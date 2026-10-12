<script setup lang="ts">
import { TASK_STATUS_TEXT, type TaskItem } from "@/api/task";
import CampusHighlight from "@/components/CampusHighlight.vue";
import { useNowTick } from "@/composables/useNowTick";
import { classCountdown, formatClockLabel, nextTaskStep, taskDetailUrl } from "@/utils/taskAction";
import { computed, ref } from "vue";

const props = withDefaults(defineProps<{
  items: TaskItem[];
  limit?: number;
  /**
   * 标题行是否可展开/收起。默认 false = 老行为（始终展开、标题行不可点），
   * 「我的」页 / 「进行中的代课」页继续用默认值，观感与本次改动前完全一致。
   */
  collapsible?: boolean;
  /**
   * 传入时把「收起」这个选择持久化到本地存储（同一个 key 复用）。
   * 不传 = 只做内存态，退出页面即复位。首页传 kean_home_ongoing_collapsed。
   */
  collapseKey?: string;
}>(), {
  limit: 0,
  collapsible: false,
  collapseKey: ""
});

const now = useNowTick();
const shown = computed(() => (props.limit > 0 ? props.items.slice(0, props.limit) : props.items));
const extra = computed(() => Math.max(0, props.items.length - shown.value.length));
// 角标口径与本项目既有徽标一致（pages/message/index.vue 的未读徽标）：>99 截断成 99+。
// 用 items.length 而不是 shown.length —— 角标答的是「进行中的代课有几条」，
// 与当前列表显示几条无关（首页 limit=3，否则永远只能显示 3）。
const countLabel = computed(() => (props.items.length > 99 ? "99+" : String(props.items.length)));
/** 读持久化的收起态；任何异常都退化为「展开」，绝不把首页渲染搞挂 */
function readCollapsed(key: string) {
  try {
    return String(uni.getStorageSync(key) || "") === "1";
  } catch (err) {
    return false;
  }
}

// 默认展开：有进行中的代课时先让用户看到内容；
// 存储里记着「上次是收起的」才收起。读失败（隐私模式/无 storage）时按默认展开走。
const collapsed = ref(props.collapseKey ? readCollapsed(props.collapseKey) : false);

function toggle() {
  collapsed.value = !collapsed.value;
  if (!props.collapseKey) {
    return;
  }
  try {
    uni.setStorageSync(props.collapseKey, collapsed.value ? "1" : "0");
  } catch (err) {
    // 存储写失败不影响本次会话的展开/收起（内存态已经改过了）
  }
}

/**
 * 「还有 N 个 / 全部」自己带跳转，必须掐断冒泡，
 * 否则点它会同时把标题行折叠起来（用户以为跳转没生效）。
 * 这里用函数而不是 @click.stop，是因为整个标题行是点击区，
 * 显式 stopPropagation 更好核对，也避免模板里再掺一个修饰符。
 */
function onMoreTap(event: { stopPropagation?: () => void }) {
  if (event && typeof event.stopPropagation === "function") {
    event.stopPropagation();
  }
  goAll();
}

function stepOf(item: TaskItem) {
  return nextTaskStep(item, now.value);
}

function countdownOf(item: TaskItem) {
  return classCountdown(item, now.value);
}

function partyLine(item: TaskItem) {
  if (item.mine) {
    const name = (item.matchedApplicantNickname || item.applicant?.nickname || "").trim();
    return name ? `申请人：${name}` : "";
  }
  const name = (item.publisher?.nickname || "").trim();
  return name ? `发布者：${name}` : "";
}

function goItem(item: TaskItem) {
  const step = stepOf(item);
  uni.navigateTo({ url: taskDetailUrl(item.id, step.key) });
}

function goAll() {
  uni.navigateTo({ url: "/pages/mine/ongoing" });
}
</script>

