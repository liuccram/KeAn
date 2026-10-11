<script setup lang="ts">
import PageIntro from "@/components/PageIntro.vue";
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
  <view class="page settings-page">
    <PageIntro eyebrow="MAKE IT YOURS" title="设置" description="管理账号、安全与使用偏好" marker="设置" />
    <view class="settings-group">
      <view class="settings-heading">账号与偏好</view>
      <wd-cell-group border>
      <wd-cell title="账号与安全" is-link @click="uni.navigateTo({ url: '/pages/mine/security' })" />
      <wd-cell title="更多" is-link @click="uni.navigateTo({ url: '/pages/mine/more' })" />
      <wd-cell title="通知设置" is-link @click="comingSoon" />
      <wd-cell title="关于课安" is-link @click="uni.navigateTo({ url: '/pages/mine/about' })" />
      <wd-cell title="隐私政策" is-link @click="uni.navigateTo({ url: '/pages/mine/legal' })" />
      <wd-cell title="用户协议" is-link @click="comingSoon" />
      </wd-cell-group>
    </view>
    <view class="action">
      <wd-button type="error" plain size="large" block @click="handleLogout">退出登录</wd-button>
    </view>
    <wd-toast />
  </view>
</template>

<style scoped>
.page { min-height: 100vh; padding-bottom: 28px; background: var(--kean-bg); }
.settings-group { margin: 14px 16px 0; padding: 14px; border: 1px solid rgba(224,232,243,.9); border-radius: 17px; background: var(--kean-card); box-shadow: 0 5px 16px rgba(34,59,97,.035); }
.settings-heading { padding: 2px 2px 10px; color: var(--kean-muted); font-size: 11px; font-weight: 650; }
.settings-group :deep(.wd-cell-group) { overflow: hidden; border-radius: 11px; }
.action { padding: 24px 16px; }
</style>

<style scoped>
.page {
  min-height: 100vh;
  background: var(--kean-bg);
  padding-top: 12px;
}
.action {
  padding: 24px 16px;
}
</style>
