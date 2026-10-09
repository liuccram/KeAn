<script setup lang="ts">
import { fetchMe } from "@/api/auth";
import {
  getChat,
  listChatMessages,
  listChatMessagesAfter,
  type ChatMessageItem,
  type ChatSessionItem
} from "@/api/chat";
import { goReport } from "@/utils/report";
import { actionBlockReason, formatChatTime, shouldShowChatTime } from "@/utils/format";
import { blockUser } from "@/api/blacklist";
import { refreshMessageBadge } from "@/utils/messageBadge";
import { resolveMediaUrl, uploadFile } from "@/utils/request";
import { isOptimistic, markMineRead, maxCreatedAtOf, maxSeqOf, mergeIncoming, sortTimeline } from "@/utils/chatMerge";
import {
  getLastSeq,
  listOutbox,
  removeOutbox,
  setLastSeq,
  setReadAt,
  type ChatSendState
} from "@/utils/chatStore";
import {
  buildLocalMessage,
  createOutboxPayload,
  createReadReporter,
  outgoingStatus,
  outgoingStatusLabel,
  retryOutboxEntry,
  sendWithOutbox,
  type ChatDisplayMessage,
  type ChatOutgoingStatus,
  type ChatSendContext,
  type ChatSendPayload
} from "@/utils/chatSync";
import FallbackImage from "@/components/FallbackImage.vue";
import { useLiveUpdates } from "@/composables/useLiveUpdates";
import { useUploadProgress } from "@/composables/useUploadProgress";
import { useUserStore } from "@/store/user";
import { onHide, onLoad, onShow, onUnload } from "@dcloudio/uni-app";
import { useToast } from "wot-design-uni";
import { computed, nextTick, ref } from "vue";

const toast = useToast();
const userStore = useUserStore();
const sessionId = ref(0);
const session = ref<ChatSessionItem | null>(null);
const messages = ref<ChatMessageItem[]>([]);
const content = ref("");
const sending = ref(false);
/** 正在发送（含自动重试中）的 localId：hydrate 出来的失败消息重发时也能显示「发送中」 */
const sendingIds = ref<string[]>([]);
/**
 * 输入区是否还要挡住新消息。
 * 自动重试（最多 3 次 + 退避）可能持续两三秒，这期间不该让用户发不出下一条；
 * 所以 composer 只看「已有本地消息还在发送中」，而不看一次请求的飞行状态。
 */
const busy = computed(() => sending.value || sendingIds.value.length > 0);
const readReporter = createReadReporter(sessionId.value);
// 解构到顶层，模板才会自动解包 ref
const { active: uploading, label: uploadLabel, onProgress, reset: resetUpload } = useUploadProgress();
const chatBlock = computed(() => actionBlockReason(userStore.state.user, "chat"));
const peerBanned = computed(() => Boolean(session.value?.peerBanned));
const peerNotice = computed(() => {
  if (peerBanned.value) {
    return "对方已被封禁";
  }
  if (session.value?.peerMuted) {
    return "对方已被禁言";
  }
  return "";
});
const sendBlocked = computed(() => chatBlock.value || (peerBanned.value ? "对方已被封禁" : ""));
const myAvatar = computed(() => resolveMediaUrl(userStore.state.user?.avatarUrl));
const peerAvatar = computed(() => resolveMediaUrl(session.value?.peerAvatarUrl));
const myUserId = computed(() => Number(userStore.state.user?.id || 0));

const displayMessages = computed<ChatDisplayMessage[]>(() => {
  return messages.value.map((item, index) => ({
    ...item,
    mine: resolveMine(item),
    sendState: sendingIds.value.indexOf(String(item.localId || "")) >= 0 ? "sending" : item.sendState,
    showTime: shouldShowChatTime(index === 0 ? null : messages.value[index - 1]?.createdAt, item.createdAt)
  }));
});

function openUser(userId?: number | null, mine = false) {
  if (mine) {
    uni.switchTab({ url: "/pages/mine/index" });
    return;
  }
  if (!userId) {
    return;
  }
  uni.navigateTo({ url: `/pages/mine/user?id=${userId}` });
}

