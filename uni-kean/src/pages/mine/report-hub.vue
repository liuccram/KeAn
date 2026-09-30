<script setup lang="ts">
function openReport() {
  uni.navigateTo({ url: "/pages/mine/feedback" });
}

function openAdvice() {
  uni.navigateTo({ url: "/pages/mine/advice" });
}
</script>

<template>
  <view class="page">
    <wd-cell-group border>
      <wd-cell title="举报" label="查看举报记录、处理结果，或对处理提出申诉" is-link @click="openReport" />
      <wd-cell title="反馈" label="向管理员反馈功能建议、故障或其他事宜" is-link @click="openAdvice" />
    </wd-cell-group>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: #f5f6f8;
  padding-top: 12px;
}
</style>