<template>
  <view v-if="items.length" class="ongoing">
    <!-- 首页（collapsible=true）：整个标题行可点，右侧 chevron 表示展开态 -->
    <view v-if="collapsible" class="head head--tap" @click="toggle">
      <view class="head-main">
        <text class="title">进行中的代课</text>
        <text class="badge">{{ countLabel }}</text>
      </view>
      <view class="head-side">
        <text v-if="limit > 0 && extra > 0" class="more" @click="onMoreTap">还有 {{ extra }} 个</text>
        <text v-else-if="limit > 0 && items.length > 1" class="more" @click="onMoreTap">全部</text>
        <!-- 不用 transform 旋转：展开/收起直接换图标名，既没有动画抖动，
             也避免给这个容器加 transform（首页有 .publish-fab / 公告遮罩等 fixed 后代） -->
        <wd-icon class="chevron" :name="collapsed ? 'arrow-right' : 'arrow-down'" />
      </view>
    </view>
    <!-- 「我的」页 / 「进行中的代课」页（默认 collapsible=false）：与改动前逐字一致 -->
    <view v-else class="head">
      <text class="title">进行中的代课</text>
      <text v-if="limit > 0 && extra > 0" class="more" @click="goAll">还有 {{ extra }} 个</text>
      <text v-else-if="limit > 0 && items.length > 1" class="more" @click="goAll">全部</text>
    </view>
    <view v-show="!collapsed" class="ongoing-body">
      <view v-for="item in shown" :key="item.id" class="card" @click="goItem(item)">
        <view class="top">
          <text class="name">{{ item.courseName }}</text>
          <text class="status">{{ TASK_STATUS_TEXT[item.status] || item.status }}</text>
        </view>
        <view class="meta">
          <text>{{ formatClockLabel(item) }} · </text><CampusHighlight :name="item.campusName" /><text>{{ item.building }} {{ item.classroom }}</text>
        </view>
        <view v-if="partyLine(item)" class="party">{{ partyLine(item) }}</view>
        <view v-if="countdownOf(item)" class="count">{{ countdownOf(item) }}</view>
        <view class="step">{{ stepOf(item).label }}</view>
      </view>
    </view>
  </view>
</template>

<style scoped>
.ongoing {
  padding: 0 16px 4px;
}
.head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  padding: 4px 2px 8px;
}
.title {
  font-size: 13px;
  color: var(--kean-muted);
  font-weight: 600;
}
.more {
  font-size: 12px;
  color: var(--kean-primary);
}
/* ---------- 首页专有：标题行可点 + 数量角标 + chevron ----------
 * 角标口径沿用首页既有的「数量胶囊」（.list-heading__count）与
 * design-tokens 的主色柔光底/主色深字（--kean-primary-soft / --kean-primary-ink），
 * 不引新色系；20px 高、胶囊圆角，与消息页未读徽标同一量级。 */
.head--tap {
  min-height: 34px;
  align-items: center;
  border-radius: 8px;
}
.head-main {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 6px;
}
.head-side {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  gap: 6px;
}
.badge {
  display: inline-flex;
  min-width: 20px;
  height: 20px;
  align-items: center;
  justify-content: center;
  box-sizing: border-box;
  padding: 0 6px;
  border-radius: 999px;
  background: var(--kean-primary-soft);
  color: var(--kean-primary-ink);
  font-size: 11px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  line-height: 20px;
  text-align: center;
}
.chevron {
  color: var(--kean-muted);
  font-size: 12px;
}
/* 壁纸/深色态：本组件是共用组件，只有标题行在首页可点；
   深色底上让主色柔光底提亮一档（与首页 .page.skinned 的浅色字同向）。
   特异性 0,3,0 > 上面 .badge 的 0,2,0，不依赖加载顺序，也不用 !important。 */
.page.skinned .badge {
  background: rgba(255, 255, 255, 0.13);
  color: #dbe8ff;
}
.page.skinned .chevron {
  color: #afc2dc;
}
/* 标题行与角标都不换行：窄屏（320px）下宁可挤，也不要角标被折成两行 */
.head--tap .title,
.badge,
.chevron {
  white-space: nowrap;
}
.card {
  background: var(--kean-card);
  border-radius: 12px;
  padding: 14px 16px;
  margin-bottom: 10px;
  border-left: 3px solid var(--kean-primary);
}
.name {
  font-size: 15px;
  font-weight: 600;
  color: var(--kean-text);
  flex: 1;
}
.top {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
}
.status {
  font-size: 12px;
  color: var(--kean-primary);
  flex-shrink: 0;
}
.meta {
  margin-top: 6px;
  color: var(--kean-sub);
  font-size: 12px;
}
.party {
  margin-top: 6px;
  color: var(--kean-text);
  font-size: 13px;
  font-weight: 600;
}
.count {
  margin-top: 6px;
  color: #f77234;
  font-size: 12px;
  font-weight: 600;
}
.step {
  margin-top: 8px;
  color: var(--kean-primary-active);
  font-size: 13px;
  font-weight: 600;
}
</style>
