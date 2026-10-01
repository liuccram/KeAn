<script setup lang="ts">
import { fetchMe } from "@/api/auth";
import {
  getChat,
  listChatMessages,
  markChatRead,
  sendChatMessage,
  type ChatMessageItem,
  type ChatSessionItem
} from "@/api/chat";
import { goReport } from "@/utils/report";
import { actionBlockReason, formatChatTime, shouldShowChatTime } from "@/utils/format";
import { blockUser } from "@/api/blacklist";
import { refreshMessageBadge } from "@/utils/messageBadge";
import { resolveMediaUrl, uploadFile } from "@/utils/request";
import { useLiveUpdates } from "@/composables/useLiveUpdates";
import { useUploadProgress } from "@/composables/useUploadProgress";
import { useUserStore } from "@/store/user";
import { onLoad } from "@dcloudio/uni-app";
import { useToast } from "wot-design-uni";
import { computed, nextTick, ref } from "vue";

const toast = useToast();
const userStore = useUserStore();
const sessionId = ref(0);
const session = ref<ChatSessionItem | null>(null);
const messages = ref<ChatMessageItem[]>([]);
const content = ref("");
const sending = ref(false);
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

const displayMessages = computed(() => {
  return messages.value.map((item, index) => ({
    ...item,
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

async function loadSession() {
  session.value = await getChat(sessionId.value);
  uni.setNavigationBarTitle({ title: session.value.peerNickname || "私聊" });
}

async function loadMessages() {
  const data = await listChatMessages(sessionId.value, 1, 50);
  messages.value = data.list;
  await markChatRead(sessionId.value);
  await refreshMessageBadge();
  await nextTick();
  scrollToBottom();
}

function scrollToBottom() {
  uni.pageScrollTo({
    scrollTop: 99999,
    duration: 0
  });
}

function applyIncoming(payload?: ChatMessageItem) {
  if (!payload || payload.sessionId !== sessionId.value) {
    return;
  }
  if (messages.value.some((item) => item.id === payload.id)) {
    return;
  }
  messages.value = messages.value.concat({
    ...payload,
    mine: false
  });
  markChatRead(sessionId.value).then(() => refreshMessageBadge());
  nextTick(() => scrollToBottom());
}

async function handleSend() {
  if (sendBlocked.value) {
    toast.error(sendBlocked.value);
    return;
  }
  const text = content.value.trim();
  if (!text || sending.value) {
    return;
  }
  sending.value = true;
  try {
    const message = await sendChatMessage(sessionId.value, text, "TEXT");
    messages.value = messages.value.concat(message);
    content.value = "";
    await nextTick();
    scrollToBottom();
  } catch (error) {
    toast.error((error as Error).message || "发送失败");
  } finally {
    sending.value = false;
  }
}

function handleSendImage() {
  if (sendBlocked.value) {
    toast.error(sendBlocked.value);
    return;
  }
  if (sending.value) {
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
      sending.value = true;
      try {
        const uploaded = await uploadFile(filePath, "CHAT", { onProgress });
        const message = await sendChatMessage(sessionId.value, uploaded.objectKey, "IMAGE");
        messages.value = messages.value.concat(message);
        await nextTick();
        scrollToBottom();
      } catch (error) {
        toast.error((error as Error).message || "图片发送失败");
      } finally {
        sending.value = false;
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

function handleReportUser() {
  const peerId = session.value?.peerUserId;
  if (!goReport("USER", peerId, session.value?.peerNickname)) {
    return;
  }
}

function handleReportLastMessage() {
  const item = [...messages.value].reverse().find((msg) => !msg.mine);
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
    title: "拉黑",
    content: `拉黑后将无法与 ${session.value?.peerNickname || "对方"} 私聊，确定继续？`,
    success: async (res) => {
      if (!res.confirm) {
        return;
      }
      try {
        await blockUser(peerId);
        toast.success("已加入黑名单");
        setTimeout(() => uni.navigateBack(), 400);
      } catch (error) {
        toast.error((error as Error).message || "拉黑失败");
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

useLiveUpdates((event) => {
  if (!event) {
    loadSession();
    loadMessages();
    return;
  }
  if (event.type === "MESSAGE") {
    applyIncoming(event.data as ChatMessageItem | undefined);
    return;
  }
  if (event.type === "NOTICE" && (event.noticeType === "PEER_BANNED" || event.noticeType === "PEER_UNBANNED")) {
    if (!event.bizId || event.bizId === sessionId.value) {
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
            <image
              v-if="item.mine ? myAvatar : peerAvatar"
              class="avatar-img"
              :src="item.mine ? myAvatar : peerAvatar"
              mode="aspectFill"
            />
            <text v-else>{{ ((item.mine ? userStore.state.user?.nickname : session?.peerNickname) || "同").slice(0, 1) }}</text>
          </view>
          <view class="bubble" :class="{ image: item.msgType === 'IMAGE' }">
            <image
              v-if="item.msgType === 'IMAGE'"
              class="photo"
              :src="resolveMediaUrl(item.url || item.content)"
              mode="widthFix"
              @click="previewImage(item.url, item.content)"
            />
            <view v-else class="text">{{ item.content }}</view>
          </view>
        </view>
      </view>
    </view>
    <view class="actions">
      <text @click="handleReportUser">举报对方</text>
      <text @click="handleReportLastMessage">举报消息</text>
      <text @click="handleBlockPeer">拉黑</text>
    </view>
    <view v-if="sendBlocked" class="mute-tip" :class="{ banned: peerBanned }">{{ sendBlocked }}</view>
    <view v-else class="composer">
      <wd-button size="small" plain :disabled="sending" @click="handleSendImage">{{ uploading ? uploadLabel : "图片" }}</wd-button>
      <input v-model="content" class="input" confirm-type="send" placeholder="输入消息" @confirm="handleSend" />
      <wd-button size="small" type="primary" :loading="sending" @click="handleSend">发送</wd-button>
    </view>
    <wd-toast />
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: #f5f6f8;
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
  color: #f53f3f;
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
  border-radius: 6px;
  background: #dbe7ff;
  color: #3d6fe8;
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
  display: block;
}
.bubble {
  max-width: 68%;
  background: #fff;
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
  color: #1d2129;
  font-size: 15px;
  line-height: 1.5;
  word-break: break-word;
}
.row.mine .text {
  color: #1e3a5c;
}
.actions {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 56px;
  display: flex;
  justify-content: space-around;
  padding: 8px 12px;
  background: #fff;
  border-top: 1px solid #f2f3f5;
  color: #4d80f0;
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
  background: #fff;
  border-top: 1px solid #f2f3f5;
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
  background: #f5f6f8;
  border-radius: 18px;
  padding: 0 12px;
  font-size: 14px;
}
</style>
