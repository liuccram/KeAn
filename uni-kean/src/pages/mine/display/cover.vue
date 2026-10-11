<script setup lang="ts">
import FallbackImage from "@/components/FallbackImage.vue";
import PageIntro from "@/components/PageIntro.vue";
import { useMineCover } from "@/composables/useMineCover";

const { heroSrc, isCustom, uploading, uploadLabel, chooseCover, resetCover } = useMineCover();
</script>

<template>
  <view class="page">
    <PageIntro eyebrow="PROFILE STYLING" title="我的页背景" description="为个人主页换一张更有校园感的封面" marker="封面" />
    <view class="preview-card">
      <view class="preview">
        <FallbackImage class="preview-img" :src="heroSrc" mode="aspectFill" />
        <view class="preview-mask">{{ isCustom ? "当前为自定义背景" : "当前为默认天空" }}</view>
      </view>
      <wd-cell-group border>
        <wd-cell title="从相册选择" is-link :clickable="!uploading" @click="chooseCover" />
        <wd-cell v-if="isCustom" title="恢复默认" is-link :clickable="!uploading" @click="resetCover" />
      </wd-cell-group>
    </view>
    <view class="tip">{{ uploading ? uploadLabel || "正在上传..." : "选图后可拖动、缩放，框出对外展示的部分。这张图只显示在「我的」页顶部。" }}</view>
    <wd-toast />
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: var(--kean-bg);
}
.preview-card { margin: 0 16px; padding: 10px; border:1px solid rgba(224,232,243,.9); border-radius:18px; background:var(--kean-card); box-shadow:0 8px 24px rgba(34,59,97,.045); }
.preview {
  margin: 0 0 10px;
  height: 168px;
  border-radius: 12px;
  overflow: hidden;
  position: relative;
  background: #d4e8ff;
}
.preview-card :deep(.wd-cell-group) { border-radius:12px; overflow:hidden; }
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
  color: var(--kean-card);
  background: linear-gradient(180deg, transparent, rgba(15, 23, 42, 0.45));
}
.tip {
  padding: 12px 16px;
  color: var(--kean-muted);
  font-size: 12px;
  line-height: 1.6;
}
</style>
