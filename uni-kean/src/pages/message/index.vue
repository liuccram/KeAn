<script setup lang="ts">
import { listChats, unreadChatCount, type ChatSessionItem } from "@/api/chat";
import {
  listNotifications,
  markAllNotificationsRead,
  markNotificationRead,
  unreadNotificationCount,
  type NotificationItem
} from "@/api/notification";
import FallbackImage from "@/components/FallbackImage.vue";
import ListState from "@/components/ListState.vue";
import PageBackdrop from "@/components/PageBackdrop.vue";
import { usePageWallpaper } from "@/composables/usePageWallpaper";
import { useUserStore } from "@/store/user";
import { parseDateTime } from "@/utils/format";
import { refreshMessageBadge } from "@/utils/messageBadge";
import { noticeFocus, taskDetailUrl } from "@/utils/taskAction";
import { resolveMediaUrl } from "@/utils/request";
import { useLiveUpdates } from "@/composables/useLiveUpdates";
import { onPullDownRefresh, onReachBottom, onShow } from "@dcloudio/uni-app";
import { useToast } from "wot-design-uni";
import { computed, ref } from "vue";

type TabKey = "system" | "task" | "chat";
type TaskKind = "apply" | "fulfill" | "review" | "change";

const toast = useToast();
const userStore = useUserStore();
const { wallpaperOn, wallpaperImage } = usePageWallpaper();
const tab = ref<TabKey>("task");
const taskFilter = ref<"all" | TaskKind>("all");
const pickedBusy = ref(false);
const list = ref<NotificationItem[]>([]);
const chats = ref<ChatSessionItem[]>([]);
const loading = ref(false);
const chatLoading = ref(false);
const error = ref("");
const finished = ref(false);
const page = ref(1);
const dots = ref({ system: 0, task: 0, chat: 0 });
const noticeScope = computed(() => (tab.value === "system" ? "SYSTEM" : "TASK") as "SYSTEM" | "TASK");

const TASK_KINDS: { key: TaskKind; label: string; hint: string }[] = [
  { key: "apply", label: "申请", hint: "谁来申请、接没接上" },
  { key: "fulfill", label: "履约", hint: "上课、拍照、确认完成" },
  { key: "review", label: "评价", hint: "完成后对对方进行评价" },
  { key: "change", label: "变动", hint: "取消、过期、信息变更" }
];

function taskKind(item: NotificationItem): TaskKind {
  const title = item.title || "";
  if (item.bizType === "REVIEW" || title.includes("已完成") || title.includes("自动完成")) {
    return "review";
  }
  if (item.type === "APPLICATION" || title.includes("申请")) {
    return "apply";
  }
  if (title.includes("取消") || title.includes("过期") || title.includes("已更新")) {
    return "change";
  }
  return "fulfill";
}

const taskKindStats = computed(() => {
  return TASK_KINDS.map((kind) => ({
    ...kind,
    count: list.value.filter((item) => taskKind(item) === kind.key).length,
    unread: list.value.filter((item) => taskKind(item) === kind.key && item.readFlag !== 1).length
  }));
});

const groupedTaskNotices = computed(() => {
  return taskKindStats.value
    .map((kind) => ({
      ...kind,
      items: list.value.filter((item) => taskKind(item) === kind.key)
    }))
    .filter((section) => section.items.length);
});

const visibleNotices = computed(() => {
  if (tab.value !== "task" || taskFilter.value === "all") {
    return list.value;
  }
  return list.value.filter((item) => taskKind(item) === taskFilter.value);
});

// 通知 Tab（系统 / 申请与履约）当前是否有内容可显示，交给 ListState 决定是加载中、失败还是空态
const noticeEmpty = computed(() =>
  tab.value === "system" ? list.value.length === 0 : visibleNotices.value.length === 0
);

const noticeEmptyText = computed(() => {
  if (tab.value === "system") {
    return "暂无系统通知";
  }
  return taskFilter.value === "all" ? "暂无申请或履约消息" : "暂无该类消息";
});

// 当前 Tab 的加载状态：通知与私信是两次独立请求，不能共用一个 loading，
// 否则切换 Tab 时正在飞行的那次请求会把另一个 Tab 的加载挡住，最后显示成空态
const listLoading = computed(() => (tab.value === "chat" ? chatLoading.value : loading.value));

