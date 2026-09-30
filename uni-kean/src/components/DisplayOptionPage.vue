<script setup lang="ts">
defineProps<{
  kicker: string;
  heading: string;
  body: string;
  theme: "light" | "dark";
  fontSize: "s" | "m" | "l";
  options: { value: string; label: string }[];
  modelValue: string;
}>();

const emit = defineEmits<{
  "update:modelValue": [value: string];
}>();
</script>

<template>
  <view class="page" :class="[`theme-${theme}`, `font-${fontSize}`]">
    <view class="preview">
      <view class="kicker">{{ kicker }}</view>
      <view class="heading">{{ heading }}</view>
      <view class="body">{{ body }}</view>
    </view>
    <view class="block">
      <view
        v-for="item in options"
        :key="item.value"
        class="row"
        :class="{ on: modelValue === item.value }"
        @click="emit('update:modelValue', item.value)"
      >
        <text class="name">{{ item.label }}</text>
        <text v-if="modelValue === item.value" class="mark">✓</text>
      </view>
    </view>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: 16px;
  background: #f5f6f8;
  color: #1d2129;
  --preview: #4d80f0;
  --card: #fff;
  --line: #f2f3f5;
  --on: #4d80f0;
  --on-bg: #eef3ff;
}
.page.theme-dark {
  background: #111113;
  color: #f2f3f5;
  --preview: #3b82f6;
  --card: #1c1c1e;
  --line: #2c2c2e;
  --on: #60a5fa;
  --on-bg: #1e3a5f;
}
.page.font-s {
  font-size: 13px;
}
.page.font-m {
  font-size: 15px;
}
.page.font-l {
  font-size: 17px;
}
.preview {
  background: var(--preview);
  color: #fff;
  border-radius: 16px;
  padding: 20px 18px;
  margin-bottom: 16px;
}
.kicker {
  opacity: 0.85;
  font-size: 0.8em;
}
.heading {
  margin-top: 8px;
  font-size: 1.35em;
  font-weight: 700;
}
.body {
  margin-top: 8px;
  line-height: 1.6;
  opacity: 0.92;
  font-size: 0.95em;
}
.block {
  background: var(--card);
  border-radius: 14px;
  overflow: hidden;
}
.row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 16px;
  border-bottom: 1px solid var(--line);
  font-size: 1em;
}
.row:last-child {
  border-bottom: none;
}
.row.on {
  background: var(--on-bg);
  color: var(--on);
  font-weight: 600;
}
.mark {
  font-size: 1.05em;
}
</style>
