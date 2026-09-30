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
  if (!el || !props.siteKey || !window.turnstile) {
    return;
  }
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
  } catch {
    emit("update:modelValue", "");
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
    <!-- #ifdef H5 -->
    <view :id="mountId" class="widget" />
    <!-- #endif -->
    <!-- #ifndef H5 -->
    <wd-button v-if="!modelValue" type="info" plain block @click="openChallenge">点击完成真人验证</wd-button>
    <view v-else class="ok">已完成真人验证</view>
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
</style>
