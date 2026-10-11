<script setup lang="ts">
import { addFavorite, removeFavorite } from "@/api/favorite";
import { listActiveAnnouncements, type ActiveAnnouncement } from "@/api/announcement";
import { listTasks, TASK_STATUS_TEXT, type TaskItem } from "@/api/task";
import CampusHighlight from "@/components/CampusHighlight.vue";
import ListState from "@/components/ListState.vue";
import OngoingTasks from "@/components/OngoingTasks.vue";
import PageBackdrop from "@/components/PageBackdrop.vue";
import { useLiveUpdates } from "@/composables/useLiveUpdates";
import { useNowTick } from "@/composables/useNowTick";
import { useOngoingTasks } from "@/composables/useOngoingTasks";
import { usePageWallpaper } from "@/composables/usePageWallpaper";
import { useUserStore } from "@/store/user";
import { t } from "@/utils/i18n";
import { formatDate, formatReward } from "@/utils/format";
import { refreshMessageBadge } from "@/utils/messageBadge";
import { classCountdown, todayText } from "@/utils/taskAction";
import { onPullDownRefresh, onReachBottom, onShow } from "@dcloudio/uni-app";
import { useToast } from "wot-design-uni";
import { computed, reactive, ref } from "vue";

const toast = useToast();
const userStore = useUserStore();
const { wallpaperOn, wallpaperImage, prefs } = usePageWallpaper();
const { items: ongoing, error: ongoingError, load: loadOngoing } = useOngoingTasks();
const nowTick = useNowTick();
const loading = ref(false);
const error = ref("");
const finished = ref(false);
const list = ref<TaskItem[]>([]);
const keyword = ref("");
const filter = reactive({
  status: "OPEN",
  timeSlot: "ALL"
});
const selectedDate = ref(todayText());
const datePickerValue = ref<number | string>(Date.now());
const page = ref(1);
const total = ref(0);
const announcement = ref<ActiveAnnouncement | null>(null);
const DISMISS_KEY = "kean_dismissed_announcements";

const statusOptions = computed(() => [
  { label: t("allStatus", prefs.lang), value: "ALL" },
  { label: t("openStatus", prefs.lang), value: "OPEN" },
  { label: t("mineStatus", prefs.lang), value: "MINE" }
]);
const dateLabel = computed(() => t("classDate", prefs.lang));
const datePlaceholder = computed(() => t("datePlaceholder", prefs.lang));
const todayLabel = computed(() => t("todayOnly", prefs.lang));
const allDatesLabel = computed(() => t("allDates", prefs.lang));
// 空态文案沿用改版前的两种说法：有进行中任务时只是「今日暂无新的」，否则是真的没有任务
const emptyText = computed(() => (ongoing.value.length ? "今日暂无新的可申请代课" : "暂无代课任务"));
const isToday = computed(() => selectedDate.value === todayText(nowTick.value));
const timeOptions = computed(() => [
  { label: t("allTimes", prefs.lang), value: "ALL" },
  { label: "6-8点", value: "6-8" },
  { label: "8-10点", value: "8-10" },
  { label: "10-12点", value: "10-12" },
  { label: "13-15点", value: "13-15" },
  { label: "15-17点", value: "15-17" },
  { label: "17-20点", value: "17-20" },
  { label: "20-22点", value: "20-22" }
]);

function dismissedIds(): number[] {
  try {
    const raw = uni.getStorageSync(DISMISS_KEY);
    return Array.isArray(raw) ? raw.map(Number).filter((id) => !Number.isNaN(id)) : [];
  } catch {
    return [];
  }
}

async function loadAnnouncement() {
  try {
    const items = await listActiveAnnouncements();
    const seen = new Set(dismissedIds());
    announcement.value = (items || []).find((item) => !seen.has(item.id)) || null;
  } catch {
    announcement.value = null;
  }
}