/**
 * 是否是我发出的消息：优先后端 mine，缺失时（老后端/本地乐观消息）按 senderId 兜底。
 */
function resolveMine(message: ChatMessageItem): boolean {
  if (typeof message.mine === "boolean") {
    return message.mine;
  }
  const senderId = Number(message.senderId || 0);
  return senderId > 0 && senderId === myUserId.value;
}

function statusOf(message: ChatMessageItem): ChatOutgoingStatus {
  return outgoingStatus({ ...message, mine: resolveMine(message) });
}

function statusLabel(message: ChatMessageItem): string {
  return outgoingStatusLabel(statusOf(message));
}

function statusClass(message: ChatMessageItem): string {
  const status = statusOf(message);
  return status ? `st-${status}` : "";
}

/** seqNo 缺失（老后端）时为 0，用来判断有没有可用的增量游标 */
function latestSeq(): number {
  const seq = maxSeqOf(messages.value);
  return seq > 0 ? seq : Number(getLastSeq(sessionId.value) || 0);
}

/** 已读位点：最大值出现时立刻发，其余合并到 1 秒后 */
function reportRead(seq: number, immediate = false) {
  if (readReporter.note(seq, { immediate })) {
    refreshMessageBadge();
  }
}

function scrollToBottom() {
  uni.pageScrollTo({
    scrollTop: 99999,
    duration: 0
  });
}

async function loadSession() {
  session.value = await getChat(sessionId.value);
  uni.setNavigationBarTitle({ title: session.value.peerNickname || "私信" });
}

/**
 * 写入消息数组的统一入口：负责排序、走增量游标、不覆盖本地乐观消息。
 * batchSeq = 这批消息里的最大 seqNo（调用方用它做分页续拉/已读位点）。
 */
function upsertMessages(incoming: ChatMessageItem[]): { changed: boolean; batchSeq: number } {
  if (!incoming.length) {
    return { changed: false, batchSeq: 0 };
  }
  const rows = incoming.filter((item) => Number(item?.sessionId || 0) === sessionId.value);
  if (!rows.length) {
    return { changed: false, batchSeq: 0 };
  }
  // 只在收到「非本地乐观消息」时推进增量游标：本地那条还没有 seqNo
  const confirmed = rows.filter((item) => !isOptimistic(item));
  const batchSeq = maxSeqOf(confirmed);

  const next = mergeIncoming(messages.value, rows);
  if (next === messages.value) {
    return { changed: false, batchSeq };
  }
  messages.value = next;

  if (batchSeq > 0) {
    setLastSeq(sessionId.value, batchSeq);
  }
  const at = maxCreatedAtOf(confirmed);
  if (at > 0) {
    setReadAt(sessionId.value, at);
  }
  return { changed: true, batchSeq };
}

/**
 * 增量拉取结果：
 * - changed = 合并进了新消息
 * - ok      = 接口成功但这段时间没有新消息（不用退回整页刷新）
 * - skip    = 没有本地游标 / 接口失败，交给调用方决定兜底
 */
type SyncOutcome = "changed" | "ok" | "skip";

/** 增量拉取：afterSeq = 上次记下的 lastSeq，只取新消息并合并去重（最多翻 5 页，防跑飞） */
async function syncIncoming(batchSize = 100): Promise<SyncOutcome> {
  if (!sessionId.value) {
    return "skip";
  }
  let cursor = getLastSeq(sessionId.value);
  // 没有本地游标（首次进入 / 老后端无 seqNo）时不走增量，交给原分页逻辑
  if (!cursor) {
    return "skip";
  }
  let changed = false;
  try {
    for (let page = 0; page < 5; page += 1) {
      const increment = await listChatMessagesAfter(sessionId.value, cursor, batchSize);
      if (!increment.list.length) {
        return changed ? "changed" : "ok";
      }
      const arrived = increment.list.filter((item) => item.mine !== true);
      const result = upsertMessages(increment.list);
      if (result.changed && arrived.length) {
        reportRead(maxSeqOf(arrived));
      }
      changed = changed || result.changed;
      const nextCursor = result.batchSeq || increment.serverMaxSeq;
      // 游标没前进就停，避免服务端返回不符合契约时死循环
      if (!increment.hasMore || nextCursor <= cursor) {
        return changed ? "changed" : "ok";
      }
      cursor = nextCursor;
    }
    return changed ? "changed" : "ok";
  } catch {
    // 增量失败保持现有列表：下一轮轮询或回到前台再试
    return changed ? "changed" : "skip";
  }
}