function splitNotice(content?: string | null) {
  const text = (content || "").replace(/\r\n/g, "\n").trim();
  if (!text) {
    return { lead: "", highlights: [] as string[] };
  }
  if (text.includes("\n")) {
    const parts = text.split("\n").map((line) => line.trim()).filter(Boolean);
    return { lead: parts[0] || "", highlights: parts.slice(1) };
  }
  return { lead: text, highlights: [] as string[] };
}

function isHandleNotice(item: NotificationItem) {
  return item.bizType === "REPORT";
}

function isAlertTitle(item: NotificationItem) {
  const title = item.title || "";
  return title === "有人申请了你的代课"
    || title === "申请已被接受"
    || title.includes("取消代课");
}

function goLogin() {
  uni.navigateTo({ url: "/pages/auth/login" });
}

async function loadDots() {
  if (!userStore.isLoggedIn.value) {
    dots.value = { system: 0, task: 0, chat: 0 };
    return;
  }
  try {
    const [system, task, chat] = await Promise.all([
      unreadNotificationCount("SYSTEM"),
      unreadNotificationCount("TASK"),
      unreadChatCount()
    ]);
    dots.value = {
      system: Number(system || 0),
      task: Number(task || 0),
      chat: Number(chat || 0)
    };
  } catch {
    // ignore
  }
}

async function loadNotices(reset = false) {
  if (!userStore.isLoggedIn.value) {
    list.value = [];
    return;
  }
  if (loading.value) {
    return;
  }
  if (reset) {
    page.value = 1;
    finished.value = false;
  }
  // 本次请求前列表是否为空：为空说明加载失败后没有内容可显示，交给 ListState 展示原因和重试
  const first = list.value.length === 0;
  loading.value = true;
  error.value = "";
  try {
    const data = await listNotifications(page.value, 20, noticeScope.value);
    const rows = data.list || [];
    list.value = reset ? rows : list.value.concat(rows);
    finished.value = list.value.length >= data.total;
  } catch (err) {
    const message = (err as Error).message || "加载失败";
    error.value = message;
    // 已经有内容时只用轻提示：这时若让失败态顶掉列表，比不提示更糟
    if (!first) {
      toast.error(message);
    }
  } finally {
    loading.value = false;
    uni.stopPullDownRefresh();
  }
}

async function loadChats() {
  if (!userStore.isLoggedIn.value) {
    chats.value = [];
    return;
  }
  const first = chats.value.length === 0;
  chatLoading.value = true;
  error.value = "";
  try {
    chats.value = await listChats();
  } catch (err) {
    const message = (err as Error).message || "加载失败";
    error.value = message;
    // 已经有私信列表时只用轻提示，避免失败态把已有内容顶掉
    if (!first) {
      toast.error(message);
    }
  } finally {
    chatLoading.value = false;
    uni.stopPullDownRefresh();
  }
}

async function load(reset = false) {
  if (tab.value === "chat") {
    await loadChats();
  } else {
    await loadNotices(reset);
  }
  await loadDots();
  await refreshMessageBadge();
}

function switchTab(next: TabKey) {
  if (tab.value === next) {
    return;
  }
  tab.value = next;
  if (next !== "task") {
    taskFilter.value = "all";
  }
  load(true);
}

function switchTaskFilter(next: "all" | TaskKind) {
  taskFilter.value = next;
}

function pickBusyTab() {
  const { system, task, chat } = dots.value;
  const current = tab.value === "system" ? system : tab.value === "task" ? task : chat;
  if (current > 0) {
    return;
  }
  if (chat > 0 && chat >= task && chat >= system) {
    tab.value = "chat";
    return;
  }
  if (task > 0) {
    tab.value = "task";
    return;
  }
  if (system > 0) {
    tab.value = "system";
    return;
  }
  if (!pickedBusy.value) {
    tab.value = "task";
  }
  pickedBusy.value = true;
}

async function openItem(item: NotificationItem) {
  if (item.readFlag !== 1) {
    try {
      await markNotificationRead(item.id);
      item.readFlag = 1;
      await loadDots();
      await refreshMessageBadge();
    } catch {
      // 仍允许进入详情
    }
  }
  if (!item.bizId || item.bizType === "REPORT") {
    return;
  }
  if (item.bizType !== "TASK" && item.bizType !== "REVIEW") {
    return;
  }
  const focus = noticeFocus(item);
  uni.navigateTo({ url: taskDetailUrl(item.bizId, focus) });
}