function dismissAnnouncement() {
  if (!announcement.value) {
    return;
  }
  uni.setStorageSync(DISMISS_KEY, [...new Set([...dismissedIds(), announcement.value.id])]);
  announcement.value = null;
}

function goDetail(id: number) {
  uni.navigateTo({ url: `/pages/task/detail?id=${id}` });
}

function goPublish() {
  uni.switchTab({ url: "/pages/publish/index" });
}

async function toggleFavorite(item: TaskItem) {
  if (!userStore.isLoggedIn.value) {
    uni.navigateTo({ url: "/pages/auth/login" });
    return;
  }
  if (item.mine) {
    toast.error("不能收藏自己发布的代课");
    return;
  }
  try {
    if (item.favorited) {
      await removeFavorite(item.id);
      item.favorited = false;
    } else {
      await addFavorite(item.id);
      item.favorited = true;
    }
  } catch (error) {
    toast.error((error as Error).message || "收藏失败");
  }
}

/**
 * 自动刷新（回到页面 / 轮询兜底）的作废序号：静默刷新还在飞的时候用户发起了新请求，
 * 序号就会被 +1，随后到达的旧结果整包丢弃 —— 否则旧筛选的数据会盖掉用户刚切的新筛选结果。
 */
let autoSeq = 0;

/**
 * @param reset 按首次加载处理：回到第一页并整体替换列表
 * @param silent 静默刷新（回到页面 / 轮询兜底用）：不置 loading、不弹轻提示，只更新数据
 */
async function loadList(reset = false, silent = false) {
  if (loading.value) {
    return;
  }
  const seq = ++autoSeq;
  const first = list.value.length === 0;
  if (reset) {
    page.value = 1;
    finished.value = false;
  }
  // 静默刷新刻意不置 loading：ListState 只在「没有内容」时接管渲染，但空列表时
  // 置了 loading 就会闪一次骨架 —— 回到页面和 30 秒轮询都不该闪。
  if (!silent) {
    loading.value = true;
    error.value = "";
  }
  try {
    const data = await listTasks({
      keyword: keyword.value.trim() || undefined,
      schoolId: Number(userStore.state.user?.schoolId) || undefined,
      status: filter.status,
      taskDate: selectedDate.value || undefined,
      timeSlot: filter.timeSlot && filter.timeSlot !== "ALL" ? filter.timeSlot : undefined,
      page: page.value,
      size: 10
    });
    if (silent && seq !== autoSeq) {
      return;
    }
    // 静默刷新只在拿到结果后清错误：先把「上次加载失败」闪成「暂无数据」再变回来更糟
    error.value = "";
    total.value = data.total;
    list.value = reset ? data.list : list.value.concat(data.list);
    finished.value = list.value.length >= data.total;
  } catch (err) {
    if (silent && seq !== autoSeq) {
      return;
    }
    const message = (err as Error).message || "加载失败";
    error.value = message;
    // 已经有内容时只用轻提示：这时若让失败态顶掉列表，比不提示更糟。
    // 没有内容时交给 ListState 显示原因和「重新加载」。
    // 静默刷新不弹提示：这是后台自动重试，断网时每 30 秒弹一次会盖满屏幕。
    if (!first && !silent) {
      toast.error(message);
    }
  } finally {
    // 静默刷新从未置过 loading、也没拉过下拉指示器，所以也不负责复位它们
    if (!silent) {
      loading.value = false;
      uni.stopPullDownRefresh();
    }
  }
}

// 重新加载：按首次加载处理，重置分页后重拉第一页
function retry() {
  loadList(true);
}

function onSearch() {
  loadList(true);
}

function onFilterChange() {
  if (filter.status === "MINE" && !userStore.isLoggedIn.value) {
    uni.navigateTo({ url: "/pages/auth/login" });
    return;
  }
  loadList(true);
}

function onDateConfirm(event: { value?: string | number }) {
  const value = event?.value ?? datePickerValue.value;
  if (value === "" || value === undefined || value === null) {
    selectedDate.value = "";
  } else {
    selectedDate.value = formatDate(Number(value));
  }
  loadList(true);
}

