<script setup lang="ts">
import FallbackImage from "@/components/FallbackImage.vue";
import { useMineCover } from "@/composables/useMineCover";

const { heroSrc, isCustom, uploading, uploadLabel, chooseCover, resetCover } = useMineCover();
</script>

<template>
  <view class="page">
    <view class="preview">
      <FallbackImage class="preview-img" :src="heroSrc" mode="aspectFill" />
      <view class="preview-mask">{{ isCustom ? "当前为自定义背景" : "当前为默认天空" }}</view>
    </view>
    <wd-cell-group border>
      <wd-cell title="从相册选择" is-link :clickable="!uploading" @click="chooseCover" />
      <wd-cell v-if="isCustom" title="恢复默认" is-link :clickable="!uploading" @click="resetCover" />
    </wd-cell-group>
    <view class="tip">{{ uploading ? uploadLabel || "正在上传..." : "选图后可拖动、缩放，框出对外展示的部分。这张图只显示在「我的」页顶部。" }}</view>
    <wd-toast />
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: #f5f6f8;
  padding-top: 12px;
}
.preview {
  margin: 0 16px 16px;
  height: 168px;
  border-radius: 12px;
  overflow: hidden;
  position: relative;
  background: #d4e8ff;
}
.preview-img {
  width: 100%;
  height: 100%;
}
.preview-mask {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  padding: 8px 12px;
  font-size: 12px;
  color: #fff;
  background: linear-gradient(180deg, transparent, rgba(15, 23, 42, 0.45));
}
.tip {
  padding: 12px 16px;
  color: #86909c;
  font-size: 12px;
  line-height: 1.6;
}
</style>
