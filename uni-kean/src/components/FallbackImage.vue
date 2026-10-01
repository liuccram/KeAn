<script setup lang="ts">
import { ref, watch } from "vue";

type ImageMode =
  | "scaleToFill" | "aspectFit" | "aspectFill" | "widthFix" | "heightFix"
  | "top" | "bottom" | "center" | "left" | "right"
  | "top left" | "top right" | "bottom left" | "bottom right";

/**
 * 带失败兜底的图片。全应用 24 个 `<image>` 里此前只有 1 个处理了 `@error`，
 * 图片挂掉就是一块空白，用户分不清「对方没上传」和「网络坏了」。
 *
 * 实现上刻意保持「根节点始终是一个 `<image>`」：
 * - 这样调用方的 class / style 会原样落到图片上，既有的尺寸与 object-fit 样式完全不用改
 * - 若改成外面包一层 `<view>`，所有 `mode="widthFix"` 的图片（高度由宽度算出）会塌成 0 高
 *
 * 失败时把 src 换成一张 1×1 的灰色 PNG（内联 base64，不需要额外资源文件），
 * 配合原有的 mode 就得到一个中性灰占位块；调用方也可以用 `fallback` 传自己的占位图。
 */
const GRAY_PIXEL =
  "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M/wHwAEAAH/q842iQAAAABJRU5ErkJggg==";

const props = withDefaults(defineProps<{
  src?: string | null;
  mode?: ImageMode;
  fallback?: string;
}>(), {
  src: "",
  // 与原生 <image> 的默认值保持一致：调用方没写 mode 时行为不能变
  mode: "scaleToFill",
  fallback: GRAY_PIXEL
});

const failed = ref(false);

// src 变化时要重新尝试加载（列表复用、重试成功等），不能一直停在占位图上
watch(() => props.src, () => {
  failed.value = false;
});
</script>

<template>
  <image :src="failed || !src ? fallback : src" :mode="mode" @error="failed = true" />
</template>
