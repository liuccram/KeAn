<script setup lang="ts">
import PageIntro from "@/components/PageIntro.vue";
function openDisplay() {
  uni.navigateTo({ url: "/pages/mine/display/index" });
}

function openPrivacy() {
  uni.navigateTo({ url: "/pages/mine/privacy" });
}
</script>

<template>
  <view class="page">
    <PageIntro eyebrow="PREFERENCES" title="更多设置" description="细节由你决定，体验更贴合习惯" marker="偏好" />
    <view class="more-group">
      <view class="more-heading">个性化</view>
      <wd-cell-group border>
      <wd-cell title="界面与显示" is-link @click="openDisplay" />
      </wd-cell-group>
    </view>
    <view class="gap more-group">
      <view class="more-heading">数据与隐私</view>
      <wd-cell-group border>
        <wd-cell title="隐私" is-link @click="openPrivacy" />
      </wd-cell-group>
    </view>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: var(--kean-bg);
}
.gap {
  margin-top: 14px;
}
.more-group { margin-right: 16px; margin-left: 16px; padding: 14px; border: 1px solid rgba(224,232,243,.9); border-radius: 16px; background: var(--kean-card); box-shadow: 0 5px 16px rgba(34,59,97,.035); }.more-heading { padding: 2px 2px 10px; color: var(--kean-muted); font-size: 11px; font-weight: 650; }.more-group :deep(.wd-cell-group) { overflow: hidden; border-radius: 11px; }
</style>
