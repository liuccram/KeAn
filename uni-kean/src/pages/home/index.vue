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

async function loadList(reset = false) {
  if (loading.value) {
    return;
  }
  const first = list.value.length === 0;
  if (reset) {
    page.value = 1;
    finished.value = false;
  }
  loading.value = true;
  error.value = "";
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
    total.value = data.total;
    list.value = reset ? data.list : list.value.concat(data.list);
    finished.value = list.value.length >= data.total;
  } catch (err) {
    const message = (err as Error).message || "加载失败";
    error.value = message;
    // 已经有内容时只用轻提示：这时若让失败态顶掉列表，比不提示更糟。
    // 没有内容时交给 ListState 显示原因和「重新加载」。
    if (!first) {
      toast.error(message);
    }
  } finally {
    loading.value = false;
    uni.stopPullDownRefresh();
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
  loadList(true);
  loadOngoing();
  refreshMessageBadge();
  loadAnnouncement();
});

useLiveUpdates((event) => {
  if (!event) {
    loadOngoing();
    return;
  }
  if (event.type === "NOTICE" && (event.bizType === "TASK" || event.noticeType === "APPLICATION" || event.noticeType === "TASK")) {
    loadList(true);
    loadOngoing();
  }
}, 0);

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
        <view v-for="item in list" :key="item.id" class="card" @click="goDetail(item.id)">
          <view class="card-top">
            <text class="course">{{ item.courseName }}</text>
            <view class="card-right">
              <text v-if="!item.mine" class="fav" @click.stop="toggleFavorite(item)">{{ item.favorited ? "★" : "☆" }}</text>
              <text class="status">{{ TASK_STATUS_TEXT[item.status] || item.status }}</text>
            </view>
          </view>
          <view class="meta">{{ item.taskDate }} {{ item.startTime }}-{{ item.endTime }}</view>
          <view v-if="countdownOf(item)" class="count">{{ countdownOf(item) }}</view>
          <view class="meta">
            <CampusHighlight :name="item.campusName" /><text>{{ item.building }} {{ item.classroom }}</text>
          </view>
          <view class="card-bottom">
            <text class="reward">{{ formatReward(item.reward) }}</text>
            <text class="count-app">{{ item.applyCount }} 人申请</text>
          </view>
        </view>
        <view class="end">{{ finished ? "没有更多了" : "上拉加载更多" }}</view>
      </view>
    </ListState>
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
  background: var(--kean-card);
}
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
  background: var(--kean-card);
}
.quick {
  display: flex;
  gap: 8px;
  padding: 8px 16px 4px;
  position: relative;
  z-index: 0;
}
.chip {
  height: 28px;
  padding: 0 12px;
  border-radius: 14px;
  background: var(--kean-card);
  color: var(--kean-sub);
  font-size: 13px;
  display: flex;
  align-items: center;
}
.chip.on {
  background: var(--kean-primary-active);
  color: var(--kean-card);
  font-weight: 600;
}
.page.skinned .chip {
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
.ongoing-error {
  padding: 0 18px 8px;
  color: #d94b4b;
  font-size: 12px;
}
.card {
  background: var(--kean-card);
  border-radius: 12px;
  padding: 16px;
  margin-bottom: 12px;
}
.card-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
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
  margin-top: 8px;
  color: var(--kean-sub);
  font-size: 13px;
}
.count {
  margin-top: 6px;
  color: #f77234;
  font-size: 12px;
  font-weight: 600;
}
.card-bottom {
  margin-top: 12px;
  display: flex;
  justify-content: space-between;
  color: var(--kean-muted);
  font-size: 13px;
}
.reward {
  color: #f77234;
  font-weight: 600;
}
.end {
  text-align: center;
  color: var(--kean-muted);
  font-size: 12px;
  padding: 8px 0 16px;
}
</style>
