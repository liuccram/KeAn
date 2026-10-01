<script setup lang="ts">
// 注意：App.vue 是应用入口，**不能编写视图元素 / 不能写模板**（uni-app 官方约束）。
// 全局性的界面（例如手势锁屏）请放到页面里 —— 见 pages/lock/index。
import { onHide, onLaunch, onShow } from "@dcloudio/uni-app";
import { heartbeat } from "@/api/auth";
import { useUserStore } from "@/store/user";
import { markGestureLocked, shouldShowGestureLock } from "@/utils/gesture";
import { refreshMessageBadge } from "@/utils/messageBadge";
import { applyDisplayAppearance } from "@/utils/prefs";
import { startRealtime, stopRealtime } from "@/utils/realtime";

const userStore = useUserStore();
const LOCK_PAGE = "/pages/lock/index";
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

/**
 * 需要上锁就跳到锁屏页。
 *
 * 这里只用了 App.vue 的应用生命周期（全平台有效），不再依赖 App.vue 的模板 ——
 * 模板在 App / 小程序 上根本不会渲染，这正是之前「开了手势锁但锁屏不出现」的原因。
 * 已在锁屏页时不重复跳转，避免把锁屏页重置掉。
 */
function syncLock() {
  if (!shouldShowGestureLock(userStore.isLoggedIn.value)) {
    return;
  }
  const pages = getCurrentPages();
  const current = pages.length ? `/${pages[pages.length - 1].route}` : "";
  if (current === LOCK_PAGE) {
    return;
  }
  // 延迟一拍再跳：冷启动时页面栈可能还没就绪
  setTimeout(() => {
    uni.reLaunch({ url: LOCK_PAGE });
  }, 0);
}

onLaunch(() => {
  applyDisplayAppearance();
  // 冷启动也要求手势：进程若是在前台被强杀，onHide 不会触发，标记可能没写上。
  // markGestureLocked 内部自带「已开启且有手势」的判断，未开启时是空操作。
  markGestureLocked();
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

<style>
@import "./styles/wot-theme.css";
@import "./styles/wallpaper-skin.css";
@import "./styles/display-appearance.css";
page {
  background-color: var(--kean-page-bg, #f5f6f8);
  font-size: var(--kean-fs, 16px);
  overflow-x: hidden;
}
</style>
