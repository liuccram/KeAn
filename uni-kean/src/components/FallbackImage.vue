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
 * 根节点刻意始终是一个 `<image>`：
 * - 调用方的 class / :style / @click 会原样落到图片上，既有尺寸与 object-fit 样式不用改
 * - `mode="widthFix"` 的图靠图片自身算高度，外面包一层 `<view>` 会让它塌成 0 高
 *
 * 失败时不再换成内联假图（此前那张 1×1 base64 PNG 就是屏幕上一片绿色/杂色的根因，已删掉）：
 * 把 src 置空，并打上失败态 class `is-failed`（Vue 会自动把它和调用方传的 class 合并），
 * 中性灰底由本组件的 scoped CSS 提供；最小高度只对 `mode="widthFix"` 追加 `is-failed-fix`，
 * 免得把 24px/64px/72px 这类写了固定尺寸的小头像、小图撑大。
 */
const props = withDefaults(defineProps<{
  src?: string | null;
  mode?: ImageMode;
}>(), {
  src: "",
  // 与原生 <image> 的默认值保持一致：调用方没写 mode 时行为不能变
  mode: "scaleToFill"
});

const failed = ref(false);

// src 变化时要重新尝试加载（列表复用、重试成功等），不能一直停在失败态上
watch(() => props.src, () => {
  failed.value = false;
});
</script>

<template>
  <image
    :src="failed || !src ? '' : src"
    :mode="mode"
    :class="{
      'is-failed': failed || !src,
      'is-failed-fix': (failed || !src) && mode === 'widthFix'
    }"
    @error="failed = true"
  />
</template>

<style scoped>
.is-failed {
  background-color: #f2f3f5;
}

/* 只有 widthFix 的图没有固有高度，失败时不定最小高度会看不见；
   其它 mode 的调用方都写了自己的尺寸，别去撑大它们（尤其是小头像）。 */
.is-failed-fix {
  min-height: 80px;
}
</style>
