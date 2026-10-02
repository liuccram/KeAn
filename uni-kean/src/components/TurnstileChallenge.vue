<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from "vue";
import { useToast } from "wot-design-uni";

const props = defineProps<{
  siteKey: string;
  modelValue?: string;
  boxId?: string;
}>();

const emit = defineEmits<{
  (e: "update:modelValue", value: string): void;
}>();

// 取消验证时沿用页面已有的「请完成真人验证」提示链路（页面里都挂了 <wd-toast />）
const toast = useToast();

const mountId = computed(() => props.boxId || "kean-turnstile-box");
let widgetId = "";
/** 未配置或加载失败：给出明确提示，并保持 token 为空，让后续提交被拦住而不是静默放行。 */
const failed = ref(false);
/** 非 H5：正在进入验证页 / 用户取消后没完成，用于给出可理解的当前状态。 */
const opening = ref(false);
const cancelled = ref(false);
const hint = computed(() => {
  if (opening.value) {
    return "正在进入真人验证…";
  }
  if (cancelled.value) {
    return "未完成真人验证，提交时将再次验证";
  }
  return "需要时自动进行真人验证";
});
/** 非 H5：跳转互斥、组件是否已销毁、自动进入验证的定时器、本组件是否发起过验证。 */
let navigating = false;
let disposed = false;
let autoTimer: ReturnType<typeof setTimeout> | null = null;
let cancelTimer: ReturnType<typeof setTimeout> | null = null;
let launched = false;

function fail() {
  failed.value = true;
  emit("update:modelValue", "");
}

function loadScript(): Promise<void> {
  // #ifdef H5
  if (window.turnstile) {
    return Promise.resolve();
  }
  return new Promise((resolve, reject) => {
    const existing = document.querySelector<HTMLScriptElement>("script[data-kean-turnstile]");
    if (existing) {
      existing.addEventListener("load", () => resolve(), { once: true });
      existing.addEventListener("error", () => reject(new Error("加载失败")), { once: true });
      return;
    }
    const script = document.createElement("script");
    script.src = "https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit";
    script.async = true;
    script.defer = true;
    script.dataset.keanTurnstile = "1";
    script.onload = () => resolve();
    script.onerror = () => reject(new Error("加载失败"));
    document.head.appendChild(script);
  });
  // #endif
  return Promise.resolve();
}

function renderWidget() {
  // #ifdef H5
  const el = document.getElementById(mountId.value);
  if (!el) {
    return;
  }
  if (!props.siteKey) {
    fail();
    return;
  }
  if (!window.turnstile) {
    // 脚本还没就绪（或加载失败）；加载失败由 onMounted 统一提示，这里不误报
    return;
  }
  failed.value = false;
  if (widgetId) {
    window.turnstile.remove(widgetId);
    widgetId = "";
  }
  el.innerHTML = "";
  widgetId = window.turnstile.render(el, {
    sitekey: props.siteKey,
    appearance: "always",
    size: "flexible",
    callback: (token: string) => emit("update:modelValue", token),
    "expired-callback": () => emit("update:modelValue", ""),
    "error-callback": () => emit("update:modelValue", "")
  });
  // #endif
}

function onToken(token: string) {
  launched = false;
  cancelled.value = false;
  opening.value = false;
  clearCancelTimer();
  emit("update:modelValue", token);
}

function clearAutoTimer() {
  if (autoTimer) {
    clearTimeout(autoTimer);
    autoTimer = null;
  }
}

function clearCancelTimer() {
  if (cancelTimer) {
    clearTimeout(cancelTimer);
    cancelTimer = null;
  }
}

/**
 * 进入真人验证。
 * - H5：控件已经内联渲染在表单里，不需要跳转，返回 false，让页面走原有的「请完成真人验证」提示。
 * - 非 H5：<web-view> 只能整页承载，所以跳到 pages/auth/turnstile；返回 true 表示已经发起验证。
 * retryOnce 只给"进入页面自动验证"用：转场还没结束就被拦下时可以重试一次，不把时序问题当成加载失败。
 */
