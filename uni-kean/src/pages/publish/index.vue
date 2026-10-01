<script setup lang="ts">
import { fetchMe } from "@/api/auth";
import TaskForm from "@/components/TaskForm.vue";
import PageBackdrop from "@/components/PageBackdrop.vue";
import { usePageWallpaper } from "@/composables/usePageWallpaper";
import { useUserStore } from "@/store/user";
import { actionBlockReason } from "@/utils/format";
import { onShow } from "@dcloudio/uni-app";
import { computed, ref } from "vue";

const userStore = useUserStore();
const { wallpaperOn, wallpaperImage } = usePageWallpaper();
const loggedIn = ref(userStore.isLoggedIn.value);
const formKey = ref(0);
const publishBlock = computed(() => actionBlockReason(userStore.state.user, "publish"));

onShow(async () => {
  loggedIn.value = userStore.isLoggedIn.value;
  if (!loggedIn.value) {
    return;
  }
  try {
    const latest = await fetchMe();
    if (userStore.state.token) {
      userStore.setLogin(userStore.state.token, latest);
    }
  } catch {
    // 使用本地缓存
  }
});

function goLogin() {
  uni.navigateTo({ url: "/pages/auth/login?redirect=publish" });
}

function onSuccess(id: number) {
  // 仅在发布成功后重建表单：下次进入发布页是干净表单，而切 Tab / 返回时未提交内容得以保留
  formKey.value += 1;
  uni.navigateTo({ url: `/pages/task/detail?id=${id}` });
}

function onReset() {
  // 重建 TaskForm 即回到初始状态：默认值只在组件内 model 声明处定义一份，不会漏字段
  formKey.value += 1;
}
</script>

<template>
  <view class="page" :class="{ skinned: wallpaperOn }">
    <PageBackdrop :src="wallpaperImage" />
    <view v-if="!loggedIn" class="guest">
      <wd-status-tip image="content" tip="登录后才能发布代课" />
      <wd-button type="primary" @click="goLogin">去登录</wd-button>
    </view>
    <view v-else-if="publishBlock" class="guest">
      <wd-status-tip image="content" :tip="publishBlock" />
    </view>
    <TaskForm v-else :key="formKey" @success="onSuccess" @reset="onReset" />
    <wd-toast />
  </view>
</template>

<style scoped>
.page {
  position: relative;
  min-height: 100vh;
  background: #f5f6f8;
}
.page.skinned {
  background: transparent;
}
.guest {
  padding-top: 80px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
}
</style>