function applyIncoming(payload?: ChatMessageItem, options?: { silent?: boolean }) {
  if (!payload || Number(payload.sessionId || 0) !== sessionId.value) {
    return;
  }
  const message = { ...payload, sessionId: sessionId.value };
  const existed = messages.value.some(
    (item) => Number(item.id) === Number(message.id) || (message.localId && item.localId === message.localId)
  );
  if (existed) {
    return;
  }
  messages.value = sortTimeline(messages.value.concat(message));
  const seq = Number(message.seqNo || 0);
  if (seq > 0) {
    setLastSeq(sessionId.value, seq);
  }
  setReadAt(sessionId.value, Date.parse(String(message.createdAt || "")) || 0);
  scrollToBottom();
  reportRead(seq);
  if (!options?.silent && !message.mine) {
    refreshMessageBadge();
  }
}

/** 首次进入没有本地游标时，仍然走原来的分页接口 */
async function loadFirstPage() {
  const data = await listChatMessages(sessionId.value, 1, 50);
  const rows = (data?.list || []).map((item) => ({ ...item, sessionId: sessionId.value }));
  messages.value = sortTimeline(rows);
  const seq = maxSeqOf(rows);
  // 老后端没有 seqNo 时游标保持 0，后续不再走增量接口，退回「每次全量拉第一页」的旧行为
  if (seq > 0) {
    setLastSeq(sessionId.value, seq);
  }
  const at = maxCreatedAtOf(rows);
  if (at > 0) {
    setReadAt(sessionId.value, at);
  }
}

/** 恢复上次没发成功的消息：只恢复「失败」态，真正在发送中的会随页面销毁重来 */
function restoreOutbox() {
  const pending = listOutbox(sessionId.value);
  if (!pending.length) {
    return;
  }
  const rows: ChatMessageItem[] = pending.map((entry) => ({
    id: -Math.floor(Math.random() * 1000000) - 1,
    sessionId: sessionId.value,
    senderId: myUserId.value,
    msgType: entry.msgType,
    content: entry.content,
    url: entry.msgType === "IMAGE" ? entry.content : null,
    createdAt: new Date(entry.updatedAt || Date.now()).toISOString(),
    mine: true,
    localId: entry.localId,
    sendState: "failed" as ChatSendState
  }));
  messages.value = sortTimeline(mergeIncoming(messages.value, rows));
}

async function loadMessages() {
  await loadFirstPage();
  restoreOutbox();
  const seq = latestSeq();
  if (seq > 0) {
    // 进入会话即上报已读；若先做增量同步，合并后的最大值会走节流窗口
    reportRead(seq, true);
  }
  await refreshMessageBadge();
  await nextTick();
  scrollToBottom();
}

function insertLocalMessage(input: {
  localId: string;
  content: string;
  msgType: string;
  sendState?: ChatSendState;
}): number {
  const payload: ChatSendPayload = {
    localId: input.localId,
    content: input.content,
    msgType: input.msgType,
    attempts: 0
  };
  const local = buildLocalMessage(payload, {
    url: input.msgType === "IMAGE" ? input.content : null,
    sendState: input.sendState
  });
  const row = { ...local, sessionId: sessionId.value, senderId: myUserId.value };
  messages.value = sortTimeline(mergeIncoming(messages.value, [row]));
  return row.id;
}

