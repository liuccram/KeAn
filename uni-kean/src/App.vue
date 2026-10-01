<script setup lang="ts">
// 注意：App.vue 是应用入口，**不能编写视图元素 / 不能写模板**（uni-app 官方约束）。
// 全局性的界面请放到页面里。
import { onHide, onLaunch, onShow } from "@dcloudio/uni-app";
import { heartbeat } from "@/api/auth";
import { useUserStore } from "@/store/user";
import { refreshMessageBadge } from "@/utils/messageBadge";
import { applyDisplayAppearance } from "@/utils/prefs";
import { startRealtime, stopRealtime } from "@/utils/realtime";

const userStore = useUserStore();
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

onLaunch(() => {
  applyDisplayAppearance();
});

onShow(() => {
  refreshMessageBadge();
  startHeartbeat();
  applyDisplayAppearance();
});

onHide(() => {
  stopHeartbeat();
  stopRealtime();
});
</script>

<style>
@import "./styles/theme-vars.css";
@import "./styles/wot-theme.css";
@import "./styles/wallpaper-skin.css";
@import "./styles/display-appearance.css";
page {
  background-color: var(--kean-page-bg, #f5f6f8);
  font-size: var(--kean-fs, 16px);
  overflow-x: hidden;
}
</style>
