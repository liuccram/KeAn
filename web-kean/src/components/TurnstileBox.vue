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
  if (!boxRef.value || !props.siteKey || !window.turnstile) {
    return;
  }
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
  } catch {
    emit("update:modelValue", "");
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
  <div class="turnstile-box">
    <div ref="boxRef" />
  </div>
</template>

<style scoped>
.turnstile-box {
  min-height: 65px;
  display: flex;
  justify-content: center;
}
</style>