/** 重试用尽：把本地那条标成失败（红色感叹号可手动重发） */
function markLocalFailed(localId: string) {
  messages.value = messages.value.map((item) =>
    item.localId === localId ? { ...item, sendState: "failed" as ChatSendState } : item
  );
}

/** 服务端确认发送但没有消息体（老后端）：本地那条改成「已发送」 */
function markLocalSent(localId: string) {
  messages.value = messages.value.map((item) =>
    item.localId === localId ? { ...item, sendState: undefined, status: item.status ?? 1 } : item
  );
}

async function sendPayload(payload: ChatSendPayload, confirm: (localId: string, message?: ChatMessageItem) => void) {
  if (!payload.localId) {
    return;
  }
  const localId = payload.localId;
  sending.value = true;
  sendingIds.value = sendingIds.value.concat(localId);
  try {
    // 重试始终复用同一个 localId，靠服务端幂等去重
    const result = await sendWithOutbox(sendContext, payload);
    if (result.ok) {
      if (result.message) {
        upsertMessages([{ ...result.message, sessionId: sessionId.value }]);
      } else {
        // 没带回消息体的老后端：把本地那条标成「已发送单勾」，否则会一直显示发送中
        markLocalSent(localId);
      }
      confirm(localId, result.message);
      scrollToBottom();
      return;
    }
    markLocalFailed(localId);
    toast.error("消息发送失败，长按气泡或点感叹号可重发");
  } finally {
    sendingIds.value = sendingIds.value.filter((id) => id !== localId);
    sending.value = false;
  }
}

const sendContext: ChatSendContext = {
  sessionId: sessionId.value,
  insertLocal: insertLocalMessage
};

async function handleSend() {
  if (sendBlocked.value) {
    toast.error(sendBlocked.value);
    return;
  }
  const text = content.value.trim();
  if (!text || busy.value) {
    return;
  }
  const payload = createOutboxPayload(text, "TEXT");
  content.value = "";
  await sendPayload(payload, () => undefined);
}

function handleSendImage() {
  if (sendBlocked.value) {
    toast.error(sendBlocked.value);
    return;
  }
  if (busy.value) {
    return;
  }
  uni.chooseImage({
    count: 1,
    sizeType: ["original"],
    sourceType: ["album", "camera"],
    success: async (res) => {
      const filePath = res.tempFilePaths?.[0];
      if (!filePath) {
        return;
      }
      const payload = createOutboxPayload(filePath, "IMAGE");
      insertLocalMessage({
        localId: payload.localId as string,
        content: filePath,
        msgType: "IMAGE",
        sendState: "sending"
      });
      sending.value = true;
      try {
        const uploaded = await uploadFile(filePath, "CHAT", { onProgress });
        // 本地乐观消息换成 objectKey，重发时就不需要再依赖临时文件
        payload.content = uploaded.objectKey;
        sending.value = false;
        await sendPayload(payload, (localId) => {
          messages.value = messages.value.map((item) =>
            item.localId === localId ? { ...item, content: uploaded.objectKey } : item
          );
        });
      } catch (error) {
        sending.value = false;
        // 上传阶段失败只标本地失败态：本地文件路径没法当消息内容入库，
        // 重启后"重发"也没意义（临时文件可能已被清理），让用户重新选图更诚实。
        markLocalFailed(payload.localId as string);
        toast.error((error as Error).message || "图片发送失败");
      } finally {
        resetUpload();
      }
    }
  });
}

function previewImage(url?: string | null, content?: string) {
  const src = resolveMediaUrl(url || content);
  if (!src) {
    return;
  }
  uni.previewImage({ urls: [src], current: src });
}

/** 是否已经是服务端 objectKey（而不是仍在本地、需要重新上传的 blob / 临时文件） */
function isUploadedSource(source: string): boolean {
  if (!source) {
    return false;
  }
  return !/^(blob:|data:|file:|wxfile:|filesystem:|https?:\/\/localhost|.*\/_doc\/|.*_tmp_)/i.test(source);
}

