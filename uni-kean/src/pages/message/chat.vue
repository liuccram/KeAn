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
import {
  isOptimistic,
  maxCreatedAtOf,
  maxSeqOf,
  mergeConfirm,
  mergeIncoming,
  sortTimeline
} from "@/utils/chatMerge";
import {
  createLocalId,
  getLastSeq,
  getOutboxEntry,
  listOutbox,
  removeOutbox,
  setLastSeq,
  setReadAt,
  upsertOutbox,
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
import {
  EMOJI_NAME_LIST,
  emojiPathOf,
  formatEmoji,
  listRecentEmoji,
  rememberRecentEmoji,
  splitEmoji,
  type EmojiSegment
} from "@/utils/emoji";
import { t } from "@/utils/i18n";
import { onHide, onLoad, onShow, onUnload } from "@dcloudio/uni-app";
import { useToast } from "wot-design-uni";
import { computed, nextTick, onMounted, ref } from "vue";

const toast = useToast();
const userStore = useUserStore();
const sessionId = ref(0);
const session = ref<ChatSessionItem | null>(null);
const messages = ref<ChatMessageItem[]>([]);
const content = ref("");
const sending = ref(false);
/** 正在发送（含自动重试中）的 localId：hydrate 出来的失败消息重发时也能显示「发送中」 */
const sendingIds = ref<string[]>([]);
/** 已在飞行的发送请求条数：只用来显示「发送中」提示，绝不参与「能不能发」的判断 */
const inflight = ref(0);
/**
 * 是否还有发送请求在飞 —— <b>只用来显示「发送中」提示，绝不参与「能不能发」的判断</b>。
 *
 * <p>这里曾经被当成守卫（`busy` 为真就提前 return），那正是「只转圈、不出气泡」的成因之一：
 * 上一条还在重试时新的点击被丢弃，乐观气泡根本没机会插入。</p>
 *
 * <p><b>2024 补记（实测产物证据）</b>：{@code wot-design-uni} 的 {@code wd-button}
 * 在 {@code loading=true} 时<b>根本不派发 click</b>（产物 {@code wd-button.Cb9JFRLM.js}：
 * {@code f.disabled || f.loading || h("click", e)}；源码 {@code wd-button.vue} 同）。
 * 也就是说 {@code :loading="busy"} 等于在重试期间把发送按钮变成不可点，
 * 注释里过去写的「按钮仍是可点的」是<b>错的</b>。所以按钮上不再绑定 loading，
 * 改用旁边的 {@code sendingHint} 文案提示发送中。</p>
 */
const busy = computed(() => inflight.value > 0 || sending.value || sendingIds.value.length > 0);
/** 发送中的可见提示：不吞点击，只做展示 */
const sendingHint = computed(() => (busy.value ? "发送中" : ""));
// 传取值函数而不是当前值：这一行在 setup 里执行时 sessionId 还是 0（真实 id 要到 onLoad 才有），
// 值捕获会让 readReporter 永远被 !sessionId 挡在发请求之前。
// onRead 只在服务端确认已读后才重算未读角标 —— 之前是"入队即刷"，markRead 失败时角标会假清零。
const readReporter = createReadReporter(() => sessionId.value, {
  onRead: () => {
    void refreshMessageBadge();
  }
});
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

/**
 * ⚠️ 临时诊断埋点（本轮只加日志、不改逻辑）。
 *
 * 统一前缀 `[kean-send]` / `[kean-rt]`，只打长度与 ID，绝不打消息正文（隐私）。
 * 目的：让线上复现时自己说话 —— 区分「插入没执行」「插入后又被整页刷新覆盖」
 * 「插进去了但渲染侧过滤掉」这三种此前无法区分的可能。
 */
function logSend(step: string, detail: Record<string, unknown> = {}) {
  console.log(`[kean-send] ${step}`, detail);
}

/* ===========================================================================
 * 内置表情（纯前端；消息体里存的仍是**纯文本短代码** `[微笑]`）
 * ---------------------------------------------------------------------------
 * · 短代码格式与映射表来自 box-im 的 utils/emotion.web.ts（formatEmoji → `[名字]`），
 *   素材与版权声明见 src/static/emoji/（LICENSE.txt + README.md）。
 * · 这里**只往输入框里插文字**，绝不改消息结构、请求体或后端契约：
 *   发送走的还是 handleSend → createOutboxPayload(text, "TEXT")，一个字没动。
 * · 面板刻意不用 backdrop-filter / transform / filter / will-change / contain /
 *   perspective：本页有 fixed 弹层与 fixed 输入区，这些属性会创建 containing block
 *   把 fixed 后代劫持住（见仓库铁律 3）。面板本身也放在 .composer **之外**，
 *   不参与 fixed 元素做 containing block。
 * =========================================================================== */
const emojiOpen = ref(false);
/** 展开次数：面板每次重开都重新算一次「最近使用」（不引 watch，也不动其它状态） */
const emojiSessions = ref(0);
const recentEmoji = ref<string[]>([]);
const emojiRecentList = computed(() => (emojiSessions.value ? recentEmoji.value : []));

function emojiCellKey(name: string, index: number) {
  return `${index}-${name}`;
}

function refreshRecentEmoji() {
  recentEmoji.value = listRecentEmoji();
}

function toggleEmojiPanel() {
  emojiOpen.value = !emojiOpen.value;
  if (emojiOpen.value) {
    emojiSessions.value += 1;
    refreshRecentEmoji();
  }
}

/**
 * 点表情 → 把短代码插到输入框**光标处**；拿不到光标位置就追加到末尾。
 *
 * <p>⚠️ 绝不整段替换：任何取不到光标/选区的情况（小程序端原生 input 就没有
 * setSelectionRange）都退化成「追加到末尾」，已输入的内容一个字都不会丢。</p>
 */
function insertEmoji(name: string) {
  const code = formatEmoji(name);
  const value = String(content.value || "");
  let caret = value.length;
  // H5 的 <input> 是原生 DOM：优先按用户的真实光标位置插入
  const input = document.querySelector(".composer .input") as HTMLInputElement | null;
  const start = input && typeof input.selectionStart === "number" ? input.selectionStart : null;
  if (input && start !== null && start <= value.length) {
    caret = start;
  }
  const next = value.slice(0, caret) + code + value.slice(caret);
  content.value = next;
  // 复原光标到插入内容之后（小程序端 setSelectionRange 不存在 → 走追加，同样正确）
  if (input && typeof input.setSelectionRange === "function") {
    const at = caret + code.length;
    nextTick(() => {
      try {
        input.focus();
        input.setSelectionRange(at, at);
      } catch {
        // 某些内核会在失焦时抛错：忽略即可，输入框内容已经是对的
      }
    });
  }
  refreshRecentEmoji();
  recentEmoji.value = rememberRecentEmoji(name);
}

/** 打开图片选择器前收起面板：否则面板会一直悬在系统弹层上面 */
function closeEmojiPanel() {
  emojiOpen.value = false;
}

/**
 * 消息正文 → 渲染片段（纯文本里只把**已知**短代码换成内联小图）。
 *
 * <p>性能：切好的片段按「id/localId + 正文」缓存，列表重渲染不会重复切；
 * 正则与「名字 → 下标」查表都在 utils/emoji.ts 里预编译，渲染路径上不做编译。
 * 缓存条目数有上限，超了整体清空（只是一次重算，不会泄漏内存）。</p>
 */
const emojiSegmentCache = new Map<string, EmojiSegment[]>();

function messageSegments(item: ChatMessageItem): EmojiSegment[] {
  const text = String(item.content || "");
  if (!text) {
    return [];
  }
  const key = `${item.localId || item.id || 0}|${text}`;
  const cached = emojiSegmentCache.get(key);
  if (cached) {
    return cached;
  }
  const segments = splitEmoji(text);
  if (emojiSegmentCache.size > 600) {
    emojiSegmentCache.clear();
  }
  emojiSegmentCache.set(key, segments);
  return segments;
}

const displayMessages = computed<ChatDisplayMessage[]>(() => {
  const list = messages.value;
  logSend("render", { messagesLen: list.length, sendingIds: sendingIds.value.length });
  return list.map((item, index) => ({
    ...item,
    mine: resolveMine(item),
    sendState: resolveSendState(item),
    showTime: shouldShowChatTime(index === 0 ? null : messages.value[index - 1]?.createdAt, item.createdAt)
  }));
});

/**
 * 角标状态：服务端口径优先，本地「正在重发」只在服务端还没结论时才显示为发送中。
 *
 * <p>为什么不能无条件压成 sending：重发时 localId 会留在 {@code sendingIds} 直到重发结束，
 * 而重发成功后服务端已给了 seqNo/status —— 无条件压实会把刚拿回来的对勾打回「发送中」，
 * 表现为「角标要刷新才回来」。</p>
 */
function resolveSendState(item: ChatMessageItem): ChatSendState | undefined {
  if (sendingIds.value.indexOf(String(item.localId || "")) < 0) {
    return item.sendState;
  }
  const server = outgoingStatus({ ...item, mine: resolveMine(item) });
  // 服务端已经给出结论（已发送；历史取值 "read" 现在也只显示单勾）时不再覆盖；
  // 本地还是 sending/failed/未知才显示为发送中
  return server === "sent" || server === "read" ? item.sendState : "sending";
}

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

/**
 * 已读上报的位点：优先取「对方发来的消息」的最大 seqNo —— 服务端只把
 * 「对方发给我、seq_no <= maxSeq」的消息置为已读，先报自己那条会让位点跳过对方的消息。
 * 一条都没有（只有我发的 / 老后端无 seqNo）时退回全部消息的最大值。
 */
function unreadMaxSeq(): number {
  const fromPeer = messages.value.filter((item) => resolveMine(item) === false);
  const seq = maxSeqOf(fromPeer);
  return seq > 0 ? seq : maxSeqOf(messages.value);
}

/** 已读位点：最大值出现时立刻发，其余合并到 1 秒后 */
function reportRead(seq: number, immediate = false) {
  readReporter.note(seq, { immediate });
}

/**
 * 进会话（以及每次全量刷新后）无条件上报一次已读：
 * - 有 seqNo 就带 maxSeq（服务端会清未读 + 把对方发给我的消息置已读）；
 * - 一条 seqNo 都拿不到（老后端 / 序号字段缺失）时不发 maxSeq=0（那等于"读到第 0 条"，
 *   服务端会当成位点 0 处理），改为走不带 body 的老口径，只清自己那侧未读数。
 *
 * <p>⚠️ 这条上报链路只服务于「自己的未读角标」，<b>本轮一行未动</b>：
 * 与气泡上的对勾无关，也不能因为去掉双勾而删掉（删了未读就永远不清零）。</p>
 */
function chatRead(): void {
  const seq = unreadMaxSeq();
  if (seq > 0) {
    readReporter.note(seq, { immediate: true });
    return;
  }
  readReporter.flushUnscoped();
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
  const rows = incoming.filter((item) => {
    if (!item) {
      return false;
    }
    const target = Number(item.sessionId || 0);
    if (target) {
      return target === sessionId.value;
    }
    // 老后端不保证回 sessionId：只认「localId 能对上本地已有条目」的那种，
    // 那是本会话刚发出去、服务端回显的同一条。localId 由本端生成，不会跨会话撞车；
    // 认不出来的一律丢弃 —— 绝不把别的会话、或排不了序的野消息插进来。
    if (!item.localId) {
      return false;
    }
    return messages.value.some((row) => String(row.localId || "") === String(item.localId));
  });
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
  logSend("assign:upsertMessages", { before: messages.value.length, after: next.length, rows: rows.length });
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

function applyIncoming(payload?: ChatMessageItem) {
  if (!payload || Number(payload.sessionId || 0) !== sessionId.value) {
    return;
  }
  const message = { ...payload, sessionId: sessionId.value };
  const existed = messages.value.some(
    (item) => Number(item.id) === Number(message.id) || (message.localId && item.localId === message.localId)
  );
  logSend("applyIncoming", { id: message.id, localId: message.localId || "", existed });
  if (existed) {
    return;
  }
  messages.value = sortTimeline(messages.value.concat(message));
  logSend("assign:applyIncoming", { len: messages.value.length });
  const seq = Number(message.seqNo || 0);
  if (seq > 0) {
    setLastSeq(sessionId.value, seq);
  }
  setReadAt(sessionId.value, Date.parse(String(message.createdAt || "")) || 0);
  scrollToBottom();
  reportRead(seq);
  // 角标不在这里刷：消息到屏不等于服务端已读到（未读数由 markRead 清零），
  // 真正的重算在 readReporter 的 onRead 回调里
}

/**
 * IM（box 通道）私聊消息的节流增量拉取。
 *
 * <p>box 的推送体不带 kean 的会话序号语义，且和自研通道可能几乎同时到达同一批消息；
 * 逐条触发增量会把一个「连发 5 条」变成 5 次请求，所以合并到 400ms 内只拉一次。</p>
 */
let incomingSyncTimer: ReturnType<typeof setTimeout> | null = null;
const INCOMING_SYNC_DELAY_MS = 400;

/**
 * 收到 MESSAGE 事件（两条通道都走这里）。{@code eventSessionId} 是事件顶层的会话 id
 * （IM 通道会把 {@code data.sessionId} 也镜像到顶层），两者取到哪个用哪个。
 *
 * <p><b>取舍：不做「直接插气泡」，改为按本地游标拉一次增量</b>，原因有三：
 * 1) 推送来的那份数据没有 kean 的消息 id，直接插会与随后 HTTP/增量拉回来的同一条重复，
 *    而只有增量接口返回的是权威版本（带 id/seqNo/status）；
 * 2) 乱序：推送与本地 outbox 里的乐观消息、以及正在发送的那条需要一个统一的合并点，
 *    {@code syncIncoming} → {@code mergeIncoming} 就是那个点（按 localId 用服务端版本替换本地那条）；
 * 3) 与两条通道并存时的行为一致：自研通道带完整消息体时仍照旧直接插入（下面 applyIncoming 那条），
 *    只有「没有 id / 不是权威版本」的推送才退化成拉增量。</p>
 *
 * <p>会话 id 对不上的推送直接忽略 —— 后端未上线 {@code sessionId} 字段时就是这个分支，
 * 表现为「只刷未读角标、气泡等轮询补齐」，与本轮之前的现状一致。</p>
 *
 * <p>参数故意收 {@code unknown}：两条通道（自研 WS / box IM）投递过来的 {@code data}
 * 在类型上是并集，这里显式收窄一次，避免在调用点写容易出错的断言。</p>
 */
function handleMessageEvent(data: unknown, eventSessionId?: number) {
  if (!data || typeof data !== "object") {
    return;
  }
  const payload = data as Partial<ChatMessageItem> & { sessionId?: unknown };
  const target = Number(payload.sessionId || eventSessionId || 0);
  if (!target || target !== sessionId.value) {
    return;
  }
  const seq = Number(payload.seqNo || 0);
  // 权威版本（带消息 id，且有 seqNo 可排序）仍按原逻辑立即上屏；
  // 其余（例如 IM 通道里 id 缺席的那份）交给增量拉取，拿权威版本再合并。
  if (Number(payload.id || 0) !== 0 && seq > 0) {
    applyIncoming(data as Partial<ChatMessageItem>);
    return;
  }
  const cursor = Number(getLastSeq(sessionId.value) || 0);
  if (cursor <= 0) {
    // 没有游标就没有增量可拉：只有带 localId 的推送（发送方回显 / 自己别的端发的）才插入，
    // 它能和本地乐观消息按 localId 去重；其余情况宁可不插 —— 轮询兜底会走整页刷新，
    // 消息不会丢，但不会出现「没有 id、排不了序」的野气泡。
    if (payload.localId) {
      applyIncoming(data as Partial<ChatMessageItem>);
    }
    return;
  }
  if (incomingSyncTimer) {
    return;
  }
  incomingSyncTimer = setTimeout(() => {
    incomingSyncTimer = null;
    void syncIncoming();
  }, INCOMING_SYNC_DELAY_MS);
}

/**
 * 本地还没被服务端确认的消息（乐观插入或从 outbox 恢复的失败消息）。
 *
 * <p>它是「气泡只增不灭」的守卫：整页刷新（{@link loadFirstPage}）会把列表
 * 换成服务端那一页，而断网时本地那条<b>服务端根本没有</b> —— 不把它挑出来重挂，
 * 就会「刷新一下气泡就没了」。用户在断网时看到气泡消失，自然会以为消息丢了。</p>
 */
function unsentLocalMessages(): ChatMessageItem[] {
  return messages.value.filter((item) => {
    if (item.localId && isOptimistic(item)) {
      return true;
    }
    // 兜底：会话 id 没写上的本地条目（老数据）也算，宁可多留一条也不吞掉用户的消息
    return Number(item.id || 0) < 0;
  });
}

/** 首次进入没有本地游标时，仍然走原来的分页接口 */
async function loadFirstPage() {
  const data = await listChatMessages(sessionId.value, 1, 50);
  const rows = (data?.list || []).map((item) => ({ ...item, sessionId: sessionId.value }));
  // 服务端那一页 + 本地未确认的那些：合并而不是替换，避免整页刷新把乐观气泡冲掉
  const keepLocal = unsentLocalMessages();
  logSend("assign:loadFirstPage", {
    before: messages.value.length,
    rows: rows.length,
    keepLocal: keepLocal.length
  });
  messages.value = sortTimeline(mergeIncoming(rows, keepLocal));
  logSend("assign:loadFirstPage.done", { after: messages.value.length, displayLen: displayMessages.value.length });
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
  logSend("restoreOutbox", { pending: pending.length, before: messages.value.length });
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
  logSend("assign:restoreOutbox", { after: messages.value.length, displayLen: displayMessages.value.length });
}

async function loadMessages() {
  await loadFirstPage();
  restoreOutbox();
  // 进入会话即上报已读（有 seqNo 带 maxSeq，没有就退回老口径）；后续更大的值走 1 秒节流。
  // 角标不在这里刷：必须在服务端确认已读后再重算，否则 markRead 失败时角标会假清零。
  chatRead();
  await nextTick();
  scrollToBottom();
}

/**
 * 乐观插入本地消息（幂等）。
 *
 * <p>两条硬性约束：</p>
 * <ol>
 *   <li><b>同步执行</b>：调用点必须在任何 await / 任何守卫之前，按下发送气泡立刻可见；</li>
 *   <li><b>不经过任何会话过滤</b>：这里是列表的直接写入，只做 mergeIncoming（按 localId 去重）
 *       + sortTimeline，sessionId 只作为字段写入，不参与筛选 —— 断网、会话 id 迟到都不影响上屏。</li>
 * </ol>
 *
 * <p>同一个 localId 重复调用只会返回已存在那条的 id，不会产生第二个气泡
 * （图片先插入、随后 sendWithOutbox 再插一次就是走这条幂等分支）。</p>
 */
function insertLocalMessage(input: {
  localId: string;
  content: string;
  msgType: string;
  sendState?: ChatSendState;
}): number {
  logSend("insertLocal.enter", {
    localId: input.localId || "",
    hasLocalId: Boolean(input.localId),
    msgType: input.msgType,
    len: input.content.length,
    before: messages.value.length
  });
  if (!input.localId) {
    // 没有 localId 就没有去重键：这里不是「本地乐观消息」的入口，直接不插
    logSend("insertLocal.return", { reason: "skip:empty-localId", messagesLen: messages.value.length });
    return 0;
  }
  const existed = messages.value.find((item) => String(item.localId || "") === input.localId);
  if (existed) {
    logSend("insertLocal.return", {
      reason: "skip:duplicate",
      id: existed.id,
      messagesLen: messages.value.length
    });
    return Number(existed.id);
  }
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
  // 插入后立刻对比「数组长度」与「实际渲染的 computed 长度」：
  // 两个数不一致就说明渲染侧把它过滤掉了。
  logSend("insertLocal.return", {
    reason: "inserted",
    id: row.id,
    messagesLen: messages.value.length,
    displayLen: displayMessages.value.length
  });
  nextTick(() => {
    logSend("insertLocal.nextTick", {
      messagesLen: messages.value.length,
      displayLen: displayMessages.value.length
    });
    // ⚠️ 必须滚动到底！乐观插入的气泡在列表末尾，不滚用户就"以为消息没发出去"（
    // 这正是线上反馈「断网发送看不到气泡」的真正原因：联网时消息走 applyIncoming（见 :337）会滚，
    // 离线时只走这条乐观插入路径，之前这里只打日志没滚动 → 气泡其实已经渲染，只是停在视口之外）。
    scrollToBottom();
  });
  return row.id;
}

/** 重试用尽：把本地那条标成失败（红色感叹号可手动重发）。只改角标，绝不移除气泡 */
function markLocalFailed(localId: string) {
  const found = messages.value.some((item) => item.localId === localId);
  logSend("markLocalFailed", { localId: localId || "", found, len: messages.value.length });
  messages.value = messages.value.map((item) =>
    item.localId === localId ? { ...item, sendState: "failed" as ChatSendState } : item
  );
  logSend("markLocalFailed.done", { localId: localId || "", len: messages.value.length, found });
}

/**
 * 服务端确认落库、但回执里没有消息体（老后端）：此时气泡已不可删除，
 * 若 outbox 里没有这条（原实现只在失败时落盘），「重发」会取不到内容而假失败。
 * 这里补写一份原始内容，让重发路径依然可用。
 */
function rememberOutboxContent(entry: { localId: string; msgType: string; content: string; attempts: number }) {
  if (getOutboxEntry(sessionId.value, entry.localId)) {
    return;
  }
  upsertOutbox({
    localId: entry.localId,
    sessionId: sessionId.value,
    content: entry.content,
    msgType: entry.msgType,
    attempts: entry.attempts,
    updatedAt: Date.now()
  });
}

/** 服务端确认发送但没有消息体（老后端）：本地那条改成「已发送」 */
function markLocalSent(localId: string) {
  logSend("markLocalSent", { localId: localId || "" });
  messages.value = messages.value.map((item) =>
    item.localId === localId ? { ...item, sendState: undefined, status: item.status ?? 1 } : item
  );
}

/**
 * 重发时本地没有可重发的原始内容（例如这条其实已经落库、只是回执里没有消息体）。
 * 此时不能假装重发成功、也不能让气泡永远停在"发送中"：
 * 回到「已发送」角标并如实提示，用户可以重新输入内容发送。
 */
function markResendUnavailable(localId: string) {
  markLocalSent(localId);
  toast.info("这条已发出，无法再次发送");
}

/**
 * 发送成功回执：用服务端版本按 localId 替换本地那条乐观消息（清掉 sendState）。
 * 不走 upsertMessages —— 那条路径会按 sessionId 过滤，老后端不回 sessionId 时会整行丢弃，
 * 本地那条就永远停在"发送中"并盖住对勾。
 */
function confirmIncoming(message?: ChatMessageItem) {
  if (!message || typeof message !== "object") {
    return;
  }
  const row = { ...message, sessionId: sessionId.value };
  logSend("confirmIncoming", { id: row.id, localId: row.localId || "", seqNo: Number(row.seqNo || 0), status: row.status });
  messages.value = mergeConfirm(messages.value, [row]);
  logSend("confirmIncoming.done", { len: messages.value.length, displayLen: displayMessages.value.length });
  const seq = Number(row.seqNo || 0);
  if (seq > 0) {
    setLastSeq(sessionId.value, seq);
  }
}

/**
 * 「进行中」状态的看门狗。
 *
 * <p>一次发送最坏耗时 = 3 次网络尝试 × 15s 请求超时 + 两次退避(400/800ms)，约 47s；
 * 兜底时限 {@link SEND_FALLBACK_DEADLINE_MS} 是 32s。所以 60s 足够覆盖任何正常路径。</p>
 *
 * <p>为什么必须有：{@code inflight} / {@code sendingIds} 一旦因任何意外没被清掉，
 * 界面就会永久停在「发送中」，而用户看到的只是「点了没反应」。宁可误清一次（只是
 * loading 停早了一点，消息本身不受影响，气泡与失败角标照旧），也不能让界面永久卡死。</p>
 */
const BUSY_WATCHDOG_MS = 60000;
let busyWatchdogTimer: ReturnType<typeof setTimeout> | null = null;

function armBusyWatchdog() {
  if (busyWatchdogTimer) {
    return;
  }
  busyWatchdogTimer = setTimeout(() => {
    busyWatchdogTimer = null;
    if (inflight.value > 0 || sendingIds.value.length > 0) {
      console.warn("[kean-send] busy watchdog fired: 发送中状态超过上限未清，强制复位", {
        inflight: inflight.value,
        sendingIds: sendingIds.value.length
      });
      inflight.value = 0;
      sendingIds.value = [];
    }
    if (sending.value) {
      sending.value = false;
    }
  }, BUSY_WATCHDOG_MS);
}

function clearBusyWatchdog() {
  if (!busyWatchdogTimer) {
    return;
  }
  // 只有在确实没有在飞请求/在途重发时才取消，否则下一轮发送就没有保护了
  if (inflight.value <= 0 && sendingIds.value.length === 0 && !sending.value) {
    clearTimeout(busyWatchdogTimer);
    busyWatchdogTimer = null;
  }
}

async function sendPayload(payload: ChatSendPayload, confirm: (localId: string, message?: ChatMessageItem) => void) {
  const localId = String(payload.localId || "");
  if (!localId) {
    logSend("sendPayload.return", { reason: "no-localId" });
    return;
  }
  // 计数与递减在同一个函数里配对，无论从哪条路径进来都不会漏减（loading 不会卡住）
  inflight.value += 1;
  armBusyWatchdog();
  logSend("sendPayload.enter", { localId, msgType: payload.msgType, inflight: inflight.value, sessionId: sessionId.value });
  try {
    // 重试始终复用同一个 localId，靠服务端幂等去重
    const result = await sendWithOutbox(sendContext, payload);
    logSend("sendPayload.result", { localId, ok: result.ok, inserted: result.inserted, hasMessage: Boolean(result.message) });
    if (result.ok) {
      if (result.message) {
        confirmIncoming(result.message);
      } else {
        // 没带回消息体的老后端：把本地那条标成「已发送单勾」，否则会一直显示发送中
        markLocalSent(localId);
      }
      confirm(localId, result.message);
      scrollToBottom();
      logSend("sendPayload.return", {
        reason: "ok",
        len: messages.value.length,
        displayLen: displayMessages.value.length
      });
      return;
    }
    // 失败只换角标：气泡留在原地（红色感叹号，可点击/长按重发）
    logSend("sendPayload.return", {
      reason: "failed",
      inserted: result.inserted,
      len: messages.value.length,
      displayLen: displayMessages.value.length
    });
    if (result.inserted) {
      markLocalFailed(localId);
      toast.error("消息发送失败，长按气泡或点感叹号可重发");
      return;
    }
    // 极端情况：气泡没能显示出来（列表异常），此时标"失败"会是隐形的，如实提示重发
    toast.error("消息未能显示，请重新发送");
  } catch (error) {
    // 兜底：任何意外都让这条消息进入失败态，而不是留下一个永远「发送中」的气泡
    logSend("sendPayload.throw", { localId, error: String((error as Error)?.message || error) });
    markLocalFailed(localId);
    toast.error((error as Error)?.message || "消息发送失败，长按气泡可重发");
  } finally {
    // 这里的 finally + chatSync 内部的兜底超时，共同保证按钮 loading 一定会被清掉
    inflight.value = Math.max(0, inflight.value - 1);
    clearBusyWatchdog();
  }
}

const sendContext: ChatSendContext = {
  sessionId: sessionId.value,
  onOutbox: (entry) => {
    rememberOutboxContent(entry);
  },
  insertLocal: insertLocalMessage
};

/** 发送前必做：气泡先落地（同步、不受任何守卫影响），再进网络阶段 */
function enqueueLocalMessage(payload: ChatSendPayload) {
  const localId = payload.localId || createLocalId();
  payload.localId = localId;
  logSend("enqueue.enter", { localId, msgType: payload.msgType, len: payload.content.length });
  const insertedId = insertLocalMessage({
    localId,
    content: payload.content,
    msgType: payload.msgType,
    sendState: "sending"
  });
  logSend("enqueue.exit", { localId, insertedId, messagesLen: messages.value.length });
  return localId;
}

/**
 * 点「发送」。
 *
 * <p><b>这里绝不因为「上一条还在发」而丢弃本次输入</b>：{@code content} 已在同步阶段清空，
 * 用户看到输入框空了却什么都没有发生，是比重复提交严重得多的缺陷。
 * 重复提交本身由两点兜住：① 输入框内容清空后第二次点击走 {@code !text} 直接返回；
 * ② 服务端按 {@code localId} 幂等去重。</p>
 */
async function handleSend() {
  // 第一行埋点：能区分「点击根本没进来」（wd-button 吞掉 / 按钮不可点）与「进来了但没插气泡」
  logSend("click:send", {
    len: content.value.length,
    sessionId: sessionId.value,
    busy: busy.value,
    inflight: inflight.value,
    sending: sending.value,
    blocked: sendBlocked.value
  });
  if (sendBlocked.value) {
    toast.error(sendBlocked.value);
    return;
  }
  const text = content.value.trim();
  if (!text) {
    logSend("click:send.skip", { reason: "empty-text" });
    return;
  }
  const payload = createOutboxPayload(text, "TEXT");
  content.value = "";
  // 发送后收起表情面板：输入框已清空，还挂着面板没有意义
  closeEmojiPanel();
  // ⭐ 乐观插入在任何 await / 任何守卫之前：点一下必然出现气泡
  enqueueLocalMessage(payload);
  if (!sessionId.value) {
    // 会话 id 还没就绪（onLoad 未完成）：直接给失败态 + 可重发，绝不静默丢弃
    logSend("click:send.skip", { reason: "no-sessionId", localId: payload.localId });
    markLocalFailed(payload.localId as string);
    toast.error("会话信息未就绪，请重新进入会话");
    return;
  }
  await sendPayload(payload, () => undefined);
}

function handleSendImage() {
  if (sendBlocked.value) {
    toast.error(sendBlocked.value);
    return;
  }
  // 系统选图弹层起来之前先收面板（面板是 fixed 的，留着会和系统弹层打架）
  closeEmojiPanel();
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
      // ① 气泡先出现（本地临时路径直接能显示），再谈上传
      enqueueLocalMessage(payload);
      sending.value = true;
      armBusyWatchdog();
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
        clearBusyWatchdog();
      }
    },
    // 取消选图时也要把上传中状态收掉，否则 sending 会一直为真、按钮永远转圈
    fail: () => {
      sending.value = false;
      resetUpload();
      clearBusyWatchdog();
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
    if (!isUploadedSource(source)) {
      sendingIds.value = sendingIds.value.concat(localId);
      sending.value = true;
      armBusyWatchdog();
      try {
        const uploaded = await uploadFile(source, "CHAT", { onProgress });
        messages.value = messages.value.map((row) =>
          row.localId === localId ? { ...row, content: uploaded.objectKey, url: uploaded.objectKey } : row
        );
        sending.value = false;
        await sendPayload({ localId, content: uploaded.objectKey, msgType: "IMAGE", attempts: 0 }, () => undefined);
      } catch (error) {
        sending.value = false;
        markLocalFailed(localId);
        toast.error((error as Error).message || "图片发送失败");
      } finally {
        sendingIds.value = sendingIds.value.filter((id) => id !== localId);
        sending.value = false;
        resetUpload();
      }
      return;
    }
  }
  // 重发前确保这条气泡还在：气泡只增不灭（用户主动删除除外），重发不该把它弄丢
  insertLocalMessage({
    localId,
    content: String(item.content || ""),
    msgType: item.msgType,
    sendState: "sending"
  });
  sendingIds.value = sendingIds.value.concat(localId);
  armBusyWatchdog();
  try {
    const result = await retryOutboxEntry(sendContext, localId);
    if (result.ok && result.message) {
      // 与首发同一条路径：按 localId 用服务端版本替换本地那条（清掉"发送中"）
      confirmIncoming(result.message);
      scrollToBottom();
    } else if (result.ok) {
      // 老后端没回消息体：本地那条改成单勾，别一直卡在"发送中"
      markLocalSent(localId);
    } else if (getOutboxEntry(sessionId.value, localId)) {
      markLocalFailed(localId);
      toast.error("重发失败，请检查网络后重试");
    } else {
      // outbox 里没有原始内容（例如上次其实已落库、只是回执丢了消息体）：
      // 如实告诉用户无法重发，并回到「已发送」角标，不留下一条永远"发送中"的气泡
      markResendUnavailable(localId);
    }
  } finally {
    sendingIds.value = sendingIds.value.filter((id) => id !== localId);
    clearBusyWatchdog();
  }
}