function openChat(item: ChatSessionItem) {
  uni.navigateTo({ url: `/pages/message/chat?id=${item.id}` });
}

function openPeer(item: ChatSessionItem) {
  if (!item.peerUserId) {
    return;
  }
  uni.navigateTo({ url: `/pages/mine/user?id=${item.peerUserId}` });
}

function goPeers() {
  uni.navigateTo({ url: "/pages/message/peers" });
}

async function handleReadAll() {
  try {
    await markAllNotificationsRead(noticeScope.value);
    list.value = list.value.map((item) => ({ ...item, readFlag: 1 }));
    await loadDots();
    await refreshMessageBadge();
  } catch (error) {
    toast.error((error as Error).message || "操作失败");
  }
}

onShow(async () => {
  await loadDots();
  pickBusyTab();
  await load(true);
});

useLiveUpdates((event) => {
  if (!event || event.type === "NOTICE" || event.type === "MESSAGE") {
    load(true);
  }
});

onPullDownRefresh(() => {
  load(true);
});

onReachBottom(() => {
  if (tab.value === "chat" || finished.value || loading.value) {
    return;
  }
  page.value += 1;
  loadNotices(false);
});
</script>

<template>
  <view class="page" :class="{ skinned: wallpaperOn }">
    <PageBackdrop :src="wallpaperImage" />
    <view v-if="!userStore.isLoggedIn" class="guest">
      <wd-status-tip image="content" tip="登录后查看系统通知、申请履约与私信" />
      <wd-button type="primary" @click="goLogin">去登录</wd-button>
    </view>
    <template v-else>
      <view class="tabs-bar">
        <view class="tabs">
          <view class="tab" :class="{ on: tab === 'system' }" @click="switchTab('system')">
            <text>系统</text>
            <view v-if="dots.system > 0" class="dot" />
          </view>
          <view class="tab" :class="{ on: tab === 'task' }" @click="switchTab('task')">
            <text>申请与履约</text>
            <view v-if="dots.task > 0" class="dot" />
          </view>
          <view class="tab" :class="{ on: tab === 'chat' }" @click="switchTab('chat')">
            <text>私信</text>
            <view v-if="dots.chat > 0" class="dot" />
          </view>
        </view>
      </view>

      <template v-if="tab !== 'chat'">
        <view v-if="tab === 'task'" class="kind-bar">
          <view class="kind" :class="{ on: taskFilter === 'all' }" @click="switchTaskFilter('all')">
            全部
            <view v-if="dots.task > 0" class="kind-dot" />
          </view>
          <view
            v-for="kind in taskKindStats"
            :key="kind.key"
            class="kind"
            :class="{ on: taskFilter === kind.key }"
            @click="switchTaskFilter(kind.key)"
          >
            {{ kind.label }}
            <view v-if="kind.unread > 0" class="kind-dot" />
          </view>
        </view>
        <view v-if="(tab === 'system' ? list.length : visibleNotices.length) || (tab === 'task' && taskFilter === 'all' && groupedTaskNotices.length)" class="toolbar">
          <text class="hint">{{
            tab === "system"
              ? "账号与平台通知"
              : taskFilter === "all"
                ? "按申请、履约、评价、变动分开"
                : TASK_KINDS.find((item) => item.key === taskFilter)?.hint || "点击进入对应代课"
          }}</text>
          <text class="link" @click="handleReadAll">全部已读</text>
        </view>
        <ListState
          :loading="listLoading"
          :error="error"
          :empty="noticeEmpty"
          :empty-text="noticeEmptyText"
          @retry="load(true)"
        >
          <template v-if="tab === 'task' && taskFilter === 'all' && groupedTaskNotices.length">
            <view v-for="section in groupedTaskNotices" :key="section.key" class="section">
              <view class="section-head">
                <text class="section-label">{{ section.label }}</text>
                <text class="section-hint">{{ section.hint }}</text>
              </view>
              <view
                v-for="item in section.items"
                :key="item.id"
                class="row"
                :class="[section.key, { unread: item.readFlag !== 1 }]"
                @click="openItem(item)"
              >
                <view class="mark" />
                <view class="main">
                  <view class="top">
                    <text class="title" :class="{ alert: isAlertTitle(item) }">{{ item.title }}</text>
                    <text class="time">{{ parseDateTime(item.createdAt) }}</text>
                  </view>
                  <view class="content">{{ item.content }}</view>
                </view>
              </view>
            </view>
            <view class="end">{{ finished ? "没有更多了" : "上拉加载更多" }}</view>
          </template>
          <view v-else class="list">
            <view
              v-for="item in tab === 'system' ? list : visibleNotices"
              :key="item.id"
              class="row"
              :class="[tab === 'task' ? taskKind(item) : '', { unread: item.readFlag !== 1 }]"
              @click="openItem(item)"
            >
              <view class="mark" />
              <view class="main">
                <view class="top">
                  <text class="title" :class="{ alert: isAlertTitle(item) }">{{ item.title }}</text>
                  <text class="time">{{ parseDateTime(item.createdAt) }}</text>
                </view>
                <view class="content" :class="{ handle: isHandleNotice(item) }">
                  <template v-if="isHandleNotice(item)">
                    <view class="lead">{{ splitNotice(item.content).lead }}</view>
                    <view
                      v-for="(line, index) in splitNotice(item.content).highlights"
                      :key="index"
                      class="result-line"
                    >{{ line }}</view>
                  </template>
                  <template v-else>{{ item.content }}</template>
                </view>
              </view>
            </view>
            <view class="end">{{ finished ? "没有更多了" : "上拉加载更多" }}</view>
          </view>
        </ListState>
      </template>

      <template v-else>
        <view class="toolbar">
          <text class="hint">本校同学可发起私信</text>
          <text class="link" @click="goPeers">发起私信</text>
        </view>
        <ListState
          :loading="listLoading"
          :error="error"
          :empty="chats.length === 0"
          empty-text="暂无私信"
          @retry="load(true)"
        >
          <view class="chat-list">
            <view
              v-for="item in chats"
              :key="item.id"
              class="chat-row"
              @click="openChat(item)"
            >
              <FallbackImage v-if="item.peerAvatarUrl" class="avatar img" :src="resolveMediaUrl(item.peerAvatarUrl)" mode="aspectFill" @click.stop="openPeer(item)" />
              <view v-else class="avatar" @click.stop="openPeer(item)">{{ (item.peerNickname || "同").slice(0, 1) }}</view>
              <view class="chat-main">
                <view class="top">
                  <view class="name-row">
                    <text class="title">{{ item.peerNickname || "同学" }}</text>
                    <text v-if="item.peerBanned" class="muted-tag banned">已封禁</text>
                    <text v-else-if="item.peerMuted" class="muted-tag">已禁言</text>
                  </view>
                  <text class="time">{{ parseDateTime(item.lastMessageAt) }}</text>
                </view>
                <view class="preview-row">
                  <text class="preview">{{ item.lastContent || "暂无消息" }}</text>
                  <view v-if="item.unreadCount > 0" class="badge">{{ item.unreadCount > 99 ? "99+" : item.unreadCount }}</view>
                </view>
              </view>
            </view>
          </view>
        </ListState>
      </template>
    </template>
    <wd-toast />
  </view>
