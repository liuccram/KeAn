<script setup lang="ts">
import { createReview } from "@/api/review";
import { getTask, type TaskItem } from "@/api/task";
import { useUserStore } from "@/store/user";
import { genderLabel, starText } from "@/utils/format";
import { onLoad } from "@dcloudio/uni-app";
import { useToast } from "wot-design-uni";
import { computed, ref } from "vue";

const toast = useToast();
const userStore = useUserStore();
const id = ref(0);
const loading = ref(true);
const submitting = ref(false);
const rating = ref(5);
const task = ref<TaskItem | null>(null);

const peer = computed(() => {
  if (!task.value) {
    return null;
  }
  return task.value.mine ? task.value.applicant : task.value.publisher;
});

const peerTitle = computed(() => (task.value?.mine ? "代课者" : "发布者"));
const rateHint = computed(() =>
  task.value?.mine
    ? "本次打星会计入对方的代课可信度，与发布可信度分开计算"
    : "本次打星会计入对方的发布可信度，与代课可信度分开计算"
);

const alreadyRated = computed(() => Boolean(task.value?.myReviewRating));

async function load() {
  if (!id.value) {
    return;
  }
  loading.value = true;
  try {
    task.value = await getTask(id.value);
    if (task.value.myReviewRating) {
      rating.value = task.value.myReviewRating;
    }
  } catch (error) {
    toast.error((error as Error).message || "加载失败");
  } finally {
    loading.value = false;
  }
}

async function handleSubmit() {
  if (!rating.value) {
    toast.error("请先打星");
    return;
  }
  submitting.value = true;
  try {
    await createReview({
      taskId: id.value,
      rating: rating.value
    });
    toast.success("已打星");
    await load();
  } catch (error) {
    toast.error((error as Error).message || "打星失败");
  } finally {
    submitting.value = false;
  }
}

onLoad((query) => {
  if (!userStore.isLoggedIn.value) {
    uni.redirectTo({ url: "/pages/auth/login" });
    return;
  }
  id.value = Number(query?.id || 0);
  if (!id.value) {
    toast.error("任务不存在");
    return;
  }
  load();
});
</script>

<template>
  <view class="page">
    <view v-if="task" class="card">
      <view class="course">{{ task.courseName }}</view>
      <view class="peer-name">{{ peerTitle }}：{{ peer?.nickname || task.matchedApplicantNickname || "同学" }}</view>
      <view v-if="peer" class="peer-meta">
        {{ genderLabel(peer.gender) }} · {{ peer.schoolName || "未设置学校" }}{{ peer.campusName ? ` · ${peer.campusName}` : "" }}
      </view>
      <view v-if="alreadyRated" class="done">
        <view class="stars">{{ starText(task.myReviewRating) }}</view>
        <view class="hint">已完成打星</view>
      </view>
      <template v-else>
        <wd-rate v-model="rating" size="28" />
        <view class="hint">{{ rateHint }}</view>
        <wd-button type="primary" block :loading="submitting" @click="handleSubmit">提交打星</wd-button>
      </template>
    </view>
    <wd-status-tip v-else-if="!loading" image="content" tip="任务不存在" />
    <wd-toast />
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: #f5f6f8;
  padding: 24px 16px;
}
.card {
  background: #fff;
  border-radius: 16px;
  padding: 28px 20px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
}
.course {
  font-size: 18px;
  font-weight: 600;
  color: #1d2129;
}
.peer-name {
  color: #4e5969;
  font-size: 14px;
}
.peer-meta {
  color: #86909c;
  font-size: 12px;
}
.done {
  text-align: center;
}
.stars {
  color: #f7ba2a;
  font-size: 22px;
  letter-spacing: 4px;
}
.hint {
  margin-top: 8px;
  color: #86909c;
  font-size: 12px;
  text-align: center;
  line-height: 1.5;
  padding: 0 8px;
}
</style>
