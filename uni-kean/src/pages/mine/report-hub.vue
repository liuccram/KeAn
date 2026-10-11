<script setup lang="ts">
import PageIntro from "@/components/PageIntro.vue";
function openReport() {
  uni.navigateTo({ url: "/pages/mine/feedback" });
}

function openAdvice() {
  uni.navigateTo({ url: "/pages/mine/advice" });
}
</script>

<template>
  <view class="page">
    <PageIntro eyebrow="COMMUNITY CARE" title="举报与反馈" description="让社区规则更清晰，让产品持续变好" marker="守护" />
    <view class="hub-group">
      <view class="hub-heading">需要哪种帮助？</view>
      <wd-cell-group border>
      <wd-cell title="举报" label="查看举报记录、处理结果，或对处理提出申诉" is-link @click="openReport" />
      <wd-cell title="反馈" label="向管理员反馈功能建议、故障或其他事宜" is-link @click="openAdvice" />
      </wd-cell-group>
    </view>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: var(--kean-bg);
}
.hub-group { margin: 12px 16px 0; padding: 14px; border: 1px solid rgba(224,232,243,.9); border-radius: 16px; background: var(--kean-card); box-shadow: 0 5px 16px rgba(34,59,97,.035); }.hub-heading { padding: 2px 2px 10px; color: var(--kean-muted); font-size: 11px; font-weight: 650; }.hub-group :deep(.wd-cell-group) { overflow: hidden; border-radius: 11px; }
</style>
