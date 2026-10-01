<script setup lang="ts">
import { logout } from "@/api/auth";
import { useUserStore } from "@/store/user";
import { refreshMessageBadge } from "@/utils/messageBadge";
import { useToast } from "wot-design-uni";

const toast = useToast();
const userStore = useUserStore();

function comingSoon() {
  toast.info("功能暂未开放");
}

async function handleLogout() {
  try {
    await logout();
  } catch {
    // 本地也退出
  }
  userStore.logoutLocal();
  refreshMessageBadge();
  toast.success("已退出");
  setTimeout(() => {
    uni.switchTab({ url: "/pages/mine/index" });
  }, 300);
}
</script>

<template>
  <view class="page">
    <wd-cell-group border>
      <wd-cell title="账号与安全" is-link @click="uni.navigateTo({ url: '/pages/mine/security' })" />
      <wd-cell title="更多" is-link @click="uni.navigateTo({ url: '/pages/mine/more' })" />
      <wd-cell title="通知设置" is-link @click="comingSoon" />
      <wd-cell title="关于课安" is-link @click="uni.navigateTo({ url: '/pages/mine/about' })" />
      <wd-cell title="隐私政策" is-link @click="uni.navigateTo({ url: '/pages/mine/legal' })" />
      <wd-cell title="用户协议" is-link @click="comingSoon" />
    </wd-cell-group>
    <view class="action">
      <wd-button type="error" plain size="large" block @click="handleLogout">退出登录</wd-button>
    </view>
    <wd-toast />
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: #f5f6f8;
  padding-top: 12px;
}
.action {
  padding: 24px 16px;
}
</style>