function onDateClear() {
  selectedDate.value = "";
  datePickerValue.value = "";
  loadList(true);
}

function useToday() {
  const now = Date.now();
  datePickerValue.value = now;
  selectedDate.value = formatDate(now);
  loadList(true);
}

function countdownOf(item: TaskItem) {
  return classCountdown(item, nowTick.value);
}

onShow(() => {
  // 静默刷新：任务列表 + 进行中两块数据都刷，但不显示加载骨架、不把已有列表顶掉
  loadList(true, true);
  loadOngoing();
  refreshMessageBadge();
  loadAnnouncement();
});

// pollMs = 30000：首页是列表页，30 秒足够「新任务最多滞后半分钟出现」，
// 再快会把首页请求量放大数倍而收益很小（用户主动下拉/切页本来就会立即刷新）。
useLiveUpdates((event) => {
  if (!event) {
    // 轮询兜底（不依赖 NOTICE、也不依赖自研 WS）：两块数据一起刷
    // 只在「没有翻页」时重拉列表 —— 用户已上拉加载过更多页时把列表截回第一页会顶掉他的位置
    if (page.value === 1) {
      loadList(true, true);
    }
    loadOngoing();
    return;
  }
  if (event.type === "NOTICE" && (event.bizType === "TASK" || event.noticeType === "APPLICATION" || event.noticeType === "TASK")) {
    loadList(true);
    loadOngoing();
  }
}, 30000);

onPullDownRefresh(() => {
  loadList(true);
  loadOngoing();
});

onReachBottom(() => {
  if (finished.value || loading.value) {
    return;
  }
  page.value += 1;
  loadList(false);
});
</script>