</template>

<style scoped>
.page {
  position: relative;
  min-height: 100vh;
  background: #f5f6f8;
  overflow-x: hidden;
  max-width: 100%;
}
.page.skinned {
  background: transparent;
}
.guest {
  padding-top: 80px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
}
.tabs-bar {
  padding: 10px 12px 0;
  background: #f5f6f8;
  position: relative;
  z-index: 20;
}
.page.skinned .tabs-bar {
  background: #1c1c1e;
}
.tabs {
  display: flex;
  gap: 8px;
  margin: 0;
  padding: 4px;
  background: #eceff3;
  border-radius: 10px;
}
.tab {
  flex: 1;
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  height: 36px;
  color: #86909c;
  font-size: 13px;
  border-radius: 8px;
}
.tab.on {
  color: #3d6fe8;
  font-weight: 600;
  background: #fff;
}
.page.skinned .tabs {
  background: #111113;
  border: 1px solid #4d80f0;
}
.page.skinned .tab {
  color: #4d80f0;
}
.page.skinned .tab.on {
  color: #4d80f0;
  background: rgba(77, 128, 240, 0.2);
}
.tab .dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #f53f3f;
}
.kind-bar {
  display: flex;
  gap: 8px;
  padding: 10px 12px 0;
  width: 100%;
  box-sizing: border-box;
}
.kind {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  height: 30px;
  padding: 0 4px;
  border-radius: 15px;
  background: #fff;
  color: #4e5969;
  font-size: 13px;
  min-width: 0;
}
.kind.on {
  background: #3d6fe8;
  color: #fff;
  font-weight: 600;
}
.kind-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #f53f3f;
}
.kind.on .kind-dot {
  background: #fff;
}
.page.skinned .kind {
  background: rgba(28, 28, 30, 0.86);
  color: #dce6ff;
}
.page.skinned .kind.on {
  background: #4d80f0;
  color: #fff;
}
.section {
  margin-top: 8px;
  background: #fff;
}
.section-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  padding: 12px 16px 8px;
  background: #f8fafc;
  border-bottom: 1px solid #f2f3f5;
  min-width: 0;
}
.section-label {
  color: #1d2129;
  font-size: 13px;
  font-weight: 700;
  flex-shrink: 0;
}
.section-hint {
  color: #86909c;
  font-size: 11px;
  min-width: 0;
  text-align: right;
}
.page.skinned .section,
.page.skinned .section-head {
  background: rgba(28, 28, 30, 0.72);
}
.page.skinned .section-label {
  color: #fff;
}
.page.skinned .section-hint {
  color: #9aa4b2;
}
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 16px 0;
}
.hint {
  color: #86909c;
  font-size: 12px;
}
.link {
  color: #3d6fe8;
  font-size: 13px;
}
.list {
  padding: 8px 0 24px;
}
.row {
  display: flex;
  gap: 10px;
  padding: 12px 16px;
  background: #fff;
  border-bottom: 1px solid #f2f3f5;
  border-left: 3px solid transparent;
}
.row.apply {
  border-left-color: #3d6fe8;
}
.row.fulfill {
  border-left-color: #14b8a6;
}
.row.review {
  border-left-color: #f59e0b;
}
.row.change {
  border-left-color: #f97316;
}
.row.unread .title {
  font-weight: 700;
}
.mark {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  margin-top: 8px;
  background: transparent;
  flex-shrink: 0;
}
.row.unread .mark {
  background: #f53f3f;
}
.main {
  flex: 1;
  min-width: 0;
}
.top {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
}
.title {
  color: #1d2129;
  font-size: 15px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.title.alert {
  color: #f53f3f;
  font-weight: 700;
}
.time {
  flex-shrink: 0;
  color: #c9cdd4;
  font-size: 11px;
}
.content {
  margin-top: 4px;
  color: #86909c;
  font-size: 13px;
  line-height: 1.45;
  overflow: hidden;
  overflow-wrap: anywhere;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}
.content.handle {
  display: block;
  overflow: visible;
  text-overflow: unset;
  -webkit-line-clamp: unset;
}
.lead {
  display: block;
}
.result-line {
  display: block;
  margin-top: 6px;
  color: #f53f3f;
  font-weight: 700;
  font-size: 14px;
  line-height: 1.5;
}
.chat-list {
  margin-top: 8px;
  background: #fff;
}
.chat-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  border-bottom: 1px solid #f2f3f5;
}
.avatar {
  width: 44px;
  height: 44px;
  border-radius: 6px;
  background: #dbe7ff;
  color: #3d6fe8;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 600;
  flex-shrink: 0;
  overflow: hidden;
}
.avatar.img {
  display: block;
  object-fit: cover;
}
.chat-main {
  flex: 1;
  min-width: 0;
}
.name-row {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.muted-tag {
  flex-shrink: 0;
  font-size: 11px;
  color: #d25f00;
  background: #fff7e8;
  border-radius: 4px;
  padding: 1px 6px;
}
.muted-tag.banned {
  color: #f53f3f;
  background: #fff1f0;
}
.preview-row {
  margin-top: 4px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.preview {
  flex: 1;
  color: #86909c;
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.badge {
  min-width: 16px;
  height: 16px;
  padding: 0 5px;
  border-radius: 8px;
  background: #f53f3f;
  color: #fff;
  font-size: 10px;
  line-height: 16px;
  text-align: center;
}
.end {
  text-align: center;
  color: #86909c;
  font-size: 12px;
  padding: 12px 0;
}
</style>