function request(retryOnce = false): boolean {
  // #ifndef H5
  if (failed.value || !props.siteKey || props.modelValue || navigating) {
    return false;
  }
  navigating = true;
  launched = true;
  cancelled.value = false;
  opening.value = true;
  uni.navigateTo({
    url: `/pages/auth/turnstile?siteKey=${encodeURIComponent(props.siteKey)}`,
    fail: () => {
      if (retryOnce && !disposed) {
        clearAutoTimer();
        autoTimer = setTimeout(() => {
          autoTimer = null;
          request();
        }, 500);
        return;
      }
      // 跳转失败必须说清楚，不能让用户点了没反应
      fail();
    },
    complete: () => {
      navigating = false;
      opening.value = false;
    }
  });
  return true;
  // #endif
  // #ifdef H5
  return false;
  // #endif
}

/**
 * 非 H5：进入页面即自动把验证呈现出来（不再需要用户先点一个自绘按钮）。
 * 稍等一小会儿再进，避开进入页面时的转场，避免和页面跳转打架。
 */
function autoEnter() {
  clearAutoTimer();
  // #ifndef H5
  autoTimer = setTimeout(() => {
    autoTimer = null;
    if (disposed || !props.siteKey || props.modelValue || failed.value) {
      return;
    }
    request(true);
  }, 200);
  // #endif
}

/**
 * 用户返回但没完成验证：给出明确结果（沿用页面已有的「请完成真人验证」提示）。
 * 只有真正发起过验证、且还没拿到 token 的这个页面才提示，不静默失败，也不卡死
 * （下次提交 / 发送验证码时会再次自动进入验证）。
 */
function onCancel() {
  // #ifndef H5
  if (!launched || props.modelValue) {
    return;
  }
  cancelled.value = true;
  opening.value = false;
  // 稍等返回转场结束再提示，避免被 web-view 挡住；期间若 token 到了就不再提示
  clearCancelTimer();
  cancelTimer = setTimeout(() => {
    cancelTimer = null;
    if (!launched || props.modelValue) {
      return;
    }
    toast.error("请完成真人验证");
  }, 300);
  // #endif
}

function reset() {
  emit("update:modelValue", "");
  // #ifdef H5
  if (widgetId && window.turnstile) {
    window.turnstile.reset(widgetId);
    return;
  }
  renderWidget();
  // #endif
}

watch(
  () => props.siteKey,
  () => {
    renderWidget();
    // #ifndef H5
    if (!props.modelValue) {
      autoEnter();
    }
    // #endif
  }
);

onMounted(async () => {
  uni.$on("kean-turnstile", onToken);
  uni.$on("kean-turnstile-cancel", onCancel);
  try {
    await loadScript();
    renderWidget();
    // #ifdef H5
    if (!window.turnstile) {
      fail();
    }
    // #endif
    // #ifndef H5
    if (!failed.value) {
      autoEnter();
    }
    // #endif
  } catch {
    fail();
  }
});

onUnmounted(() => {
  disposed = true;
  launched = false;
  clearAutoTimer();
  clearCancelTimer();
  uni.$off("kean-turnstile", onToken);
  uni.$off("kean-turnstile-cancel", onCancel);
  // #ifdef H5
  if (widgetId && window.turnstile) {
    window.turnstile.remove(widgetId);
  }
  // #endif
});

defineExpose({ reset, request });
</script>

<template>
  <view class="turnstile">
    <view v-if="failed" class="err">真人验证加载失败，请稍后重试</view>
    <!-- #ifdef H5 -->
    <view v-show="!failed" :id="mountId" class="widget" />
    <!-- #endif -->
    <!-- #ifndef H5 -->
    <view v-if="!failed && !modelValue" class="hint">{{ hint }}</view>
    <view v-else-if="!failed" class="ok">已完成真人验证</view>
    <!-- #endif -->
  </view>
</template>

<style scoped>
.turnstile {
  padding: 12px 16px 0;
}
.widget {
  min-height: 65px;
  display: flex;
  justify-content: center;
}
.ok {
  text-align: center;
  color: #16a34a;
  font-size: 14px;
  padding: 8px 0;
}
.hint {
  text-align: center;
  color: var(--kean-muted);
  font-size: 13px;
  padding: 6px 0;
}
.err {
  color: #ef4444;
  font-size: 13px;
  padding: 4px 0 8px;
}
</style>
