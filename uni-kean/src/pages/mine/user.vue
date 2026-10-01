<script setup lang="ts">
import { openChat } from "@/api/chat";
import { getPublicProfile, type PublicProfile } from "@/api/user";
import FallbackImage from "@/components/FallbackImage.vue";
import ListState from "@/components/ListState.vue";
import { formatRoleRating, genderLabel, parseDateTime, starText, trustRoleLabel } from "@/utils/format";
import { goReport } from "@/utils/report";
import { resolveMediaUrl } from "@/utils/request";
import { onLoad } from "@dcloudio/uni-app";
import { useToast } from "wot-design-uni";
import { computed, ref } from "vue";

const toast = useToast();
const loading = ref(false);
const error = ref("");
const chatting = ref(false);
const profile = ref<PublicProfile | null>(null);
const userId = ref(0);

const avatarText = computed(() => (profile.value?.nickname || "同").slice(0, 1));
const schoolLine = computed(() => {
  if (!profile.value) {
    return "";
  }
  if (profile.value.limited) {
    return profile.value.schoolName || "已设置隐私账号";
  }
  const school = profile.value.schoolName || "未设置学校";
  return profile.value.campusName ? `${school} · ${profile.value.campusName}` : school;
});

async function load() {
  if (!userId.value) {
    return;
  }
  // 本次请求前有没有资料：没有资料时加载失败交给 ListState 展示原因和「重新加载」，
  // 已有资料时只用轻提示，避免刷新失败把整页顶掉
  const first = !profile.value;
  if (first) {
    loading.value = true;
  }
  error.value = "";
  try {
    profile.value = await getPublicProfile(userId.value);
    uni.setNavigationBarTitle({ title: profile.value.nickname || "同学主页" });
  } catch (err) {
    const message = (err as Error).message || "无法查看该主页";
    error.value = message;
    if (!first) {
      toast.error(message);
    }
  } finally {
    if (first) {
      loading.value = false;
    }
  }
}

async function startChat() {
  if (!userId.value || chatting.value || profile.value?.mine) {
    return;
  }
  chatting.value = true;
  try {
    const session = await openChat(userId.value);
    uni.navigateTo({ url: `/pages/message/chat?id=${session.id}` });
  } catch (error) {
    toast.error((error as Error).message || "无法发起私信");
  } finally {
    chatting.value = false;
  }
}

function handleReport() {
  if (profile.value?.mine || !userId.value) {
    return;
  }
  goReport("USER", userId.value, profile.value?.nickname);
}

onLoad((query) => {
  userId.value = Number(query?.id || 0);
  load();
});
</script>

<template>
  <view class="page">
    <ListState
      :loading="loading"
      :error="error"
      :empty="!profile"
      empty-text="没有找到该用户"
      @retry="load"
    >
      <view v-if="profile" class="card">
        <view class="hero">
          <FallbackImage v-if="profile.avatarUrl" class="avatar" :src="resolveMediaUrl(profile.avatarUrl)" mode="aspectFill" />
          <view v-else class="avatar text">{{ avatarText }}</view>
          <view class="meta">
            <view class="name">
              {{ profile.nickname }}
              <text v-if="!profile.limited && profile.gender" class="tag">{{ genderLabel(profile.gender) }}</text>
            </view>
            <view class="school">{{ schoolLine }}</view>
            <view v-if="!profile.limited" class="rate">
              发布 {{ formatRoleRating(profile.publishRatingAvg, profile.publishRatingCount) }}
              · 完成 {{ profile.publishCompletedCount || 0 }} 次
            </view>
            <view v-if="!profile.limited" class="rate">
              代课 {{ formatRoleRating(profile.applyRatingAvg, profile.applyRatingCount) }}
              · 完成 {{ profile.completedCount || 0 }} 次
            </view>
          </view>
        </view>
        <view v-if="profile.limited" class="lock">该账号已设为隐私账号，仅展示有限信息</view>
        <wd-button v-if="!profile.mine && !profile.limited" type="primary" block :loading="chatting" @click="startChat">
          发私信
        </wd-button>
        <wd-button v-else-if="!profile.mine" type="primary" plain block :loading="chatting" @click="startChat">
          发私信
        </wd-button>
        <wd-button v-if="!profile.mine" plain block @click="handleReport">举报该用户</wd-button>
      </view>

      <template v-if="profile && !profile.limited">
        <view class="block-title">收到的评价</view>
        <view v-if="profile.reviews?.length" class="reviews">
          <view v-for="item in profile.reviews" :key="item.id" class="review">
            <view class="review-top">
              <text class="from">{{ item.fromNickname || "同学" }}</text>
              <text class="stars">{{ starText(item.rating) }}</text>
            </view>
            <view class="course">{{ item.courseName }}<text v-if="trustRoleLabel(item.targetRole)"> · {{ trustRoleLabel(item.targetRole) }}</text></view>
            <view v-if="item.content" class="body">{{ item.content }}</view>
            <view class="time">{{ parseDateTime(item.createdAt) }}</view>
          </view>
        </view>
        <wd-status-tip v-else image="content" tip="暂无评价" />
      </template>
    </ListState>
    <wd-toast />
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: #f5f6f8;
  padding: 12px 16px 24px;
}
.card {
  background: #fff;
  border-radius: 12px;
  padding: 16px;
}
.hero {
  display: flex;
  gap: 12px;
  align-items: center;
}
.avatar {
  width: 64px;
  height: 64px;
  border-radius: 8px;
  background: #dbe7ff;
  flex-shrink: 0;
}
.avatar.text {
  display: flex;
  align-items: center;
  justify-content: center;
  color: #3d6fe8;
  font-size: 24px;
  font-weight: 600;
}
.meta {
  min-width: 0;
}
.name {
  font-size: 18px;
  font-weight: 600;
  color: #1d2129;
}
.tag {
  margin-left: 6px;
  font-size: 11px;
  font-weight: 400;
  color: #3d6fe8;
  background: #eef3ff;
  padding: 1px 6px;
  border-radius: 4px;
}
.school,
.rate {
  margin-top: 4px;
  color: #86909c;
  font-size: 13px;
}
.lock {
  margin: 12px 0;
  padding: 10px 12px;
  background: #f7f8fa;
  color: #86909c;
  font-size: 13px;
  border-radius: 8px;
}
.block-title {
  margin: 16px 4px 8px;
  color: #86909c;
  font-size: 13px;
}
.reviews {
  background: #fff;
  border-radius: 12px;
  overflow: hidden;
}
.review {
  padding: 12px 16px;
  border-bottom: 1px solid #f2f3f5;
}
.review-top {
  display: flex;
  justify-content: space-between;
}
.from {
  font-weight: 600;
  color: #1d2129;
}
.stars {
  color: #f7ba2a;
  font-size: 12px;
}
.course {
  margin-top: 4px;
  color: #86909c;
  font-size: 12px;
}
.body {
  margin-top: 6px;
  color: #4e5969;
  font-size: 14px;
  line-height: 1.5;
}
.time {
  margin-top: 6px;
  color: #c9cdd4;
  font-size: 11px;
}
</style>
