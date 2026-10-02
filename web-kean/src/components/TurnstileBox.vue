<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from "vue";

const props = defineProps<{
  siteKey: string;
  modelValue?: string;
}>();

const emit = defineEmits<{
  (e: "update:modelValue", value: string): void;
}>();

const boxRef = ref<HTMLElement | null>(null);
let widgetId = "";
let scriptEl: HTMLScriptElement | null = null;
/** 未配置或加载失败：给出明确提示，并保持 token 为空，让后续提交被拦住而不是静默空白。 */
const failed = ref(false);

function fail() {
  failed.value = true;
  emit("update:modelValue", "");
}

function loadScript() {
  if (window.turnstile) {
    return Promise.resolve();
  }
  return new Promise<void>((resolve, reject) => {
    const existing = document.querySelector<HTMLScriptElement>("script[data-kean-turnstile]");
    if (existing) {
      existing.addEventListener("load", () => resolve(), { once: true });
      existing.addEventListener("error", () => reject(new Error("加载失败")), { once: true });
      return;
    }
    scriptEl = document.createElement("script");
    scriptEl.src = "https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit";
    scriptEl.async = true;
    scriptEl.defer = true;
    scriptEl.dataset.keanTurnstile = "1";
    scriptEl.onload = () => resolve();
    scriptEl.onerror = () => reject(new Error("加载失败"));
    document.head.appendChild(scriptEl);
  });
}

function renderWidget() {
  if (!boxRef.value) {
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
  boxRef.value.innerHTML = "";
  widgetId = window.turnstile.render(boxRef.value, {
    sitekey: props.siteKey,
    appearance: "always",
    callback: (token: string) => emit("update:modelValue", token),
    "expired-callback": () => emit("update:modelValue", ""),
    "error-callback": () => emit("update:modelValue", "")
  });
}

function reset() {
  emit("update:modelValue", "");
  if (widgetId && window.turnstile) {
    window.turnstile.reset(widgetId);
    return;
  }
  renderWidget();
}

watch(
  () => props.siteKey,
  () => {
    renderWidget();
  }
);

onMounted(async () => {
  try {
    await loadScript();
    renderWidget();
    if (!window.turnstile) {
      fail();
    }
  } catch {
    fail();
  }
});

onBeforeUnmount(() => {
  if (widgetId && window.turnstile) {
    window.turnstile.remove(widgetId);
  }
});

defineExpose({ reset });
</script>

<template>
  <div>
    <p v-if="failed" class="turnstile-error">真人验证加载失败，请稍后重试</p>
    <div v-show="!failed" ref="boxRef" class="turnstile-box" />
  </div>
</template>

<style scoped>
.turnstile-box {
  min-height: 65px;
  display: flex;
  justify-content: center;
}
.turnstile-error {
  margin: 0 0 12px;
  color: #ef4444;
  font-size: 13px;
}
</style>
