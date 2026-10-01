<script setup lang="ts">
import { t } from "@/utils/i18n";
import { loadDisplayPrefs } from "@/utils/prefs";
import { computed } from "vue";

function goPassword() {
  uni.navigateTo({ url: "/pages/mine/password" });
}

function goDevices() {
  uni.navigateTo({ url: "/pages/mine/devices" });
}

const copy = computed(() => {
  const lang = loadDisplayPrefs().lang;
  return {
    password: t("changePassword", lang),
    devices: t("loginDevices", lang)
  };
});
</script>

<template>
  <view class="page">
    <wd-cell-group border>
      <wd-cell :title="copy.password" is-link @click="goPassword" />
      <wd-cell :title="copy.devices" is-link @click="goDevices" />
    </wd-cell-group>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: #f5f6f8;
  padding-top: 12px;
  padding-bottom: 32px;
}
</style>
