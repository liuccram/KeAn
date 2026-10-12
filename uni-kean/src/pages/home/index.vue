<script setup lang="ts">
import { addFavorite, removeFavorite } from "@/api/favorite";
import { listActiveAnnouncements, type ActiveAnnouncement } from "@/api/announcement";
import { listTasks, TASK_STATUS_TEXT, type TaskItem } from "@/api/task";
import CampusHighlight from "@/components/CampusHighlight.vue";
import FallbackImage from "@/components/FallbackImage.vue";
import ListState from "@/components/ListState.vue";
import OngoingTasks from "@/components/OngoingTasks.vue";
import PageBackdrop from "@/components/PageBackdrop.vue";
import { useLiveUpdates } from "@/composables/useLiveUpdates";
import { useNowTick } from "@/composables/useNowTick";
import { useOngoingTasks } from "@/composables/useOngoingTasks";
import { usePageWallpaper } from "@/composables/usePageWallpaper";
import { useUserStore } from "@/store/user";
import { t } from "@/utils/i18n";
import { formatDate, formatReward, genderRequirementLabel } from "@/utils/format";
import { refreshMessageBadge } from "@/utils/messageBadge";
import { classCountdown, nextTaskStep, taskDetailUrl, taskStartAt, todayText } from "@/utils/taskAction";
import { resolveMediaUrl } from "@/utils/request";
import { onPullDownRefresh, onReachBottom, onShow } from "@dcloudio/uni-app";
import { useToast } from "wot-design-uni";
import { computed, reactive, ref } from "vue";

const toast = useToast();
const userStore = useUserStore();
const { wallpaperOn, wallpaperImage, prefs } = usePageWallpaper();
const { items: ongoing, loading: ongoingLoading, error: ongoingError, load: loadOngoing } = useOngoingTasks();
// 顶部标题栏改成页面内自绘（pages.json 的 navigationStyle: custom，见下）后，刘海屏要自己
// 让出状态栏高度；取值与 pages/mine/index.vue 完全同一套（同一行、同一个 20 兜底）。
const statusBarHeight = uni.getSystemInfoSync().statusBarHeight || 20;
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
const sortMode = ref<"newest" | "soonest" | "reward">("newest");
const selectedDate = ref(todayText());
const datePickerValue = ref<number | null>(Date.now());
const page = ref(1);
const total = ref(0);
const todayOpenCount = ref<number | null>(null);
const announcement = ref<ActiveAnnouncement | null>(null);
const DISMISS_KEY = "kean_dismissed_announcements";
// 「进行中的代课」标题行的展开/收起记忆：1 = 收起、0 = 展开。
// 与公告的 DISMISS_KEY 同一套写法（同步 uni.get/setStorageSync，不加请求）。
const ONGOING_COLLAPSE_KEY = "kean_home_ongoing_collapsed";

const emptyText = computed(() => "暂时没有合适的代课需求");
const dateChoices = computed(() => {
  const weekdays = ["周日", "周一", "周二", "周三", "周四", "周五", "周六"];
  return Array.from({ length: 4 }, (_, offset) => {
    const date = new Date(nowTick.value);
    date.setHours(0, 0, 0, 0);
    date.setDate(date.getDate() + offset);
    return {
      value: formatDate(date.getTime()),
      label: offset === 0 ? "今天" : offset === 1 ? "明天" : weekdays[date.getDay()],
      day: `${date.getMonth() + 1}/${date.getDate()}`
    };
  });
});
const calendarDateLabel = computed(() => {
  if (!selectedDate.value) return "日历";
  const selected = selectedDate.value.slice(5).replace("-", "/");
  return dateChoices.value.some((item) => item.value === selectedDate.value) ? "日历" : selected;
});
const hasActiveFilters = computed(() => Boolean(
  keyword.value.trim() || filter.status !== "OPEN" || filter.timeSlot !== "ALL" || selectedDate.value !== todayText(nowTick.value) || sortMode.value !== "newest"
));
const sortOptions = ["最新发布", "离上课最近", "酬金从高到低"];
const sortLabel = computed(() => sortOptions[sortMode.value === "newest" ? 0 : sortMode.value === "soonest" ? 1 : 2]);
const sortIndex = computed(() => sortMode.value === "newest" ? 0 : sortMode.value === "soonest" ? 1 : 2);
const displayList = computed(() => {
  const rows = [...list.value];
  if (sortMode.value === "soonest") {
    rows.sort((a, b) => Math.abs(taskStartMillis(a) - nowTick.value) - Math.abs(taskStartMillis(b) - nowTick.value));
  } else if (sortMode.value === "reward") {
    rows.sort((a, b) => Number(b.reward || 0) - Number(a.reward || 0));
  } else {
    rows.sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());
  }
  return rows;
});
const upcomingCount = computed(() => ongoing.value.filter((item) => {
  const start = taskStartAt(item);
  return (item.status === "MATCHED" || item.status === "CONFIRMED") && start != null && start >= nowTick.value && start - nowTick.value <= 24 * 60 * 60 * 1000;
}).length);
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

