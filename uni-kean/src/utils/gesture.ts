const ENABLED_KEY = "kean_gesture_enabled";
const PATTERN_KEY = "kean_gesture_pattern";
const LOCKED_KEY = "kean_gesture_locked";

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
