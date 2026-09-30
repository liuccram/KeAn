<script setup lang="ts">
import { listMyReviews, listPendingReviews, type ReviewItem, type ReviewPendingItem } from "@/api/review";
import { useUserStore } from "@/store/user";
import { parseDateTime, starText, trustRoleLabel } from "@/utils/format";
import { resolveMediaUrl } from "@/utils/request";
import { onShow } from "@dcloudio/uni-app";
import { computed, ref } from "vue";

const userStore = useUserStore();
const loading = ref(false);
const publishRatingAvg = ref<number | null>(null);
const publishRatingCount = ref(0);
const publishCompletedCount = ref(0);
const applyRatingAvg = ref<number | null>(null);
const applyRatingCount = ref(0);
const applyCompletedCount = ref(0);
const list = ref<ReviewItem[]>([]);
const pending = ref<ReviewPendingItem[]>([]);

function scoreOf(avg: number | null, count: number) {
  if (!count || avg == null) {
    return "暂无";
  }
  return Number(avg).toFixed(1);
}

const publishScore = computed(() => scoreOf(publishRatingAvg.value, publishRatingCount.value));
const applyScore = computed(() => scoreOf(applyRatingAvg.value, applyRatingCount.value));

function pendingHint(item: ReviewPendingItem) {
  return item.role === "PUBLISHER" ? "计入对方发布可信度" : "计入对方代课可信度";
}

async function load() {
  if (!userStore.isLoggedIn.value) {
    return;
  }
  loading.value = true;
  try {
    const [data, wait] = await Promise.all([listMyReviews(1, 50), listPendingReviews()]);
    publishRatingAvg.value = data.publishRatingAvg ?? null;
    publishRatingCount.value = data.publishRatingCount || 0;
    publishCompletedCount.value = data.publishCompletedCount || 0;
    applyRatingAvg.value = data.applyRatingAvg ?? null;
    applyRatingCount.value = data.applyRatingCount || 0;
    applyCompletedCount.value = data.applyCompletedCount || 0;
    list.value = data.page?.list || [];
    pending.value = wait || [];
  } catch {
    const me = userStore.state.user;
    publishRatingAvg.value = me?.publishRatingAvg ?? null;
    publishRatingCount.value = me?.publishRatingCount || 0;
    publishCompletedCount.value = me?.publishCompletedCount || 0;
    applyRatingAvg.value = me?.applyRatingAvg ?? null;
    applyRatingCount.value = me?.applyRatingCount || 0;
    applyCompletedCount.value = me?.completedCount || 0;
  } finally {
    loading.value = false;
  }
}

function goTask(id: number) {
  uni.navigateTo({ url: `/pages/task/detail?id=${id}` });
}

function goRate(id: number) {
  uni.navigateTo({ url: `/pages/task/rate?id=${id}` });
}

onShow(() => {
  load();
});
</script>

<template>
  <view class="page">
    <view class="score-card">
      <view class="score-row">
        <view class="score-col">
          <view class="label">发布可信度</view>
          <view class="score">{{ publishScore }}</view>
          <view class="stars">{{ starText(publishRatingAvg) }}</view>
          <view class="from">{{ publishRatingCount }} 次评价 · 发布完成 {{ publishCompletedCount }}</view>
        </view>
        <view class="score-col">
          <view class="label">代课可信度</view>
          <view class="score">{{ applyScore }}</view>
          <view class="stars">{{ starText(applyRatingAvg) }}</view>
          <view class="from">{{ applyRatingCount }} 次评价 · 代课完成 {{ applyCompletedCount }}</view>
        </view>
      </view>
    </view>
    <view v-if="pending.length" class="list-title">待评价</view>
    <view v-if="pending.length" class="list">
      <view v-for="item in pending" :key="item.taskId" class="card pending" @click="goRate(item.taskId)">
        <view class="top">
          <text class="name">{{ item.courseName }}</text>
          <text class="go">去打星</text>
        </view>
        <view class="course">对方：{{ item.peerNickname }} · {{ pendingHint(item) }}</view>
      </view>
    </view>
    <view class="list-title">收到的评价</view>
    <view v-if="list.length" class="list">
      <view v-for="item in list" :key="item.id" class="card" @click="goTask(item.taskId)">
        <view class="top">
          <view class="who">
            <image v-if="item.fromAvatarUrl" class="mini" :src="resolveMediaUrl(item.fromAvatarUrl)" mode="aspectFill" />
            <text class="name">{{ item.fromNickname }}</text>
          </view>
          <text class="rate">{{ starText(item.rating) }}</text>
        </view>
        <view class="course">{{ item.courseName }}<text v-if="trustRoleLabel(item.targetRole)" class="role"> · {{ trustRoleLabel(item.targetRole) }}</text></view>
        <view v-if="item.tags?.length" class="tags">
          <text v-for="tag in item.tags" :key="tag" class="tag">{{ tag }}</text>
        </view>
        <view v-if="item.content" class="content">{{ item.content }}</view>
        <view class="time">{{ parseDateTime(item.createdAt) }}</view>
      </view>
    </view>
    <wd-status-tip v-else-if="!loading && !pending.length" image="content" tip="暂无评价" />
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: #f5f6f8;
}
.score-card {
  background: #fff;
  margin: 12px 16px;
  border-radius: 16px;
  padding: 20px 8px;
}
.score-row {
  display: flex;
}
.score-col {
  flex: 1;
  text-align: center;
}
.score-col + .score-col {
  border-left: 1px solid #f2f3f5;
}
.label {
  color: #86909c;
  font-size: 12px;
}
.score {
  font-size: 32px;
  font-weight: 700;
  color: #1d2129;
  margin-top: 6px;
}
.stars {
  margin-top: 8px;
  color: #f7ba2a;
  letter-spacing: 4px;
}
.from {
  margin-top: 8px;
  color: #86909c;
  font-size: 11px;
  padding: 0 8px;
  line-height: 1.4;
}
.role {
  color: #86909c;
}
.list-title {
  padding: 8px 20px;
  color: #86909c;
  font-size: 13px;
}
.list {
  padding: 0 16px 24px;
}
.card {
  background: #fff;
  border-radius: 12px;
  padding: 14px 16px;
  margin-bottom: 12px;
}
.card.pending {
  background: #f3f7ff;
}
.top {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.who {
  display: flex;
  align-items: center;
  gap: 8px;
}
.mini {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: #dbe7ff;
}
.name {
  font-weight: 600;
  color: #1d2129;
}
.rate {
  color: #f7ba2a;
  font-size: 13px;
}
.go {
  color: #4d80f0;
  font-size: 13px;
}
.course {
  margin-top: 6px;
  color: #4d80f0;
  font-size: 12px;
}
.tags {
  margin-top: 8px;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.tag {
  background: #f2f3f5;
  color: #4e5969;
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 10px;
}
.content {
  margin-top: 8px;
  color: #4e5969;
  font-size: 13px;
  line-height: 1.5;
}
.time {
  margin-top: 8px;
  color: #c9cdd4;
  font-size: 12px;
}
</style>