async function loadTodayOverview() {
  try {
    const data = await listTasks({
      schoolId: Number(userStore.state.user?.schoolId) || undefined,
      status: "OPEN",
      taskDate: todayText(nowTick.value),
      page: 1,
      size: 1
    });
    todayOpenCount.value = Number(data.total || 0);
  } catch {
    // 统计不可用时隐藏数字；不把接口异常伪装成 0 条需求。
    todayOpenCount.value = null;
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

function taskStartMillis(item: TaskItem) {
  const parsed = new Date(item.startAt || `${item.taskDate}T${item.startTime}`).getTime();
  return Number.isNaN(parsed) ? Number.MAX_SAFE_INTEGER : parsed;
}

function stepOf(item: TaskItem) {
  const step = nextTaskStep(item, nowTick.value);
  if (step.key === "review" && item.canReview === false) {
    return { key: "none" as const, label: "查看详情" };
  }
  return step;
}

function goTaskAction(item: TaskItem) {
  uni.navigateTo({ url: taskDetailUrl(item.id, stepOf(item).key) });
}

function selectStatus(status: string) {
  if (status === "MINE" && !userStore.isLoggedIn.value) {
    uni.navigateTo({ url: "/pages/auth/login" });
    return;
  }
  filter.status = status;
  loadList(true);
}

function onSortChange(event: { detail?: { value?: number | string }; value?: number | string }) {
  const value = Number(event?.detail?.value ?? event?.value ?? 0);
  sortMode.value = value === 1 ? "soonest" : value === 2 ? "reward" : "newest";
}

function resetFilters() {
  keyword.value = "";
  filter.status = "OPEN";
  filter.timeSlot = "ALL";
  selectedDate.value = todayText(nowTick.value);
  datePickerValue.value = nowTick.value;
  sortMode.value = "newest";
  loadList(true);
}

function goPublish() {
  uni.switchTab({ url: "/pages/publish/index" });
}

function goMyOngoing() {
  uni.navigateTo({ url: userStore.isLoggedIn.value ? "/pages/mine/ongoing" : "/pages/auth/login" });
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
 * 请求作废序号：无论是筛选切换还是自动刷新，只接受最后发起的请求结果，
 * 避免旧条件的列表盖住用户刚选择的新条件。
 */
let autoSeq = 0;

/**
 * @param reset 按首次加载处理：回到第一页并整体替换列表
 * @param silent 静默刷新（回到页面 / 轮询兜底用）：不置 loading、不弹轻提示，只更新数据
 */
async function loadList(reset = false, silent = false) {
  if (silent && loading.value) {
    return;
  }
  const seq = ++autoSeq;
  if (reset) {
    page.value = 1;
    finished.value = false;
    if (!silent) {
      list.value = [];
      total.value = 0;
    }
  }
  const first = list.value.length === 0;
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
    if (seq !== autoSeq) {
      return;
    }
    // 静默刷新只在拿到结果后清错误：先把「上次加载失败」闪成「暂无数据」再变回来更糟
    error.value = "";
    total.value = data.total;
    list.value = reset ? data.list : list.value.concat(data.list);
    finished.value = list.value.length >= data.total;
  } catch (err) {
    if (seq !== autoSeq) {
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
    if (!silent && seq === autoSeq) {
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

function selectDate(date: string) {
  selectedDate.value = date;
  datePickerValue.value = new Date(`${date}T00:00:00`).getTime();
  loadList(true);
}

function onDateClear() {
  selectedDate.value = "";
  datePickerValue.value = Date.now();
  loadList(true);
}

function countdownOf(item: TaskItem) {
  return classCountdown(item, nowTick.value);
}

onShow(() => {
  // 静默刷新：任务列表 + 进行中两块数据都刷，但不显示加载骨架、不把已有列表顶掉
  loadList(true, true);
  loadOngoing();
  loadTodayOverview();
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
    loadTodayOverview();
  }
}, 30000);

onPullDownRefresh(() => {
  loadList(true);
  loadOngoing();
  loadTodayOverview();
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
    <!-- 顶部标题栏（原框架导航栏，现按「我的」页的做法自绘）：
         ⚠️ 它就在页面内容的滚动流里，**没有** position: fixed / sticky → 随内容一起滚走。
         文案取 pages.json 的 navigationBarTitleText「首页」，不另造文案。 -->
    <view class="status-bar" :style="{ height: statusBarHeight + 'px' }" />
    <view class="nav-title">首页</view>
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
        <text class="intro-kicker">课安 · 校园代课互助</text>
        <text class="intro-title">让每一节课，都有人接手。</text>
        <text class="intro-subtitle">让临时有事的同学找到帮手，让愿意互助的同学找到机会。</text>
      </view>
      <view class="campus-scene" aria-hidden="true"><view class="campus-sun" /><view class="campus-building campus-building--back" /><view class="campus-building campus-building--front" /><view class="campus-tree" /></view>
    </view>
    <view class="search">
      <wd-search v-model="keyword" placeholder="搜索课程 / 教学楼 / 教室" hide-cancel @search="onSearch" @clear="onSearch" />
    </view>
    <view class="date-browser">
      <view class="date-strip">
        <view v-for="item in dateChoices" :key="item.value" class="date-choice" :class="{ 'date-choice--on': selectedDate === item.value }" @click="selectDate(item.value)">
          <text class="date-choice__label">{{ item.label }}</text><text class="date-choice__day">{{ item.day }}</text>
        </view>
        <view class="date-choice date-choice--all" :class="{ 'date-choice--on': !selectedDate }" @click="onDateClear">
          <text class="date-choice__label">全部</text><text class="date-choice__day">日期</text>
        </view>
      </view>
      <wd-calendar v-model="datePickerValue" type="date" title="选择上课日期" @confirm="onDateConfirm">
        <view class="calendar-trigger"><wd-icon name="calendar" /><text>{{ calendarDateLabel }}</text></view>
      </wd-calendar>
    </view>
    <view class="today-overview">
      <view v-if="todayOpenCount !== null" class="overview-item"><text class="overview-value">{{ todayOpenCount }}</text><text class="overview-label">今日待接手</text></view>
      <view v-if="todayOpenCount !== null" class="overview-divider" />
      <view v-if="userStore.isLoggedIn && !ongoingLoading && !ongoingError" class="overview-item"><text class="overview-value">{{ upcomingCount }}</text><text class="overview-label">24 小时内开课</text></view>
      <view class="overview-action" @click="goMyOngoing">我的进行中 <wd-icon name="arrow-right" /></view>
    </view>
    <view class="quick-filters">
      <view class="quick-chip" :class="{ 'quick-chip--on': filter.status === 'ALL' }" @click="selectStatus('ALL')">全部需求</view>
      <view class="quick-chip" :class="{ 'quick-chip--on': filter.status === 'OPEN' }" @click="selectStatus('OPEN')">可申请</view>
      <view class="quick-chip" :class="{ 'quick-chip--on': filter.status === 'MINE' }" @click="selectStatus('MINE')">我的发布</view>
      <view class="quick-spacer" />
      <picker mode="selector" :range="sortOptions" :value="sortIndex" @change="onSortChange">
        <view class="sort-trigger"><wd-icon name="order-descending" /><text>{{ sortLabel }}</text><wd-icon name="arrow-down" /></view>
      </picker>
    </view>
    <view class="advanced-filters">
      <view class="filter-caption">上课时段</view>
      <wd-drop-menu>
        <wd-drop-menu-item v-model="filter.timeSlot" :options="timeOptions" @change="onFilterChange" />
      </wd-drop-menu>
      <view v-if="hasActiveFilters" class="reset-filter" @click="resetFilters">重置</view>
    </view>
    <view v-if="hasActiveFilters" class="active-filter-line">
      <text>当前条件</text>
      <text v-if="keyword.trim()" class="active-filter">{{ keyword.trim() }}</text>
      <text v-if="filter.status !== 'OPEN'" class="active-filter">{{ filter.status === 'ALL' ? '全部状态' : '我的发布' }}</text>
      <text v-if="filter.timeSlot !== 'ALL'" class="active-filter">{{ filter.timeSlot }}</text>
      <text v-if="selectedDate !== todayText(nowTick)" class="active-filter">{{ selectedDate || '全部日期' }}</text>
      <text class="active-filter">{{ sortLabel }}</text>
      <text v-if="sortMode !== 'newest'" class="sort-scope">已加载结果</text>
    </view>
    <!-- 标题行可点：右侧 chevron + 数量角标；收起/展开的选择记在 ONGOING_COLLAPSE_KEY。
         ⚠️ collapsible / collapse-key 是 OngoingTasks 的新增可选 prop，
         「我的」页与「进行中的代课」页不传 = 老行为（组件内有逐字一致的 else 分支）。 -->
    <OngoingTasks
      v-if="userStore.isLoggedIn"
      :items="ongoing"
      :limit="3"
      collapsible
      :collapse-key="ONGOING_COLLAPSE_KEY"
    />
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
      <template #loading>
        <view class="home-skeleton" aria-label="正在加载需求">
          <view v-for="index in 3" :key="index" class="skeleton-card">
            <view class="skeleton-top"><view class="skeleton-line skeleton-course" /><view class="skeleton-line skeleton-tag" /></view>
            <view class="skeleton-line skeleton-wide" /><view class="skeleton-line skeleton-medium" />
            <view class="skeleton-bottom"><view class="skeleton-line skeleton-short" /><view class="skeleton-line skeleton-tag" /></view>
          </view>
        </view>
      </template>
      <template #empty>
        <view class="home-empty">
          <view class="home-empty__illustration"><view class="empty-room"><view /><view /><view /></view><wd-icon name="search" /></view>
          <text class="home-empty__title">暂时没有合适的代课需求</text>
          <text class="home-empty__copy">{{ hasActiveFilters ? '试试调整日期或筛选条件，也可以发布一条求助。' : '新的校园求助出现时，会展示在这里。你也可以发布自己的需求。' }}</text>
          <view class="home-empty__actions">
            <wd-button v-if="hasActiveFilters" plain size="small" @click="resetFilters">重置筛选</wd-button>
            <wd-button type="primary" size="small" @click="goPublish">发布求助</wd-button>
          </view>
        </view>
      </template>
      <view class="list">
        <view class="list-heading"><view><text class="list-heading__title">校园求助</text><text class="list-heading__sub">真实需求 · 按你的时间和地点查找</text></view><text class="list-heading__count">{{ total }} 条需求</text></view>
        <view v-for="item in displayList" :key="item.id" class="card" @click="goDetail(item.id)">
          <view class="card-top">
            <view class="course-wrap"><view class="course-mark"><wd-icon name="edit-outline" /></view><text class="course">{{ item.courseName }}</text></view>
            <view class="card-right">
              <view class="status" :class="`status--${item.status.toLowerCase()}`">{{ TASK_STATUS_TEXT[item.status] || item.status }}</view>
              <wd-icon v-if="!item.mine" class="fav" :name="item.favorited ? 'star-filled' : 'star'" @click.stop="toggleFavorite(item)" />
            </view>
          </view>
          <view class="task-info-row"><wd-icon class="info-icon" name="time" /><view class="meta">{{ item.taskDate }} <text class="time-emphasis">{{ item.startTime.slice(0, 5) }}–{{ item.endTime.slice(0, 5) }}</text></view><text v-if="countdownOf(item)" class="count">{{ countdownOf(item) }}</text></view>
          <view class="task-info-row"><wd-icon class="info-icon pin-icon" name="location" /><view class="meta"><CampusHighlight :name="item.campusName" /><text>{{ item.building }} {{ item.classroom }}</text></view>
          </view>
          <view v-if="item.requirement || (item.genderRequirement && item.genderRequirement !== 'ANY') || item.computerLab || item.requirePhoto || item.applyCount > 0" class="requirements">
            <text v-if="item.computerLab" class="requirement-tag">机房</text><text v-if="item.requirePhoto" class="requirement-tag">需现场照片</text><text v-if="item.genderRequirement && item.genderRequirement !== 'ANY'" class="requirement-tag">{{ genderRequirementLabel(item.genderRequirement) }}</text>
            <text v-if="item.applyCount > 0" class="requirement-tag">{{ item.applyCount }} 人已响应</text>
            <text v-if="item.requirement" class="requirement-copy">{{ item.requirement }}</text>
          </view>
          <view class="card-bottom">
            <view class="publisher">
              <FallbackImage v-if="item.publisher?.avatarUrl" class="publisher-avatar" :src="resolveMediaUrl(item.publisher.avatarUrl)" mode="aspectFill" />
              <view v-else class="publisher-avatar publisher-avatar--fallback">{{ (item.publisher?.nickname || '同学').slice(0, 1) }}</view>
              <text class="publisher-name">{{ item.publisher?.nickname || '校内同学' }}</text>
            </view>
            <view class="card-action"><view class="reward-pill"><text class="reward">{{ formatReward(item.reward) }}</text><text class="reward-caption">酬谢</text></view><view class="action-label" @click.stop="goTaskAction(item)">{{ stepOf(item).label }}<wd-icon name="arrow-right" /></view></view>
          </view>
        </view>
        <view class="end">{{ finished ? "没有更多了" : "上拉加载更多" }}</view>
      </view>
    </ListState>
    <!-- 发布求助：右下角**圆形**悬浮按钮（只有一个加号、不带文字、不占地方）。
         原来这里是「＋ 发布求助」胶囊；按用户要求收成一颗圆钮。
         ⚠️ 点击逻辑仍然是原来那个 goPublish，一个字都没改。
         ⚠️ 加号用「＋」（全角加号字符）而不是 CSS 画线：它与原来胶囊里那个 `＋`
         是**同一个字符**（视觉零变化），也省掉 pseudo-element 的定位代码。 -->
    <view class="publish-fab" @click="goPublish"><text class="publish-fab__plus">＋</text></view>
    <wd-toast />
  </view>
</template>

<style scoped>
/* ===========================================================================
 * 顶部标题栏（自绘，替代被 navigationStyle: custom 移除的框架导航栏）
 * ---------------------------------------------------------------------------
 * 排版逐字照抄 pages/mine/index.vue 的 .status-bar / .nav-title（含全局表
 * design-kit.css 的 `.kean-mine .nav-title`：17px / 600 / 居中 / 10-6 内边距），
 * 一个字都没有新发明：
 *   · .status-bar —— 状态栏高度的占位条（高度由模板的行内 style 给）；
 *   · .nav-title  —— 标题行，单行高度 ≈ 10+22+6 = 38px，与框架导航栏的 44px
 *     同一量级，所以页面内容往下推的距离与原框架栏基本一致。
 * ⚠️ 这里**不写** position: fixed / sticky：它必须是普通文档流里的元素，
 *    才会随页面内容一起滚走（这正是本次需求）。
 * ⚠️ 也**不写** backdrop-filter / transform / filter / will-change / contain /
 *    perspective：本页 <wd-toast> 与 .announce-mask 都是 position: fixed 的
 *    .page 直接子节点，祖先链上出现这些属性会把它们劫持成"关不掉的弹层"。
 * 颜色：原框架栏是全局 style 的白底黑字（navigationBarBackgroundColor #FFFFFF /
 *    navigationBarTextStyle black），所以字色取 --kean-text（#1d2129）并带同值兜底；
 *    底色刻意留透明 —— 白底块在滚动中段会糊成一条白带子，而「我的」页的自绘标题
 *    也是透明底。壁纸/深色那两态下与「我的」页同向改成浅色字（见下）。
 * =========================================================================== */
.status-bar {
  width: 100%;
}
.nav-title {
  text-align: center;
  font-size: 17px;
  font-weight: 600;
  color: var(--kean-text, #1d2129);
  padding: 10px 0 6px;
}
/* 壁纸态：本页页面底变成深色流星图（wallpaper-skin.css），黑字会看不见 ——
   与「我的」页 `.page.skinned.kean-mine .guest .nav-title` 同向改浅色。
   特异性 0,3,0 > 上面 scoped 的 0,2,0（scoped 的 .page.skinned + .nav-title[data-v]）。 */
.page.skinned .nav-title {
  color: #f4f7ff;
}
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
.quick-filters { display:flex; align-items:center; gap:7px; margin:10px 16px 0; min-width:0; }
.quick-chip { display:flex; height:32px; align-items:center; justify-content:center; padding:0 12px; border:1px solid rgba(218,227,239,.9); border-radius:17px; color:#687991; background:rgba(255,255,255,.78); font-size:11px; white-space:nowrap; }
.quick-chip--on { border-color:#274b78; color:#fff; background:#274b78; font-weight:600; }
.quick-spacer { flex:1; min-width:0; }
.sort-trigger { display:flex; align-items:center; gap:4px; height:32px; color:#687991; font-size:10px; white-space:nowrap; }
.sort-trigger :deep(.wd-icon) { font-size:12px; }
.advanced-filters { position:relative; display:flex; align-items:center; gap:8px; height:34px; margin:3px 16px 0; padding:0 7px; border-bottom:1px solid rgba(220,228,239,.8); }
.advanced-filters :deep(.wd-drop-menu) { flex:1; height:32px; background:transparent; }
.advanced-filters :deep(.wd-drop-menu__item) { height:32px; }
.advanced-filters :deep(.wd-drop-menu__item-title),.advanced-filters :deep(.wd-drop-menu__item-title-text) { color:#6d7f96; font-size:11px; }
/* ⭐ 本轮：把「全部时间」这段文字**在它自己的按钮里居中**。
   定位过程（先 grep 后改）：「全部时间」= i18n 的 allTimes（utils/i18n.ts:18 / :317），
   全仓库**唯一**引用点是本页 timeOptions 的第一项（:94），而它的落点就是这一行的
   wd-drop-menu-item 触发器标题（即模板 :437 那个组件，类名 .wd-drop-menu__item-title-text）。
   wot-design-uni 自己虽然给了 `justify-content: center` + `text-align: center`
   （components/wd-drop-menu/index.scss:30-60），但那两条在**异步分包 CSS** 里、
   且随时可能被组件升级改掉；这里就近写死一份，等于钉住这条口径。
   ⚠️ 只作用于**标题文字本身及其内层 text**（.wd-drop-menu__item-title 是 inline-flex，
   .wd-drop-menu__item-title-text 是它唯一的孩子），**整行布局一个字没动**：
   左侧「上课时段」caption、右侧「重置」都还在原位、样式未变，
   也没有对下拉面板里的选项（.wd-drop-item__title）做任何改动。
   ⚠️ 若将来面板里也要居中，另加 `.advanced-filters :deep(.wd-drop-item__title)` —— 本轮不加。 */
.advanced-filters :deep(.wd-drop-menu__item-title),
.advanced-filters :deep(.wd-drop-menu__item-title-text) {
  justify-content: center;
  text-align: center;
}
.filter-caption { color:#91a0b2; font-size:10px; }
.reset-filter { display:flex; min-height:32px; align-items:center; margin-left:auto; padding-left:12px; color:#527ba9; font-size:11px; }
.page.skinned .quick-chip { border-color:rgba(255,255,255,.15); color:#d1dced; background:rgba(18,23,34,.76); }
.page.skinned .quick-chip--on { border-color:#8bb0e1; color:#172841; background:#a9c8ee; }
.page.skinned .sort-trigger,.page.skinned .filter-caption { color:#c0cee1; }
.page.skinned .advanced-filters { border-color:rgba(255,255,255,.14); }
.page.skinned .advanced-filters :deep(.wd-drop-menu),.page.skinned .advanced-filters :deep(.wd-drop-menu__list),.page.skinned .advanced-filters :deep(.wd-drop-menu__item) { background:transparent !important; }
.page.skinned .advanced-filters :deep(.wd-drop-menu__item-title),.page.skinned .advanced-filters :deep(.wd-drop-menu__item-title-text) { color:#c0cee1 !important; }
.reset-filter { color:var(--kean-primary); }
.active-filter-line { display:flex; align-items:center; gap:6px; min-height:25px; margin:0 16px; overflow:hidden; color:#8b98a9; font-size:9px; white-space:nowrap; }
.active-filter { max-width:100px; overflow:hidden; padding:3px 6px; border-radius:6px; color:#57769c; background:#eef3fa; text-overflow:ellipsis; }
.sort-scope { flex-shrink:0; color:#9aa6b6; font-size:9px; }
.page.skinned .active-filter-line { color:#b6c4d7; }.page.skinned .active-filter { color:#d4e2f5; background:rgba(255,255,255,.11); }
.today-overview { display:flex; align-items:center; gap:13px; margin:5px 20px 1px; padding:8px 2px; border-bottom:1px solid rgba(220,228,239,.75); }
.overview-item { display:flex; align-items:baseline; gap:5px; white-space:nowrap; }.overview-value { color:#274b78; font-size:14px; font-weight:700; font-variant-numeric:tabular-nums; }.overview-label { color:#8795a8; font-size:9px; }.overview-divider { width:1px; height:18px; background:#dce4ef; }.overview-action { display:flex; min-height:32px; align-items:center; gap:2px; margin-left:auto; color:#5e7fa8; font-size:10px; white-space:nowrap; }.overview-action :deep(.wd-icon) { font-size:11px; }.page.skinned .today-overview { border-color:rgba(255,255,255,.14); }.page.skinned .overview-value { color:#d4e4fa; }.page.skinned .overview-label,.page.skinned .overview-action { color:#afc2dc; }
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
.home-intro { position: relative; display: flex; align-items: center; justify-content: space-between; gap: 10px; min-height: 102px; margin: 10px 16px 9px; padding: 17px 18px; overflow: hidden; border: 1px solid rgba(179,204,239,.55); border-radius: 20px; background: linear-gradient(125deg,#edf5ff 0%,#f7f9ff 58%,#e7f2ff 100%); }
.intro-copy { position: relative; z-index: 1; min-width: 0; display: flex; flex-direction: column; }
.intro-kicker { color: #6d8fbe; font-size: 10px; font-weight: 700; letter-spacing: 1px; }
.intro-title { margin-top: 7px; color: #203858; font-size: 17px; font-weight: 750; line-height: 1.35; }
.intro-subtitle { margin-top: 5px; color: #8292aa; font-size: 11px; }
.campus-scene { position:absolute; right:0; bottom:0; width:47%; height:100%; overflow:hidden; opacity:.82; pointer-events:none; }.campus-sun { position:absolute; top:12px; right:27px; width:32px; height:32px; border:1px solid rgba(233,190,124,.45); border-radius:50%; background:rgba(255,249,226,.68); }.campus-building { position:absolute; bottom:-1px; border:1px solid rgba(93,131,173,.2); border-bottom:0; border-radius:9px 9px 0 0; background:linear-gradient(180deg,rgba(255,255,255,.82),rgba(185,211,234,.75)); }.campus-building--back { right:20px; width:52px; height:54px; background-image:repeating-linear-gradient(90deg,transparent 0 10px,rgba(102,145,184,.15) 10px 13px),linear-gradient(180deg,rgba(255,255,255,.72),rgba(185,211,234,.72)); }.campus-building--front { right:69px; width:72px; height:40px; background-image:repeating-linear-gradient(90deg,transparent 0 13px,rgba(102,145,184,.14) 13px 17px),linear-gradient(180deg,rgba(255,255,255,.86),rgba(201,221,239,.78)); }.campus-tree { position:absolute; right:9px; bottom:12px; width:19px; height:35px; border-radius:50% 50% 30% 30%; background:#8eb6a4; box-shadow:0 18px 0 -7px #8caa9c; }.page.skinned .home-intro { border-color:rgba(185,203,235,.2); background:linear-gradient(125deg,rgba(17,27,48,.88),rgba(27,38,62,.78)); }.page.skinned .intro-title { color:#f0f5ff; }.page.skinned .intro-kicker,.page.skinned .intro-subtitle { color:#b6c7e1; }.page.skinned .campus-scene { opacity:.42; }
.date-browser { display:flex; align-items:center; gap:8px; margin:0 16px 8px; padding:8px; border:1px solid rgba(224,232,243,.9); border-radius:16px; background:var(--kean-card); box-shadow:0 4px 14px rgba(35,60,98,.03); }.date-strip { display:flex; min-width:0; flex:1; align-items:center; gap:5px; }.date-choice { display:flex; min-width:43px; flex:1; flex-direction:column; align-items:center; justify-content:center; gap:3px; min-height:48px; border:1px solid transparent; border-radius:11px; color:#8795a8; }.date-choice__label { font-size:10px; }.date-choice__day { color:#52657e; font-size:11px; font-weight:700; }.date-choice--on { border-color:#bdd2ef; color:#4a79b6; background:#edf4ff; }.date-choice--on .date-choice__day { color:#3f70ad; }.calendar-trigger { display:flex; min-width:45px; height:48px; flex-direction:column; align-items:center; justify-content:center; gap:3px; border-left:1px solid #edf0f5; color:#6f89ab; font-size:9px; }.calendar-trigger :deep(.wd-icon) { font-size:16px; }.calendar-trigger text { max-width:52px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }.page.skinned .date-browser { border-color:rgba(255,255,255,.12); background:rgba(18,23,34,.86); box-shadow:none; }.page.skinned .date-choice { color:#aebbd0; }.page.skinned .date-choice__day { color:#e4ebf6; }.page.skinned .date-choice--on { border-color:rgba(145,182,235,.45); background:rgba(93,131,190,.3); }.page.skinned .date-choice--on .date-choice__day { color:#fff; }.page.skinned .calendar-trigger { border-color:rgba(255,255,255,.12); color:#c6d5ec; }
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
  position: relative;
  background: var(--kean-card);
  border: 1px solid rgba(225,232,242,.84);
  border-radius: 15px;
  padding: 14px 14px 12px;
  margin-bottom: 9px;
  box-shadow: 0 3px 12px rgba(29,54,91,.035);
}
.page.skinned .card { border-color: rgba(255,255,255,.12); background: rgba(18,23,34,.78); box-shadow: none; }
.card-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.course-wrap { display: flex; align-items: center; gap: 9px; min-width: 0; flex:1; }.course-mark { flex-shrink: 0; width: 30px; height: 30px; display: flex; align-items: center; justify-content: center; border-radius: 9px; color: #5682c0; background: #edf4ff; font-size: 14px; font-weight: 700; }.page.skinned .course-mark { color: #cfe1ff; background: rgba(124,164,226,.2); }
.course {
  min-width:0;
  overflow:hidden;
  font-size: 15px;
  font-weight: 700;
  color: var(--kean-text);
  text-overflow:ellipsis;
  white-space:nowrap;
}
.card-right {
  display: flex;
  align-items: center;
  gap: 8px;
}
.fav {
  display:flex;
  width:32px;
  height:32px;
  flex-shrink:0;
  align-items:center;
  justify-content:center;
  color: var(--kean-star);
  font-size: 17px;
}
.status {
  padding:4px 7px;
  border-radius:7px;
  color:#5579a6;
  background:#eef4fc;
  font-size:10px;
  white-space:nowrap;
}
.status--matched,.status--confirmed,.status--in_progress { color:#3c806b; background:#eaf5f0; }
.status--completed { color:#66788e; background:#f0f2f5; }
.status--cancelled,.status--expired { color:#9a7580; background:#f7eff1; }
.page.skinned .status { color:#d5e4f7; background:rgba(113,150,199,.22); }
.page.skinned .status--matched,.page.skinned .status--confirmed,.page.skinned .status--in_progress { color:#bce3d3; background:rgba(66,143,113,.2); }
.meta {
  min-width: 0;
  color: var(--kean-sub);
  font-size: 13px;
}
.task-info-row { display: flex; min-width:0; align-items: center; gap: 7px; margin-top: 9px; }.info-icon { flex-shrink: 0; width: 17px; height: 17px; color: #7891b0; font-size: 13px; }.pin-icon { color: #a98158; }.time-emphasis { margin-left: 4px; color: var(--kean-text); font-weight: 600; }.page.skinned .info-icon { color: #d0e2ff; }
.count {
  margin-top: 6px;
  color: #f77234;
  font-size: 12px;
  font-weight: 600;
}
.card-bottom {
  margin-top: 11px;
  padding-top: 10px;
  border-top: 1px solid rgba(229,234,242,.78);
  display: flex;
  justify-content: space-between;
  align-items: center;
  color: var(--kean-muted);
  font-size: 13px;
}
.page.skinned .card-bottom { border-color: rgba(255,255,255,.12); }
.publisher { display:flex; min-width:0; align-items:center; gap:7px; }
.publisher-avatar { display:flex; width:22px; height:22px; flex:0 0 22px; align-items:center; justify-content:center; overflow:hidden; border-radius:50%; color:#58769c; background:#e8eff8; font-size:10px; }
.publisher-name { max-width:90px; overflow:hidden; color:var(--kean-muted); font-size:10px; text-overflow:ellipsis; white-space:nowrap; }
.page.skinned .publisher-avatar--fallback { color:#d5e4f7; background:rgba(255,255,255,.12); }
.card-action { display:flex; align-items:center; gap:12px; }
.action-label { display:flex; min-height:34px; align-items:center; gap:3px; color:#3c6fa9; font-size:11px; font-weight:600; }
.action-label :deep(.wd-icon) { font-size:12px; }
.page.skinned .action-label { color:#b9d2f1; }
.requirements { display:flex; flex-wrap:wrap; align-items:center; gap:5px; margin-top:8px; }
.requirement-tag { padding:3px 6px; border:1px solid #e8edf4; border-radius:5px; color:#75869b; font-size:9px; }
.requirement-copy { max-width:100%; overflow:hidden; color:#75869b; font-size:10px; text-overflow:ellipsis; white-space:nowrap; }
.page.skinned .requirement-tag { border-color:rgba(255,255,255,.14); color:#c4d1e3; }.page.skinned .requirement-copy { color:#b8c6d8; }
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
.home-empty { display:flex; flex-direction:column; align-items:center; margin:8px 16px 24px; padding:22px 18px 18px; border:1px solid rgba(224,232,243,.9); border-radius:22px; background:linear-gradient(155deg,rgba(255,255,255,.98),rgba(244,248,253,.96)); box-shadow:0 9px 25px rgba(34,59,97,.05); text-align:center; }.home-empty__illustration { position:relative; display:flex; align-items:center; justify-content:center; width:68px; height:68px; margin-bottom:13px; border:1px solid #dce8f6; border-radius:24px; color:#6c94c7; background:linear-gradient(145deg,#eaf3ff,#f8fbff); font-size:27px; }.empty-cap { position:absolute; top:12px; left:16px; width:16px; height:4px; border-radius:5px; background:#d7a866; transform:rotate(-25deg); }.home-empty__title { color:#2c405b; font-size:16px; font-weight:750; }.home-empty__copy { max-width:280px; margin-top:7px; color:#8794a6; font-size:11px; line-height:1.65; }.home-empty__actions { display:flex; justify-content:center; gap:9px; margin-top:16px; }.home-empty__actions :deep(.wd-button) { min-width:104px; border-radius:12px; }.home-empty__steps { display:flex; width:100%; align-items:center; justify-content:space-between; gap:4px; margin-top:22px; padding-top:15px; border-top:1px solid #e9eef5; }.home-empty__steps > view:not(.empty-step-line) { display:flex; flex:1; flex-direction:column; gap:5px; color:#8493a7; font-size:9px; }.home-empty__steps > view:not(.empty-step-line) text:first-child { color:#7294bd; font-size:9px; font-weight:700; }.empty-step-line { width:12px; height:1px; background:#d7e2ef; }.page.skinned .home-empty { border-color:rgba(255,255,255,.13); background:rgba(18,23,34,.86); }.page.skinned .home-empty__title { color:#eaf1fb; }.page.skinned .home-empty__copy,.page.skinned .home-empty__steps > view:not(.empty-step-line) { color:#b2bfd1; }
.home-empty { margin-top:15px; padding-top:20px; }.home-empty__illustration { width:58px; height:58px; border-radius:19px; font-size:22px; }.home-empty__title { font-size:15px; }.empty-room { position:absolute; inset:13px; display:flex; align-items:flex-end; justify-content:center; gap:3px; padding:0 3px; border:1px solid rgba(94,132,181,.28); border-bottom:0; border-radius:7px 7px 0 0; opacity:.58; }.empty-room view { width:6px; height:13px; border:1px solid rgba(94,132,181,.36); border-bottom:0; border-radius:3px 3px 0 0; }.empty-room view:nth-child(2) { height:19px; }.home-empty__illustration :deep(.wd-icon) { z-index:1; margin-top:16px; padding:4px; border-radius:50%; background:#f3f8ff; }
.home-skeleton { padding:8px 16px 24px; }.skeleton-card { margin-bottom:10px; padding:15px; border:1px solid rgba(225,232,242,.8); border-radius:15px; background:var(--kean-card); }.skeleton-top,.skeleton-bottom { display:flex; align-items:center; justify-content:space-between; gap:12px; }.skeleton-line { height:10px; border-radius:6px; background:linear-gradient(90deg,#edf1f6 20%,#f6f8fb 50%,#edf1f6 80%); background-size:200% 100%; }.skeleton-course { width:42%; height:15px; }.skeleton-tag { width:50px; height:19px; }.skeleton-wide { width:66%; margin-top:15px; }.skeleton-medium { width:43%; margin-top:10px; }.skeleton-bottom { margin-top:16px; padding-top:11px; border-top:1px solid #eef1f5; }.skeleton-short { width:32%; }.page.skinned .skeleton-card { border-color:rgba(255,255,255,.1); background:rgba(18,23,34,.78); }.page.skinned .skeleton-line { background:#263246; }
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
  /* ⭐ 本轮：胶囊 → 圆钮。宽高都写死 54px（不靠 padding 撑），
     border-radius: 50% 在偶数边长下得到正圆；横向占位从原来的约 156px 收到 54px。
     54px 落在用户给的 52~56px 区间中值上，热区 54×54 远大于 44px 的可点下限。
     ⚠️ 原来那份 `gap: 4px` / `padding: 0 18px` / `border-radius: 24px` /
     `height: 48px` / `font-size: 14px` / `font-weight: 600` / `white-space: nowrap`
     与下面那条 `.publish-fab text:first-child` 已**整条删除**，不留任何残留宽度。 */
  width: 54px;
  height: 54px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  color: var(--kean-card);
  background: var(--kean-primary);
  box-shadow: 0 8px 20px rgba(77, 128, 240, 0.34);
}
/* 加号：用「＋」字符（与原胶囊里那个字符**同一个**，视觉零变化），
   28px + line-height: 1，居中由外层 flex 的 align/justify 保证。
   ⚠️ 刻意不用 transform: scale() 做按下缩放：本页 .page 下还有 wd-toast /
   .announce-mask 两个 position: fixed 直接子节点，transform 会建包含块（铁律 3）；
   按下反馈改用下面的背景色加深，零风险且观感一致。 */
.publish-fab__plus {
  font-size: 28px;
  line-height: 1;
}
.publish-fab:active {
  background: var(--kean-primary-active);
}
/* 换壁纸时页面背景透明了，阴影收一点、实一点，按钮才不会糊在照片上 */
.page.skinned .publish-fab { box-shadow: 0 10px 24px rgba(8, 14, 30, 0.46); }
/* 底部留白 80px = 圆钮直径 54 + 圆钮与 tabBar 的间距 16 + 呼吸位 10：
   圆钮占的是「tabBar 以上 16~70px」这段视口，内容滚到底时会钻到它下面，
   而框架只替 tabBar 那 50px + 安全区留了位，圆钮这 70px 得自己留。
   （本轮胶囊改圆钮：48+16+16 = 80 → 54+16+10 = 80，**总留白一个像素没变**，
   所以这条 80px 不用改；圆钮比胶囊矮 6px，最后一张卡反而更安全。）
   放在 .page 而不是 .list：ListState 的 loading / 空态 / 失败三个分支不带 .list，
   加在 .page 上四个分支一起生效；.page 本来就是 min-height: 100vh、本页恒有滚动，
   所以不会凭空多出一条滚动条。 */
.page { padding-bottom: 80px; }
@media (max-width: 360px) {
  .home-intro { margin-right:12px; margin-left:12px; padding-right:12px; padding-left:14px; }
  .intro-title { max-width:240px; font-size:15px; }
  .intro-subtitle { max-width:230px; font-size:10px; line-height:1.45; }
  .search,.date-browser { margin-right:12px; margin-left:12px; }
  .quick-filters,.advanced-filters { margin-right:12px; margin-left:12px; }
  .quick-filters { gap:5px; }
  .quick-chip { padding:0 9px; font-size:10px; }
  .sort-trigger { gap:3px; font-size:9px; }
  .date-browser { gap:5px; padding:6px; }
  .date-strip { gap:2px; }
  .date-choice { min-width:37px; }
  .card { padding:12px; }
  .card-action { gap:7px; }
  .publisher-name { max-width:65px; }
  .action-label { font-size:10px; }
}
</style>