/**
 * ⚠️ 本轮已删除 `markMineReadLocal` / `applyReadReceipt` 两个函数
 * （以及 `useLiveUpdates` 里的 `READ` 分支）：
 *
 * 它们的**唯一**作用是「收到对方已读 → 把我发出的气泡标成双勾」，
 * 也就是「对方已读」这条信息的渲染。气泡不再展示它之后，这两个函数与
 * `chatMerge.markMineRead` 一起成为死代码，留着只会让人误以为「已读还在驱动界面」。
 *
 * ⚠️ 刻意**不删**的是「已读上报」（`createReadReporter` / `chatRead` / `readReporter.note`）：
 * 它服务于**自己的未读角标**，与双勾是两条完全不同的链路。
 */

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
    // 进会话（首次 onLoad 后紧跟 onShow）也走这里兜一次：loadMessages 抛错时
    // 至少还能按"当前已显示的最大 seqNo"上报一次已读，未读数不会一直挂着。
    // note() 自带幂等（没有更大的 seqNo 就什么都不发），不会重复打接口。
    chatRead();
  }
});

onHide(() => {
  closeEmojiPanel();
  readReporter.flush();
});

onUnload(() => {
  closeEmojiPanel();
  readReporter.flush();
});

useLiveUpdates((event) => {
  console.log("[kean-rt] page handler", {
    kind: event ? event.type || "unknown" : "poll",
    sessionId: event?.sessionId,
    maxSeq: event?.maxSeq
  });
  if (!event) {
    // 轮询兜底（WS 断线时）：优先增量，拿不到增量再退回整页刷新
    if (sessionId.value) {
      syncIncoming()
        .then((outcome) => {
          // 只有"没有游标 / 接口失败"才退回整页刷新，避免每次轮询都重拉 50 条
          if (outcome === "skip" && !getLastSeq(sessionId.value)) {
            // 断网时这两个请求必然失败：显式捕获，否则会变成一条看不到来源的未捕获拒绝
            void loadSession().catch((error) => {
              console.log("[kean-rt] poll loadSession failed", { error: String((error as Error)?.message || error) });
            });
            void loadMessages().catch((error) => {
              console.log("[kean-rt] poll loadMessages failed", { error: String((error as Error)?.message || error) });
            });
          }
        })
        .catch((error) => {
          console.log("[kean-rt] poll syncIncoming failed", { error: String((error as Error)?.message || error) });
        });
    }
    return;
  }
  if (event.type === "MESSAGE") {
    handleMessageEvent(event.data, event.sessionId);
    return;
  }
  // ⚠️ 本轮<b>移除</b> READ 分支（原来调 applyReadReceipt → markMineRead 标双勾）：
  //    气泡不再展示「对方已读」，收到 READ 事件就<b>不做任何事</b>（落到函数末尾自然结束）。
  //    这里刻意不留空分支、也不打日志：一是「不订阅/不处理」才是不再依赖实时已读的证据，
  //    二是这两条通道的 READ 本来就只来自自研 WS（box 的 imSocket 恒定不产生 READ），
  //    服务端 READ 推送另有 kean.im.read-receipt-enabled 单独把关（默认仍推）。
  if (event.type === "NOTICE" && (event.noticeType === "PEER_BANNED" || event.noticeType === "PEER_UNBANNED")) {
    if (!event.bizId || Number(event.bizId) === sessionId.value) {
      loadSession();
    }
  }
});

