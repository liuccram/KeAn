<script setup lang="ts">
withDefaults(defineProps<{
  eyebrow?: string;
  title: string;
  description?: string;
  marker?: string;
}>(), {
  eyebrow: "CAMPUS MUTUAL HELP",
  description: "",
  marker: "课安"
});
</script>

<template>
  <view class="page-intro">
    <view class="page-intro__copy">
      <text class="page-intro__eyebrow">{{ eyebrow }}</text>
      <text class="page-intro__title">{{ title }}</text>
      <text v-if="description" class="page-intro__description">{{ description }}</text>
    </view>
    <view class="page-intro__mark">{{ marker }}</view>
    <view class="page-intro__orb" />
  </view>
</template>

<style scoped>
.page-intro {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 104px;
  margin: 12px 16px 16px;
  padding: 18px 18px;
  overflow: hidden;
  border: 1px solid rgba(180, 202, 235, 0.46);
  border-radius: 20px;
  background: linear-gradient(128deg, #edf4ff 0%, #f8faff 60%, #eaf2ff 100%);
}
.page-intro__copy { position: relative; z-index: 1; display: flex; flex: 1; flex-direction: column; min-width: 0; }
.page-intro__eyebrow { color: #6d8fbe; font-size: 9px; font-weight: 700; letter-spacing: 1.25px; }
.page-intro__title { margin-top: 7px; color: #203858; font-size: 20px; font-weight: 750; line-height: 1.35; }
.page-intro__description { margin-top: 5px; color: #8392a8; font-size: 11px; line-height: 1.5; }
.page-intro__mark { position: relative; z-index: 1; display: flex; flex-shrink: 0; align-items: center; justify-content: center; width: 43px; height: 43px; border: 1px solid rgba(255,255,255,.9); border-radius: 15px; color: #5c83ba; background: rgba(255,255,255,.72); box-shadow: 0 6px 15px rgba(58,92,143,.08); font-size: 11px; font-weight: 700; }
.page-intro__orb { position: absolute; top: -68px; right: -38px; width: 145px; height: 145px; border: 1px solid rgba(127,169,222,.18); border-radius: 50%; }
</style>
