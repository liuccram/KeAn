<script setup lang="ts">
function openDisplay() {
  uni.navigateTo({ url: "/pages/mine/display/index" });
}

function openPrivacy() {
  uni.navigateTo({ url: "/pages/mine/privacy" });
}
</script>

<template>
  <view class="page">
    <wd-cell-group border>
      <wd-cell title="界面与显示" is-link @click="openDisplay" />
    </wd-cell-group>
    <view class="gap">
      <wd-cell-group border>
        <wd-cell title="隐私" is-link @click="openPrivacy" />
      </wd-cell-group>
    </view>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: #f5f6f8;
  padding-top: 12px;
}
.gap {
  margin-top: 12px;
}
</style>