/** 失败消息的重发入口：长按气泡或用感叹号角标触发 */
function handleResend(item: ChatMessageItem) {
  if (statusOf(item) !== "failed") {
    return;
  }
  const localId = String(item.localId || "");
  if (!localId) {
    toast.info("该消息无法重发，请重新发送");
    return;
  }
  uni.showActionSheet({
    itemList: ["重发", "删除"],
    success: (res) => {
      if (res.tapIndex === 0) {
        void resendMessage(localId, item);
        return;
      }
      if (res.tapIndex === 1) {
        removeOutbox(sessionId.value, localId);
        messages.value = messages.value.filter((row) => row.localId !== localId);
      }
    }
  });
}

/**
 * 图片消息失败：内容还不是 objectKey（本地临时文件）时先重新上传再发送。
 * H5 的 blob: 地址在上传失败后往往已经失效，这时只能如实提示重新选图。
 */
async function resendMessage(localId: string, item: ChatMessageItem) {
  if (item.msgType === "IMAGE") {
    const source = String(item.url || item.content || "");
    if (/^(blob:|data:)/i.test(source)) {
      toast.info("图片已失效，请重新选择图片发送");
      return;
    }
    if (!isUploadedSource(source) && !sending.value) {
      sending.value = true;
      sendingIds.value = sendingIds.value.concat(localId);
      try {
        const uploaded = await uploadFile(source, "CHAT", { onProgress });
        messages.value = messages.value.map((row) =>
          row.localId === localId ? { ...row, content: uploaded.objectKey, url: uploaded.objectKey } : row
        );
        sending.value = false;
        await sendPayload(
          { localId, content: uploaded.objectKey, msgType: "IMAGE", attempts: 0 },
          () => undefined
        );
      } catch (error) {
        if (sendingIds.value.indexOf(localId) >= 0) {
          sendingIds.value = sendingIds.value.filter((id) => id !== localId);
        }
        sending.value = false;
        markLocalFailed(localId);
        toast.error((error as Error).message || "图片发送失败");
      } finally {
        resetUpload();
      }
      return;
    }
  }
  sendingIds.value = sendingIds.value.concat(localId);
  sending.value = true;
  try {
    const result = await retryOutboxEntry(sendContext, localId);
    if (result.ok && result.message) {
      upsertMessages([{ ...result.message, sessionId: sessionId.value }]);
      scrollToBottom();
    } else {
      markLocalFailed(localId);
      toast.error("重发失败，请检查网络后重试");
    }
  } finally {
    sendingIds.value = sendingIds.value.filter((id) => id !== localId);
    sending.value = false;
  }
}

/** READ 事件：把「我发出的、seqNo ≤ maxSeq」的消息标为已读（气泡双勾） */
function applyReadReceipt(payload?: { sessionId?: number; readerId?: number; maxSeq?: number }) {
  const targetSession = Number(payload?.sessionId || 0);
  if (!targetSession || targetSession !== sessionId.value) {
    return;
  }
  // 自己别端上报的已读不需要处理（那边已经处理过了）
  if (Number(payload?.readerId || 0) === myUserId.value) {
    return;
  }
  const limit = Number(payload?.maxSeq || 0);
  if (!Number.isFinite(limit) || limit <= 0) {
    return;
  }
  const next = markMineRead(messages.value, limit);
  if (next !== messages.value) {
    messages.value = next;
  }
}

function handleReportUser() {
  const peerId = session.value?.peerUserId;
  if (!goReport("USER", peerId, session.value?.peerNickname)) {
    return;
  }
}

function handleReportLastMessage() {
  const item = [...messages.value].reverse().find((msg) => !resolveMine(msg));
  if (!item) {
    toast.info("暂无对方消息可举报");
    return;
  }
  goReport("MESSAGE", item.id, session.value?.peerNickname || "对方消息");
}

