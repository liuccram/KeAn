<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from "vue";

const props = defineProps<{
  siteKey: string;
  modelValue?: string;
  boxId?: string;
}>();

const emit = defineEmits<{
  (e: "update:modelValue", value: string): void;
}>();

const mountId = computed(() => props.boxId || "kean-turnstile-box");
let widgetId = "";
/** 未配置或加载失败：给出明确提示，并保持 token 为空，让后续提交被拦住而不是静默放行。 */
const failed = ref(false);

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
  emit("update:modelValue", token);
}

function openChallenge() {
  if (!props.siteKey) {
    // webview 路径同样不能静默失败：没有 siteKey 就跳过去只会看到空白页
    fail();
    return;
  }
  uni.navigateTo({
    url: `/pages/auth/turnstile?siteKey=${encodeURIComponent(props.siteKey)}`
  });
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
  }
);

onMounted(async () => {
  uni.$on("kean-turnstile", onToken);
  try {
    await loadScript();
    renderWidget();
    // #ifdef H5
    if (!window.turnstile) {
      fail();
    }
    // #endif
  } catch {
    fail();
  }
});

onUnmounted(() => {
  uni.$off("kean-turnstile", onToken);
  // #ifdef H5
  if (widgetId && window.turnstile) {
    window.turnstile.remove(widgetId);
  }
  // #endif
});

defineExpose({ reset });
</script>

<template>
  <view class="turnstile">
    <view v-if="failed" class="err">真人验证加载失败，请稍后重试</view>
    <!-- #ifdef H5 -->
    <view v-show="!failed" :id="mountId" class="widget" />
    <!-- #endif -->
    <!-- #ifndef H5 -->
    <wd-button v-if="!failed && !modelValue" type="info" plain block @click="openChallenge">点击完成真人验证</wd-button>
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
.err {
  color: #ef4444;
  font-size: 13px;
  padding: 4px 0 8px;
}
</style>
