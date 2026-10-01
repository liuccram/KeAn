const ENABLED_KEY = "kean_gesture_enabled";
const PATTERN_KEY = "kean_gesture_pattern";
const LOCKED_KEY = "kean_gesture_locked";

/**
 * 锁屏页是否已经打开。**内存态**，不持久化。
 *
 * 用途：App 的 onShow 里要判断是否需要上锁，而 Android 从后台快速切回时
 * getCurrentPages() 可能给出过期的页面栈 —— 没有这个守卫就会重复跳转，
 * 既会把用户正在画的手势重置掉，也容易让 WebView 白屏。
 */
let lockRouteActive = false;

export function beginLockRoute() {
  if (lockRouteActive) {
    return false;
  }
  lockRouteActive = true;
  return true;
}

export function endLockRoute() {
  lockRouteActive = false;
}

export function isGestureEnabled() {
  try {
    return uni.getStorageSync(ENABLED_KEY) === true || uni.getStorageSync(ENABLED_KEY) === "1";
  } catch {
    return false;
  }
}

export function getGesturePattern() {
  try {
    const raw = uni.getStorageSync(PATTERN_KEY);
    return typeof raw === "string" ? raw : "";
  } catch {
    return "";
  }
}

export function setGestureEnabled(enabled: boolean, pattern?: string) {
  uni.setStorageSync(ENABLED_KEY, enabled);
  if (enabled && pattern) {
    uni.setStorageSync(PATTERN_KEY, pattern);
    uni.setStorageSync(LOCKED_KEY, false);
    return;
  }
  if (!enabled) {
    uni.removeStorageSync(PATTERN_KEY);
    uni.setStorageSync(LOCKED_KEY, false);
  }
}

export function encodePattern(points: number[]) {
  return points.join("-");
}

export function isGestureLocked() {
  try {
    return uni.getStorageSync(LOCKED_KEY) === true || uni.getStorageSync(LOCKED_KEY) === "1";
  } catch {
    return false;
  }
}

export function markGestureLocked() {
  if (!isGestureEnabled() || !getGesturePattern()) {
    return;
  }
  uni.setStorageSync(LOCKED_KEY, true);
}

export function clearGestureLock() {
  uni.setStorageSync(LOCKED_KEY, false);
}

export function shouldShowGestureLock(loggedIn: boolean) {
  return Boolean(loggedIn && isGestureEnabled() && getGesturePattern() && isGestureLocked());
}