function handleBlockPeer() {
  const peerId = session.value?.peerUserId;
  if (!peerId) {
    return;
  }
  uni.showModal({
    title: "加入黑名单",
    content: `加入黑名单后，你将无法再与 ${session.value?.peerNickname || "对方"} 互发消息，确定继续？`,
    success: async (res) => {
      if (!res.confirm) {
        return;
      }
      try {
        await blockUser(peerId);
        toast.success("已加入黑名单");
        setTimeout(() => uni.navigateBack(), 400);
      } catch (error) {
        toast.error((error as Error).message || "加入黑名单失败");
      }
    }
  });
}

onLoad(async (query) => {
  sessionId.value = Number(query?.id || 0);
  if (!sessionId.value) {
    toast.error("会话不存在");
    return;
  }
  // 会话 id 到位后才绑定发送上下文与已读上报器
  sendContext.sessionId = sessionId.value;
  readReporter.reset();
  try {
    if (userStore.isLoggedIn.value) {
      try {
        const latest = await fetchMe();
        if (userStore.state.token) {
          userStore.setLogin(userStore.state.token, latest);
        }
      } catch {
        // 使用本地缓存
      }
    }
    await loadSession();
    await loadMessages();
  } catch (error) {
    toast.error((error as Error).message || "加载失败");
  }
});

onShow(() => {
  // 回到前台：带上 afterSeq 增量补拉积压的消息
  if (sessionId.value) {
    void syncIncoming();
    readReporter.flush();
  }
});

onHide(() => {
  readReporter.flush();
});

onUnload(() => {
  readReporter.flush();
});

useLiveUpdates((event) => {
  if (!event) {
    // 轮询兜底（WS 断线时）：优先增量，拿不到增量再退回整页刷新
    if (sessionId.value) {
      syncIncoming().then((outcome) => {
        // 只有"没有游标 / 接口失败"才退回整页刷新，避免每次轮询都重拉 50 条
        if (outcome === "skip" && !getLastSeq(sessionId.value)) {
          loadSession();
          loadMessages();
        }
      });
    }
    return;
  }
  if (event.type === "MESSAGE") {
    applyIncoming(event.data as ChatMessageItem | undefined);
    return;
  }
  if (event.type === "READ") {
    applyReadReceipt({
      sessionId: event.sessionId ?? event.data?.sessionId,
      readerId: event.readerId ?? event.data?.readerId,
      maxSeq: event.maxSeq ?? event.data?.maxSeq
    });
    return;
  }
  if (event.type === "NOTICE" && (event.noticeType === "PEER_BANNED" || event.noticeType === "PEER_UNBANNED")) {
    if (!event.bizId || Number(event.bizId) === sessionId.value) {
      loadSession();
    }
  }
});
</script>

