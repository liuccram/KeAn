<script setup lang="ts">
import { APPLICATION_STATUS_TEXT } from "@/api/application";
import { listMyApplied, TASK_STATUS_TEXT, type TaskItem } from "@/api/task";
import { formatReward } from "@/utils/format";
import { useLiveUpdates } from "@/composables/useLiveUpdates";
import { onShow } from "@dcloudio/uni-app";
import { useToast } from "wot-design-uni";
import { ref } from "vue";

const toast = useToast();
const list = ref<TaskItem[]>([]);
const loading = ref(false);

async function load() {
  const first = list.value.length === 0;
  if (first) {
    loading.value = true;
  }
  try {
    const data = await listMyApplied();
    list.value = data.list;
  } catch (error) {
    toast.error((error as Error).message || "加载失败");
  } finally {
    if (first) {
      loading.value = false;
    }
  }
}

function goDetail(id: number) {
  uni.navigateTo({ url: `/pages/task/detail?id=${id}` });
}

function applyText(item: TaskItem) {
  return APPLICATION_STATUS_TEXT[item.myApplicationStatus || ""] || item.myApplicationStatus || "已申请";
}

onShow(() => {
  load();
});

useLiveUpdates((event) => {
  if (!event || event.type === "NOTICE") {
    load();
  }
});
</script>

<template>
  <view class="page">
    <view v-if="list.length" class="list">
      <view v-for="item in list" :key="item.id" class="card" @click="goDetail(item.id)">
        <view class="top">
          <text class="name">{{ item.courseName }}</text>
          <text class="status">{{ TASK_STATUS_TEXT[item.status] || item.status }}</text>
        </view>
        <view class="meta">申请状态：{{ applyText(item) }}</view>
        <view class="meta">{{ item.taskDate }} {{ item.startTime }}-{{ item.endTime }}</view>
        <view class="meta">{{ formatReward(item.reward) }}</view>
      </view>
    </view>
    <wd-status-tip v-else-if="!loading" image="content" tip="还没有申请过代课" />
    <wd-toast />
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: #f5f6f8;
}
.list {
  padding: 12px 16px;
}
.card {
  background: #fff;
  border-radius: 12px;
  padding: 16px;
  margin-bottom: 12px;
}
.top {
  display: flex;
  justify-content: space-between;
}
.name {
  font-weight: 600;
}
.status {
  color: #4d80f0;
  font-size: 12px;
}
.meta {
  margin-top: 8px;
  color: #4e5969;
  font-size: 13px;
}
</style>
