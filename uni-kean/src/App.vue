<script setup lang="ts">
// 注意：App.vue 是应用入口，**不能编写视图元素 / 不能写模板**（uni-app 官方约束）。
// 全局性的界面（例如手势锁屏）请放到页面里 —— 见 pages/lock/index。
import { onHide, onLaunch, onShow } from "@dcloudio/uni-app";
import { heartbeat } from "@/api/auth";
import { useUserStore } from "@/store/user";
import {
  beginLockRoute,
  endLockRoute,
  markGestureLocked,
  shouldShowGestureLock
} from "@/utils/gesture";
import { refreshMessageBadge } from "@/utils/messageBadge";
import { applyDisplayAppearance } from "@/utils/prefs";
import { startRealtime, stopRealtime } from "@/utils/realtime";

const userStore = useUserStore();
const LOCK_PAGE = "/pages/lock/index";
let timer: ReturnType<typeof setInterval> | null = null;
/** 冷启动、以及每次从后台回到前台，都需要重新判断一次是否上锁 */
let needCheckLock = true;

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
 * 打开锁屏页。
 *
 * 用 navigateTo 而不是 reLaunch：reLaunch 会清空整个页面栈，在 Android 上「从后台快速
 * 切回」时容易撞上 WebView 的恢复过程而白屏；navigateTo 保留原页面，解锁后还能回到原来
 * 那一页。冷启动时页面栈可能还没就绪导致失败，重试两次，最后用 reLaunch 兜底。
 */
function enterLock(attempt = 0) {
  if (!beginLockRoute()) {
    return;
  }
  uni.navigateTo({
    url: LOCK_PAGE,
    fail: () => {
      endLockRoute();
      if (attempt < 2) {
        setTimeout(() => enterLock(attempt + 1), 300);
        return;
      }
      uni.reLaunch({ url: LOCK_PAGE, fail: () => undefined });
    }
  });
}

/**
 * 需要上锁就进入锁屏页。
 *
 * 只用了 App.vue 的应用生命周期（全平台有效），不依赖 App.vue 的模板 ——
 * 模板在 App / 小程序 上根本不会渲染，这正是之前「开了手势锁但锁屏不出现」的原因。
 */
function syncLock() {
  if (!needCheckLock) {
    return;
  }
  needCheckLock = false;
  try {
    if (!shouldShowGestureLock(userStore.isLoggedIn.value)) {
      return;
    }
    // 延后一点再跳，等应用真正恢复到前台，避免 Android 上出现白屏
    setTimeout(() => enterLock(), 300);
  } catch {
    // 判断过程出任何问题都不能让 onShow 抛错，否则会整页白屏
  }
}

onLaunch(() => {
  applyDisplayAppearance();
  needCheckLock = true;
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
  needCheckLock = true;
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