<template>
  <view class="page" :class="{ 'has-peer-status': Boolean(peerNotice) }">
    <view v-if="peerNotice" class="peer-status" :class="{ banned: peerBanned }">{{ peerNotice }}</view>
    <view class="list">
      <view v-for="item in displayMessages" :key="item.id">
        <view v-if="item.showTime" class="stamp">{{ formatChatTime(item.createdAt) }}</view>
        <view class="row" :class="{ mine: item.mine }">
          <view class="avatar" @click="openUser(item.mine ? userStore.state.user?.id : session?.peerUserId, item.mine)">
            <FallbackImage
              v-if="item.mine ? myAvatar : peerAvatar"
              class="avatar-img"
              :src="item.mine ? myAvatar : peerAvatar"
              mode="aspectFill"
            />
            <text v-else>{{ ((item.mine ? userStore.state.user?.nickname : session?.peerNickname) || "同").slice(0, 1) }}</text>
          </view>
          <view
            class="bubble"
            :class="{ image: item.msgType === 'IMAGE' }"
            @longpress="handleResend(item)"
          >
            <FallbackImage
              v-if="item.msgType === 'IMAGE'"
              class="photo"
              :src="resolveMediaUrl(item.url || item.content)"
              mode="widthFix"
              @click="previewImage(item.url, item.content)"
            />
            <view v-else class="text">{{ item.content }}</view>
          </view>
          <view
            v-if="item.mine && statusClass(item)"
            class="status"
            :class="statusClass(item)"
            @click="handleResend(item)"
          >{{ statusLabel(item) }}</view>
        </view>
      </view>
    </view>
    <view class="actions">
      <text @click="handleReportUser">举报对方</text>
      <text @click="handleReportLastMessage">举报消息</text>
      <text @click="handleBlockPeer">加入黑名单</text>
    </view>
    <view v-if="sendBlocked" class="mute-tip" :class="{ banned: peerBanned }">{{ sendBlocked }}</view>
    <view v-else class="composer">
      <wd-button size="small" plain :disabled="busy" @click="handleSendImage">{{ uploading ? uploadLabel : "图片" }}</wd-button>
      <input v-model="content" class="input" confirm-type="send" placeholder="输入消息" @confirm="handleSend" />
      <wd-button size="small" type="primary" :loading="busy" @click="handleSend">发送</wd-button>
    </view>
    <wd-toast />
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: var(--kean-bg);
  padding-bottom: 112px;
}
.page.has-peer-status {
  padding-top: 48px;
}
.peer-status {
  position: fixed;
  left: 0;
  right: 0;
  top: 0;
  z-index: 30;
  margin: 0;
  padding: 12px 16px;
  background: #fff7e8;
  color: #d25f00;
  font-size: 13px;
  text-align: center;
  border-bottom: 1px solid #f2e3c6;
}
.peer-status.banned,
.mute-tip.banned {
  background: #fff1f0;
  color: var(--kean-danger);
  border-color: #fdcdc5;
}
.list {
  padding: 16px 12px;
}
.stamp {
  text-align: center;
  color: #c9cdd4;
  font-size: 12px;
  margin: 10px 0 8px;
}
.row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-bottom: 12px;
}
.row.mine {
  flex-direction: row-reverse;
}
.avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  background: var(--kean-primary-soft);
  color: var(--kean-primary-active);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
  font-weight: 600;
  overflow: hidden;
  flex-shrink: 0;
}
.avatar-img {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  display: block;
}
.bubble {
  max-width: 68%;
  background: var(--kean-card);
  border-radius: 8px;
  padding: 10px 12px;
}
.row.mine .bubble {
  background: #b8d4ee;
}
.row.mine .bubble.image {
  background: transparent;
  padding: 0;
}
.photo {
  max-width: 180px;
  border-radius: 8px;
  display: block;
}
.text {
  color: var(--kean-text);
  font-size: 15px;
  line-height: 1.5;
  word-break: break-word;
}
.row.mine .text {
  color: #1e3a5c;
}
/* 我发出的消息的送达状态：发送中 / 单勾已发送 / 双勾已读 / ! 失败可重发 */
.status {
  align-self: flex-end;
  margin-bottom: 2px;
  color: #9aa4b2;
  font-size: 12px;
  line-height: 18px;
  padding: 0 2px;
  white-space: nowrap;
}
.status.st-sending {
  color: #9aa4b2;
}
.status.st-sent {
  color: #8a9bb0;
}
.status.st-read {
  color: var(--kean-primary-active);
}
.status.st-failed {
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border-radius: 9px;
  background: var(--kean-danger);
  color: #fff;
  font-weight: 700;
  text-align: center;
}
.actions {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 56px;
  display: flex;
  justify-content: space-around;
  padding: 8px 12px;
  background: var(--kean-card);
  border-top: 1px solid var(--kean-line);
  color: var(--kean-primary);
  font-size: 13px;
}
.composer {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px calc(10px + env(safe-area-inset-bottom));
  background: var(--kean-card);
  border-top: 1px solid var(--kean-line);
}
.mute-tip {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  padding: 14px 16px calc(14px + env(safe-area-inset-bottom));
  background: #fff7e8;
  color: #d25f00;
  font-size: 13px;
  text-align: center;
  border-top: 1px solid #f2e3c6;
}
.input {
  flex: 1;
  height: 36px;
  background: var(--kean-bg);
  border-radius: 18px;
  padding: 0 12px;
  font-size: 14px;
}
</style>
