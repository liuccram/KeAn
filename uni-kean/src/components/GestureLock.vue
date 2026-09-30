<script setup lang="ts">
import GesturePad from "@/components/GesturePad.vue";
import { clearGestureLock, encodePattern, getGesturePattern } from "@/utils/gesture";
import { ref } from "vue";

const emit = defineEmits<{ unlocked: [] }>();
const tip = ref("请绘制解锁手势");
const error = ref("");

function onComplete(points: number[]) {
  const pattern = encodePattern(points);
  if (pattern === getGesturePattern()) {
    error.value = "";
    clearGestureLock();
    emit("unlocked");
    return;
  }
  error.value = "手势错误，请重试";
}
</script>

<template>
  <view class="lock">
    <view class="title">手势解锁</view>
    <view class="tip">{{ tip }}</view>
    <view v-if="error" class="err">{{ error }}</view>
    <GesturePad @complete="onComplete" />
  </view>
</template>

<style scoped>
.lock {
  position: fixed;
  inset: 0;
  z-index: 9999;
  background: #0b1224;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: #f4f7ff;
}
.title {
  font-size: 20px;
  font-weight: 700;
}
.tip,
.err {
  margin-top: 8px;
  font-size: 13px;
  color: rgba(235, 244, 255, 0.72);
}
.err {
  color: #ff8b8b;
}
</style>
