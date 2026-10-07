<script setup lang="ts">
import { TASK_STATUS_TEXT, type TaskItem } from "@/api/task";
import CampusHighlight from "@/components/CampusHighlight.vue";
import { useNowTick } from "@/composables/useNowTick";
import { classCountdown, formatClockLabel, nextTaskStep, taskDetailUrl } from "@/utils/taskAction";
import { computed } from "vue";

const props = withDefaults(defineProps<{
  items: TaskItem[];
  limit?: number;
}>(), {
  limit: 0
});

const now = useNowTick();
const shown = computed(() => (props.limit > 0 ? props.items.slice(0, props.limit) : props.items));
const extra = computed(() => Math.max(0, props.items.length - shown.value.length));

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
    <view class="head">
      <text class="title">进行中的代课</text>
      <text v-if="limit > 0 && extra > 0" class="more" @click="goAll">还有 {{ extra }} 个</text>
      <text v-else-if="limit > 0 && items.length > 1" class="more" @click="goAll">全部</text>
    </view>
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