<template>
  <view class="page" :class="{ skinned: wallpaperOn }">
    <PageBackdrop :src="wallpaperImage" />
    <view v-if="announcement" class="announce-mask">
      <view class="announce-window">
        <view class="announce-kicker">平台公告</view>
        <view class="announce-title">{{ announcement.title }}</view>
        <scroll-view class="announce-body" scroll-y>
          <text class="announce-text">{{ announcement.content }}</text>
        </scroll-view>
        <view class="announce-ok" @click="dismissAnnouncement">知道了</view>
      </view>
    </view>
    <view class="home-intro">
      <view class="intro-copy">
        <text class="intro-kicker">课安 · 校园互助</text>
        <text class="intro-title">让每一节课，都有人接手</text>
        <text class="intro-subtitle">看看同学们正在寻找的代课帮助</text>
      </view>
      <view class="intro-orbit orbit-one" /><view class="intro-orbit orbit-two" />
    </view>
    <view class="search">
      <wd-search v-model="keyword" placeholder="搜索课程 / 教学楼 / 教室" hide-cancel @search="onSearch" @clear="onSearch" />
    </view>
    <view class="home-filters">
      <wd-drop-menu>
        <wd-drop-menu-item v-model="filter.status" :options="statusOptions" @change="onFilterChange" />
        <wd-drop-menu-item v-model="filter.timeSlot" :options="timeOptions" @change="onFilterChange" />
      </wd-drop-menu>
      <wd-datetime-picker
        v-model="datePickerValue"
        type="date"
        :label="dateLabel"
        :placeholder="datePlaceholder"
        clearable
        @confirm="onDateConfirm"
        @clear="onDateClear"
      />
    </view>
    <view class="quick">
      <view class="chip" :class="{ on: isToday }" @click="useToday">{{ todayLabel }}</view>
      <view class="chip" :class="{ on: !selectedDate }" @click="onDateClear">{{ allDatesLabel }}</view>
    </view>
    <OngoingTasks v-if="userStore.isLoggedIn" :items="ongoing" :limit="3" />
    <!-- 仅失败时多一行小字：没有失败时这一行不渲染，观感与原来完全一致 -->
    <view v-if="userStore.isLoggedIn && ongoingError" class="ongoing-error" @click="loadOngoing">
      进行中的代课加载失败，点击重试
    </view>
    <ListState
      :loading="loading"
      :error="error"
      :empty="list.length === 0"
      :empty-text="emptyText"
      @retry="retry"
    >
      <view class="list">
        <view class="list-heading"><view><text class="list-heading__title">校园求助</text><text class="list-heading__sub">找到合适的课程，一起互相帮忙</text></view><text class="list-heading__count">{{ total }} 条</text></view>
        <view v-for="item in list" :key="item.id" class="card" @click="goDetail(item.id)">
          <view class="card-top">
            <view class="course-wrap"><view class="course-mark">课</view><text class="course">{{ item.courseName }}</text></view>
            <view class="card-right">
              <text v-if="!item.mine" class="fav" @click.stop="toggleFavorite(item)">{{ item.favorited ? "★" : "☆" }}</text>
              <text class="status">{{ TASK_STATUS_TEXT[item.status] || item.status }}</text>
            </view>
          </view>
          <view class="task-info-row"><view class="info-icon">◷</view><view class="meta">{{ item.taskDate }} <text class="time-emphasis">{{ item.startTime }}–{{ item.endTime }}</text></view><text v-if="countdownOf(item)" class="count">{{ countdownOf(item) }}</text></view>
          <view class="task-info-row">
            <view class="info-icon pin-icon">⌖</view><view class="meta"><CampusHighlight :name="item.campusName" /><text>{{ item.building }} {{ item.classroom }}</text></view>
          </view>
          <view class="card-bottom">
            <view class="reward-pill"><text class="reward">{{ formatReward(item.reward) }}</text><text class="reward-caption">感谢</text></view>
            <text class="count-app">{{ item.applyCount }} 人已响应 <text class="arrow">›</text></text>
          </view>
        </view>
        <view class="end">{{ finished ? "没有更多了" : "上拉加载更多" }}</view>
      </view>
    </ListState>
    <!-- 发布求助：右下角悬浮按钮（原来在 .home-intro 卡片里，按用户要求移到页面右下角）。
         fixed 的定位与留白见样式里 .publish-fab 的注释。 -->
    <view class="publish-fab" @click="goPublish"><text>＋</text><text>发布求助</text></view>
    <wd-toast />
  </view>
</template>

