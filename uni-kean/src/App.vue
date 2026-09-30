<script setup lang="ts">
import { onHide, onLaunch, onShow } from "@dcloudio/uni-app";
import { heartbeat } from "@/api/auth";
import GestureLock from "@/components/GestureLock.vue";
import { useUserStore } from "@/store/user";
import { markGestureLocked, shouldShowGestureLock } from "@/utils/gesture";
import { refreshMessageBadge } from "@/utils/messageBadge";
import { applyDisplayAppearance } from "@/utils/prefs";
import { startRealtime, stopRealtime } from "@/utils/realtime";
import { ref } from "vue";

const userStore = useUserStore();
const showLock = ref(false);
let timer: ReturnType<typeof setInterval> | null = null;

function ping() {
  if (!userStore.isLoggedIn.value) {
    stopRealtime();
    return;
  }
  startRealtime();
  heartbeat().catch(() => undefined);
}

function startHeartbeat() {
  ping();
  if (timer) {
    return;
  }
  timer = setInterval(ping, 10000);
}

function stopHeartbeat() {
  if (timer) {
    clearInterval(timer);
    timer = null;
  }
}

function syncLock() {
  showLock.value = shouldShowGestureLock(userStore.isLoggedIn.value);
}

onLaunch(() => {
  applyDisplayAppearance();
});
onShow(() => {
  refreshMessageBadge();
  startHeartbeat();
  applyDisplayAppearance();
  syncLock();
});
onHide(() => {
  stopHeartbeat();
  stopRealtime();
  if (userStore.isLoggedIn.value) {
    markGestureLocked();
  }
});
</script>

<template>
  <GestureLock v-if="showLock" @unlocked="showLock = false" />
</template>

<style>
@import "./styles/wot-theme.css";
@import "./styles/wallpaper-skin.css";
@import "./styles/display-appearance.css";
page {
  background-color: var(--kean-page-bg, #f5f6f8);
  font-size: var(--kean-fs, 16px);
}
</style>
