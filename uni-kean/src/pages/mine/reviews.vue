<script setup lang="ts">
import { listMyReviews, listPendingReviews, type ReviewItem, type ReviewPendingItem } from "@/api/review";
import FallbackImage from "@/components/FallbackImage.vue";
import ListState from "@/components/ListState.vue";
import { useUserStore } from "@/store/user";
import { parseDateTime, starText, trustRoleLabel } from "@/utils/format";
import { resolveMediaUrl } from "@/utils/request";
import { onShow } from "@dcloudio/uni-app";
import { useToast } from "wot-design-uni";
import { computed, ref } from "vue";

const toast = useToast();
const userStore = useUserStore();
const loading = ref(false);
const error = ref("");
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
  error.value = "";
  if (!userStore.isLoggedIn.value) {
    return;
  }
  const first = list.value.length === 0;
  if (first) {
    loading.value = true;
  }
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
  } catch (err) {
    // 分数仍用本地缓存兜底，但此前是静默顶替：用户会以为看到的是最新分数，
    // 所以失败必须可见（评分卡片上标注缓存 + 有列表时轻提示 + ListState 失败态）。
    const message = (err as Error).message || "加载失败";
    error.value = message;
    const me = userStore.state.user;
    publishRatingAvg.value = me?.publishRatingAvg ?? null;
    publishRatingCount.value = me?.publishRatingCount || 0;
    publishCompletedCount.value = me?.publishCompletedCount || 0;
    applyRatingAvg.value = me?.applyRatingAvg ?? null;
    applyRatingCount.value = me?.applyRatingCount || 0;
    applyCompletedCount.value = me?.completedCount || 0;
    // 已有评价列表时只用轻提示；没有内容时交给 ListState 显示失败原因和「重新加载」。
    if (!first) {
      toast.error(message);
    }
  } finally {
    if (first) {
      loading.value = false;
    }
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
      <view v-if="error" class="score-stale">评分加载失败，以下是本地缓存</view>
    </view>
    <view v-if="pending.length" class="list-title">待评价</view>
    <view v-if="pending.length" class="list">
      <view v-for="item in pending" :key="item.taskId" class="card pending" @click="goRate(item.taskId)">
        <view class="top">
          <text class="name">{{ item.courseName }}</text>
          <text class="go">去评价</text>
        </view>
        <view class="course">对方：{{ item.peerNickname }} · {{ pendingHint(item) }}</view>
      </view>
    </view>
    <!-- 「收到的评价」标题与空态解耦：待评价非空时也不能只剩一个空标题 -->
    <view class="list-title">收到的评价</view>
    <ListState
      :loading="loading"
      :error="error"
      :empty="list.length === 0"
      empty-text="暂无评价"
      @retry="load"
    >
      <view class="list">
        <view v-for="item in list" :key="item.id" class="card" @click="goTask(item.taskId)">
          <view class="top">
            <view class="who">
              <FallbackImage v-if="item.fromAvatarUrl" class="mini" :src="resolveMediaUrl(item.fromAvatarUrl)" mode="aspectFill" />
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
    </ListState>
    <wd-toast />
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: var(--kean-bg);
}
.score-card {
  background: var(--kean-card);
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
  border-left: 1px solid var(--kean-line);
}
.label {
  color: var(--kean-muted);
  font-size: 12px;
}
.score {
  font-size: 32px;
  font-weight: 700;
  color: var(--kean-text);
  margin-top: 6px;
}
.stars {
  margin-top: 8px;
  color: var(--kean-star);
  letter-spacing: 4px;
}
.from {
  margin-top: 8px;
  color: var(--kean-muted);
  font-size: 11px;
  padding: 0 8px;
  line-height: 1.4;
}
.score-stale {
  margin-top: 12px;
  text-align: center;
  color: #d48806;
  font-size: 12px;
}
.role {
  color: var(--kean-muted);
}
.list-title {
  padding: 8px 20px;
  color: var(--kean-muted);
  font-size: 13px;
}
.list {
  padding: 0 16px 24px;
}
.card {
  background: var(--kean-card);
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
  background: var(--kean-primary-soft);
}
.name {
  font-weight: 600;
  color: var(--kean-text);
}
.rate {
  color: var(--kean-star);
  font-size: 13px;
}
.go {
  color: var(--kean-primary);
  font-size: 13px;
}
.course {
  margin-top: 6px;
  color: var(--kean-primary);
  font-size: 12px;
}
.tags {
  margin-top: 8px;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.tag {
  background: var(--kean-line);
  color: var(--kean-sub);
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 10px;
}
.content {
  margin-top: 8px;
  color: var(--kean-sub);
  font-size: 13px;
  line-height: 1.5;
}
.time {
  margin-top: 8px;
  color: #c9cdd4;
  font-size: 12px;
}
</style>