/**
 * 全局兜底埋点：离线分支抛出的未捕获异常此前在 H5 上完全不可见
 * （乐观插入若在某处抛错，用户只看到「点了没反应」）。
 */
onMounted(() => {
  logSend("mounted", { sendingHint: sendingHint.value });
  if (typeof window === "undefined") {
    return;
  }
  window.addEventListener(
    "unhandledrejection",
    (event: PromiseRejectionEvent) => {
      const reason = event?.reason;
      console.error("[kean-send][uncaught] unhandledrejection", {
        message: String((reason as Error)?.message || reason),
        stack: String((reason as Error)?.stack || "").split("\n").slice(0, 4).join(" | "),
        messagesLen: messages.value.length
      });
    },
    true
  );
  window.addEventListener(
    "error",
    (event: ErrorEvent) => {
      console.error("[kean-send][uncaught] error", {
        message: String(event?.message || ""),
        source: String(event?.filename || ""),
        line: event?.lineno,
        col: event?.colno,
        messagesLen: messages.value.length
      });
    },
    true
  );
});
</script>

<template>
  <view class="page" :class="{ 'has-peer-status': Boolean(peerNotice) }">
    <view v-if="peerNotice" class="peer-status" :class="{ banned: peerBanned }">{{ peerNotice }}</view>
    <view class="list" @click="closeEmojiPanel">
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
            <!-- 文字气泡：已知短代码（如 [微笑]）渲染成与行高对齐的内联小图，
                 未知的方括号内容、URL、@ 之类一律原样显示（只有 image 段走图） -->
            <view v-else class="text">
              <template v-for="(segment, segIndex) in messageSegments(item)" :key="segIndex">
                <FallbackImage
                  v-if="segment.type === 'emoji'"
                  class="text-emoji"
                  :src="segment.path"
                  mode="aspectFit"
                />
                <template v-else>{{ segment.text }}</template>
              </template>
            </view>
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
      <wd-button size="small" plain @click="handleSendImage">{{ uploading ? uploadLabel : "图片" }}</wd-button>
      <input v-model="content" class="input" confirm-type="send" placeholder="输入消息" @confirm="handleSend" />
      <!-- 表情面板开关：文案走 t()（zh/en 同序），表情**名字**不 i18n（它们是数据，且以图呈现） -->
      <text class="emoji-toggle" :class="{ active: emojiOpen }" @click.stop="toggleEmojiPanel">{{ t("chatEmojiToggle") }}</text>
      <!-- 「发送中」只做展示，不吞点击：wd-button 在 loading=true 时不会派发 click，
           所以这个提示必须放在按钮外面，按钮本身永远保持可点（重复提交由清空输入框
           + 服务端 localId 幂等兜住，绝不靠丢弃用户的点击来防） -->
      <text v-if="sendingHint" class="sending-tip">{{ sendingHint }}</text>
      <wd-button size="small" type="primary" @click="handleSend">发送</wd-button>
    </view>
    <!-- 表情面板：刻意放在 .composer **外面**（composer 是 position:fixed，
         面板留在里面就会跟着 fixed 定位一起跑），只贴住 composer 上沿，
         面板自身不含任何会创建 containing block 的属性 -->
    <view v-if="emojiOpen && !sendBlocked" class="emoji-panel" @click.stop @mousedown.prevent>
      <view class="emoji-panel__title">{{ t("chatEmojiTitle") }}</view>
      <scroll-view class="emoji-scroll" scroll-y>
        <template v-if="emojiRecentList.length">
          <view class="emoji-group-title">{{ t("chatEmojiRecent") }}</view>
          <view class="emoji-grid">
            <view
              v-for="(name, index) in emojiRecentList"
              :key="emojiCellKey(name, index)"
              class="emoji-cell"
              @click="insertEmoji(name)"
            >
              <FallbackImage class="emoji-face" :src="emojiPathOf(name)" mode="aspectFit" />
            </view>
          </view>
        </template>
        <view class="emoji-group-title">{{ t("chatEmojiTitle") }}</view>
        <view class="emoji-grid">
          <view
            v-for="(name, index) in EMOJI_NAME_LIST"
            :key="emojiCellKey(name, index)"
            class="emoji-cell"
            @click="insertEmoji(name)"
          >
            <FallbackImage class="emoji-face" :src="`/static/emoji/${index}.png`" mode="aspectFit" />
          </view>
        </view>
      </scroll-view>
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
/* 气泡里的表情：内联小图，vertical-align: middle 让它与 1.5 行高的文字对齐 */
.text-emoji {
  display: inline-block;
  width: 26px;
  height: 26px;
  margin: 0 1px;
  vertical-align: middle;
}
.row.mine .text {
  color: #1e3a5c;
}
/* 我发出的消息的送达状态：发送中 / 单勾已发送 / ! 失败可重发
   ⚠️ 本轮移除 `.st-read`（双勾）样式：status 3 与 status 1 一样落到 .st-sent 单勾，
   见 utils/chatSync.ts 的 outgoingStatus / outgoingStatusLabel。 */
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
/* 表情开关：和「图片」按钮同高，按下态只改颜色 */
.emoji-toggle {
  flex-shrink: 0;
  height: 36px;
  line-height: 36px;
  padding: 0 4px;
  color: var(--kean-primary);
  font-size: 14px;
}
.emoji-toggle.active {
  color: var(--kean-primary-active);
  font-weight: 600;
}
/* ===========================================================================
 * 表情面板（纯前端装饰，不含任何会创建 containing block 的属性）
 * ---------------------------------------------------------------------------
 * ⚠️ 面板固定在输入区上沿：bottom = composer 高度 56px（10+36+10）+ 安全区，
 *    因此**永远不会盖住输入框、表情按钮和发送按钮**；关掉时是 v-if，不占位。
 * ⚠️ 高度 232px + 内部 scroll-view 滚动：73 个表情一屏放不下，滚动由 scroll-view 负责。
 * =========================================================================== */
.emoji-panel {
  position: fixed;
  left: 0;
  right: 0;
  bottom: calc(56px + env(safe-area-inset-bottom));
  z-index: 60;
  height: 232px;
  padding: 8px 10px 6px;
  box-sizing: border-box;
  background: var(--kean-card);
  border-top: 1px solid var(--kean-line);
}
.emoji-panel__title {
  font-size: 12px;
  line-height: 16px;
  color: #9aa4b2;
  padding: 0 2px 6px;
}
.emoji-scroll {
  height: 196px;
}
.emoji-group-title {
  font-size: 12px;
  line-height: 16px;
  color: #9aa4b2;
  padding: 4px 2px 6px;
}
/* 8 列网格：73 个表情排 10 行，交给 scroll-view 滚 */
.emoji-grid {
  display: grid;
  grid-template-columns: repeat(8, 1fr);
  gap: 6px;
}
.emoji-cell {
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
}
.emoji-cell:active {
  background: var(--kean-bg);
}
.emoji-face {
  width: 30px;
  height: 30px;
  display: block;
}
/* 发送中的文字提示：替代会吞点击的按钮 loading */
.sending-tip {
  color: #9aa4b2;
  font-size: 12px;
  white-space: nowrap;
}
</style>
