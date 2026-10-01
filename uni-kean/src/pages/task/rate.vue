<script setup lang="ts">
import { createReview, listReviewTags } from "@/api/review";
import { getTask, type TaskItem } from "@/api/task";
import ListState from "@/components/ListState.vue";
import { useUserStore } from "@/store/user";
import { genderLabel, starText } from "@/utils/format";
import { onLoad } from "@dcloudio/uni-app";
import { useToast } from "wot-design-uni";
import { computed, ref } from "vue";

// 与服务端 CreateReviewRequest 的约束保持一致：tags 最多 5 个、content 最多 500 字
const MAX_TAGS = 5;
const MAX_CONTENT = 500;

const toast = useToast();
const userStore = useUserStore();
const id = ref(0);
const loading = ref(true);
// 只有「任务不存在」以外的失败才算加载失败：不存在是真的查不到，走空态
const loadError = ref("");
const submitting = ref(false);
// 不再默认预选 5 星：不动就发出 5 星会把可信度冲成一片满分，这里是刻意要求用户主动选
const rating = ref(0);
const tags = ref<string[]>([]);
const availableTags = ref<string[]>([]);
const content = ref("");
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
    ? "本次评价会计入对方的代课可信度，与发布可信度分开计算"
    : "本次评价会计入对方的发布可信度，与代课可信度分开计算"
);

const alreadyRated = computed(() => Boolean(task.value?.myReviewRating));

async function loadTags() {
  if (availableTags.value.length) {
    return;
  }
  try {
    availableTags.value = await listReviewTags();
  } catch {
    // 标签是加分项，取不到就只填文字，不阻塞评价
  }
}

function toggleTag(tag: string) {
  if (tags.value.includes(tag)) {
    tags.value = tags.value.filter((item) => item !== tag);
    return;
  }
  if (tags.value.length >= MAX_TAGS) {
    toast.info(`最多选择 ${MAX_TAGS} 个标签`);
    return;
  }
  tags.value = tags.value.concat(tag);
}

async function load() {
  if (!id.value) {
    // 没有 id 不存在「加载中」可言，直接落到「任务不存在」空态
    loading.value = false;
    return;
  }
  loading.value = true;
  loadError.value = "";
  try {
    task.value = await getTask(id.value);
    if (task.value.myReviewRating) {
      rating.value = task.value.myReviewRating;
    } else {
      loadTags();
    }
  } catch (error) {
    const message = (error as Error).message || "加载失败";
    // 「不存在」是真的查不到，保留原空态；其它失败用 ListState 显示「加载失败 + 重新加载」
    if (!message.includes("不存在")) {
      loadError.value = message;
    }
    // 已经有内容时只用轻提示：这时若让失败态顶掉页面，比不提示更糟。
    if (task.value) {
      toast.error(message);
    }
  } finally {
    loading.value = false;
  }
}

async function handleSubmit() {
  if (!rating.value) {
    toast.error("请先选择评分");
    return;
  }
  submitting.value = true;
  try {
    await createReview({
      taskId: id.value,
      rating: rating.value,
      tags: tags.value.length ? tags.value : undefined,
      content: content.value.trim() || undefined
    });
    toast.success("评价已提交");
    await load();
  } catch (error) {
    toast.error((error as Error).message || "评价失败");
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
    // 没有 id 不进入加载中，直接显示「任务不存在」空态
    loading.value = false;
    toast.error("任务不存在");
    return;
  }
  load();
});
</script>

<template>
  <view class="page">
    <!-- 没有内容可显示时才由 ListState 接管：加载中 / 加载失败+重新加载 / 任务不存在 -->
    <ListState
      :loading="loading"
      :error="loadError"
      :empty="!task"
      empty-text="任务不存在"
      @retry="load"
    />
    <view v-if="task" class="card">
      <view class="course">{{ task.courseName }}</view>
      <view class="peer-name">{{ peerTitle }}：{{ peer?.nickname || task.matchedApplicantNickname || "同学" }}</view>
      <view v-if="peer" class="peer-meta">
        {{ genderLabel(peer.gender) }} · {{ peer.schoolName || "未设置学校" }}{{ peer.campusName ? ` · ${peer.campusName}` : "" }}
      </view>
      <view v-if="alreadyRated" class="done">
        <view class="stars">{{ starText(task.myReviewRating) }}</view>
        <view class="hint">已完成评价</view>
      </view>
      <template v-else>
        <wd-rate v-model="rating" size="28" />
        <view v-if="availableTags.length" class="tag-block">
          <view class="tag-title">补充标签（选填，最多 {{ MAX_TAGS }} 个）</view>
          <view class="tag-list">
            <text
              v-for="tag in availableTags"
              :key="tag"
              class="tag"
              :class="{ on: tags.includes(tag) }"
              @click="toggleTag(tag)"
            >{{ tag }}</text>
          </view>
        </view>
        <wd-textarea v-model="content" :maxlength="MAX_CONTENT" placeholder="补充说明（选填）" />
        <view class="hint">{{ rateHint }}</view>
        <wd-button type="primary" block :loading="submitting" @click="handleSubmit">提交评价</wd-button>
      </template>
    </view>
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
/* 标签与输入框要撑满卡片（卡片本身是 align-items: center） */
.tag-block,
.tag-list {
  width: 100%;
}
.tag-title {
  color: #86909c;
  font-size: 12px;
  margin-bottom: 8px;
}
.tag-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.tag {
  background: #f2f3f5;
  color: #4e5969;
  font-size: 12px;
  padding: 6px 12px;
  border-radius: 16px;
}
.tag.on {
  background: #e8f1ff;
  color: #3b82f6;
  font-weight: 600;
}
</style>