<style scoped>
.announce-mask {
  position: fixed;
  inset: 0;
  z-index: 200;
  background: rgba(15, 23, 42, 0.45);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
}
.announce-window {
  width: 86%;
  max-width: 320px;
  background: var(--kean-card);
  border-radius: 16px;
  padding: 20px 18px 16px;
  box-shadow: 0 16px 40px rgba(15, 23, 42, 0.18);
}
.announce-kicker {
  font-size: 12px;
  color: #3b82f6;
  font-weight: 600;
}
.announce-title {
  margin-top: 8px;
  font-size: 17px;
  font-weight: 700;
  color: var(--kean-text);
}
.announce-body {
  margin-top: 12px;
  max-height: 240px;
}
.announce-text {
  font-size: 14px;
  line-height: 1.7;
  color: var(--kean-sub);
  white-space: pre-wrap;
}
.announce-ok {
  margin-top: 16px;
  height: 40px;
  border-radius: 10px;
  background: #3b82f6;
  color: var(--kean-card);
  font-size: 15px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
}
.page {
  position: relative;
  min-height: 100vh;
  background: var(--kean-bg);
}
.page.skinned {
  background: transparent;
}
.home-filters {
  position: relative;
  z-index: 20;
  margin: 9px 16px 0;
  overflow: hidden;
  border: 1px solid rgba(224,232,243,.9);
  border-radius: 14px;
  background: var(--kean-card);
}
.page:not(.skinned) .home-filters { box-shadow: 0 4px 14px rgba(35,60,98,.03); }
.page.skinned .home-filters {
  background: rgba(12, 14, 18, 0.88) !important;
}
.page.skinned .home-filters :deep(.wd-drop-menu),
.page.skinned .home-filters :deep(.wd-drop-menu__list),
.page.skinned .home-filters :deep(.wd-drop-menu__item),
.page.skinned .home-filters :deep(.wd-datetime-picker),
.page.skinned .home-filters :deep(.wd-cell),
.page.skinned .home-filters :deep(.wd-cell__wrapper) {
  background: rgba(12, 14, 18, 0.88) !important;
  background-color: rgba(12, 14, 18, 0.88) !important;
}
.page.skinned .home-filters :deep(.wd-drop-menu__item-title),
.page.skinned .home-filters :deep(.wd-drop-menu__item-title-text),
.page.skinned .home-filters :deep(.wd-cell__title),
.page.skinned .home-filters :deep(.wd-cell__value),
.page.skinned .home-filters :deep(.wd-cell__placeholder) {
  color: var(--kean-card) !important;
  text-shadow: none !important;
}
.search {
  margin: 0 16px;
  overflow: hidden;
  border: 1px solid rgba(224,232,243,.92);
  border-radius: 14px;
  background: var(--kean-card);
  box-shadow: 0 4px 14px rgba(35,60,98,.035);
}
.search :deep(.wd-search) { padding: 4px 8px; background: transparent; }
.search :deep(.wd-search__block) { border-radius: 10px; background: rgba(245,248,253,.92); }
.page.skinned .search { border-color: rgba(255,255,255,.12); background: rgba(18,23,34,.78); box-shadow: none; }
.page.skinned .search :deep(.wd-search),.page.skinned .search :deep(.wd-search__block) { background: transparent; }
.home-intro { position: relative; display: flex; align-items: center; justify-content: space-between; gap: 10px; min-height: 126px; margin: 12px 16px 10px; padding: 20px 18px; overflow: hidden; border: 1px solid rgba(179,204,239,.55); border-radius: 20px; background: linear-gradient(125deg,#edf5ff 0%,#f7f9ff 58%,#e7f2ff 100%); }
.intro-copy { position: relative; z-index: 1; min-width: 0; display: flex; flex-direction: column; }
.intro-kicker { color: #6d8fbe; font-size: 10px; font-weight: 700; letter-spacing: 1px; }
.intro-title { margin-top: 8px; color: #203858; font-size: 19px; font-weight: 750; line-height: 1.4; }
.intro-subtitle { margin-top: 5px; color: #8292aa; font-size: 11px; }
.intro-action { position: relative; z-index: 1; flex-shrink: 0; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 3px; width: 64px; height: 64px; border: 1px solid rgba(255,255,255,.9); border-radius: 18px; color: #4d7fbd; background: rgba(255,255,255,.76); box-shadow: 0 6px 16px rgba(71,112,170,.1); font-size: 10px; }
.intro-action text:first-child { font-size: 22px; line-height: 1; }
.intro-orbit { position: absolute; border: 1px solid rgba(127,169,222,.18); border-radius: 50%; pointer-events: none; }.orbit-one { width: 170px; height: 170px; right: -48px; top: -94px; }.orbit-two { width: 120px; height: 120px; right: -23px; top: -68px; }
.page.skinned .home-intro { border-color: rgba(185,203,235,.2); background: linear-gradient(125deg,rgba(17,27,48,.82),rgba(27,38,62,.72)); }.page.skinned .intro-title { color: #f0f5ff; }.page.skinned .intro-kicker,.page.skinned .intro-subtitle { color: #b6c7e1; }.page.skinned .intro-action { border-color: rgba(255,255,255,.18); color: #e5efff; background: rgba(255,255,255,.12); box-shadow: none; }
.quick {
  display: flex;
  gap: 8px;
  padding: 10px 16px 6px;
  position: relative;
  z-index: 0;
}
.chip {
  height: 30px;
  padding: 0 13px;
  border: 1px solid rgba(226,233,243,.92);
  border-radius: 11px;
  background: rgba(255,255,255,.78);
  color: var(--kean-sub);
  font-size: 13px;
  display: flex;
  align-items: center;
}
.chip.on {
  border-color: var(--kean-primary-active);
  background: var(--kean-primary-active);
  color: var(--kean-card);
  font-weight: 600;
}
.page.skinned .chip {
  border-color: rgba(255,255,255,.12);
  background: rgba(28, 28, 30, 0.86);
  color: #dce6ff;
}
.page.skinned .chip.on {
  background: var(--kean-primary);
  color: var(--kean-card);
}
.list {
  padding: 12px 16px 24px;
}
.list-heading { display: flex; align-items: center; justify-content: space-between; padding: 7px 2px 13px; }.list-heading__title { display: block; color: var(--kean-text); font-size: 17px; font-weight: 700; }.list-heading__sub { display: block; margin-top: 4px; color: var(--kean-muted); font-size: 11px; }.list-heading__count { padding: 5px 10px; border-radius: 12px; color: #6d89b1; background: rgba(233,241,252,.86); font-size: 11px; }.page.skinned .list-heading__count { color: #dbe8ff; background: rgba(255,255,255,.13); }
.ongoing-error {
  padding: 0 18px 8px;
  color: #d94b4b;
  font-size: 12px;
}
.card {
  background: var(--kean-card);
  border: 1px solid rgba(225,232,242,.84);
  border-radius: 17px;
  padding: 15px;
  margin-bottom: 11px;
  box-shadow: 0 5px 16px rgba(29,54,91,.035);
}
.page.skinned .card { border-color: rgba(255,255,255,.12); background: rgba(18,23,34,.78); box-shadow: none; }
.card-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.course-wrap { display: flex; align-items: center; gap: 9px; min-width: 0; }.course-mark { flex-shrink: 0; width: 30px; height: 30px; display: flex; align-items: center; justify-content: center; border-radius: 10px; color: #5682c0; background: #edf4ff; font-size: 12px; font-weight: 700; }.page.skinned .course-mark { color: #cfe1ff; background: rgba(124,164,226,.2); }
.course {
  font-size: 16px;
  font-weight: 600;
  color: var(--kean-text);
}
.card-right {
  display: flex;
  align-items: center;
  gap: 8px;
}
.fav {
  color: var(--kean-star);
  font-size: 18px;
}
.status {
  font-size: 12px;
  color: var(--kean-primary);
}
.meta {
  min-width: 0;
  color: var(--kean-sub);
  font-size: 13px;
}
.task-info-row { display: flex; align-items: center; gap: 8px; margin-top: 11px; }.info-icon { flex-shrink: 0; width: 22px; height: 22px; display: flex; align-items: center; justify-content: center; border-radius: 7px; color: #7a9bc7; background: #f0f5fc; font-size: 13px; }.pin-icon { color: #be9569; background: #fbf4ec; }.time-emphasis { margin-left: 5px; color: var(--kean-text); font-weight: 600; }.page.skinned .info-icon { color: #d0e2ff; background: rgba(255,255,255,.1); }
.count {
  margin-top: 6px;
  color: #f77234;
  font-size: 12px;
  font-weight: 600;
}
.card-bottom {
  margin-top: 14px;
  padding-top: 11px;
  border-top: 1px solid rgba(229,234,242,.78);
  display: flex;
  justify-content: space-between;
  align-items: center;
  color: var(--kean-muted);
  font-size: 13px;
}
.page.skinned .card-bottom { border-color: rgba(255,255,255,.12); }
.reward {
  color: #f77234;
  font-weight: 600;
}
.reward-pill { display: inline-flex; align-items: baseline; gap: 5px; }.reward-caption { color: var(--kean-muted); font-size: 10px; }.count-app { color: var(--kean-muted); font-size: 11px; }.arrow { padding-left: 3px; font-size: 17px; vertical-align: -1px; }
.end {
  text-align: center;
  color: var(--kean-muted);
  font-size: 12px;
  padding: 8px 0 16px;
}
/* ===========================================================================
 * 发布求助 · 右下角悬浮按钮（原位置：.home-intro 卡片右侧那个 64×64 的 .intro-action）
 * ---------------------------------------------------------------------------
 * bottom = tabBar 高度 + 安全区 + 16px：
 *   · --tab-bar-height 由 uni-app H5 在启动时写到 documentElement 上
 *     （node_modules/@dcloudio/uni-h5/dist/uni-h5.es.js 的 useShowTabBar / updateCssVar），
 *     取值 = pages.json 规范出来的 tabBar.height = uni-shared 的 TABBAR_HEIGHT = 50px
 *     （uni-cli-shared/dist/json/pages.js 的 DEFAULT_TAB_BAR，本仓库 pages.json 未覆盖 height）。
 *     框架自己也是拿它给 tabBar 留位的（framework/tabBar.css 的 .uni-app--showtabbar
 *     uni-page-wrapper），所以它是最准的那个值。
 *   · env(safe-area-inset-bottom) 与 tabBar 自身的 padding-bottom 同值
 *     （framework/tabBar.css 的 .uni-tabbar-bottom .uni-tabbar）。
 *   → 按钮底边恒定压在 tabBar 上方 16px：既不会被 tabBar 盖住（tabBar 是 z-index 998），
 *     也不会压住 tabBar。
 * ⚠️ 这个固定元素的祖先链必须是干净的（.page → uni-page-body → uni-page-wrapper →
 *    uni-page → uni-app → #app）：全项目 CSS 里会建包含块的声明只有 8 条，没有一条落在
 *    .page 或它的祖先上（见 design-kit.css 的 .kean-mine 系列、auth-theme.css 的
 *    .auth-sky）。同页的 .announce-mask 以及 pages/message/chat.vue 的 .panel 都是
 *    在这条链上用 position: fixed，可作旁证。
 * ⚠️ z-index 90：低于 .announce-mask(200)、wd-popup / wd-toast(500±)，高于页面内容与
 *    .home-filters(20) —— 公告弹层仍然能盖住它并拦住点击。
 * =========================================================================== */
.publish-fab {
  position: fixed;
  right: 16px;
  /* 老内核不认 env()：解析期会丢掉下一条、保留这一条，按钮仍落在 tabBar 上方 16px */
  bottom: calc(var(--tab-bar-height, 50px) + 16px);
  bottom: calc(var(--tab-bar-height, 50px) + env(safe-area-inset-bottom) + 16px);
  z-index: 90;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  height: 48px;
  padding: 0 18px;
  border-radius: 24px;
  color: var(--kean-card);
  background: var(--kean-primary);
  box-shadow: 0 8px 20px rgba(77, 128, 240, 0.34);
  font-size: 14px;
  font-weight: 600;
  white-space: nowrap;
}
.publish-fab text:first-child { font-size: 18px; line-height: 1; }
/* 换壁纸时页面背景透明了，阴影收一点、实一点，按钮才不会糊在照片上 */
.page.skinned .publish-fab { box-shadow: 0 10px 24px rgba(8, 14, 30, 0.46); }
/* 底部留白 80px = 按钮高 48 + 按钮与 tabBar 的间距 16 + 呼吸位 16：
   按钮占的是「tabBar 以上 16~64px」这段视口，内容滚到底时会钻到它下面，
   而框架只替 tabBar 那 50px + 安全区留了位，按钮这 64px 得自己留。
   放在 .page 而不是 .list：ListState 的 loading / 空态 / 失败三个分支不带 .list，
   加在 .page 上四个分支一起生效；.page 本来就是 min-height: 100vh、本页恒有滚动，
   所以不会凭空多出一条滚动条。 */
.page { padding-bottom: 80px; }
</style>
