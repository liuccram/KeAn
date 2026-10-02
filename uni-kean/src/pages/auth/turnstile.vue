<script setup lang="ts">
import { resolveApiBase } from "@/utils/apiBase";
import { onLoad, onUnload } from "@dcloudio/uni-app";
import { ref } from "vue";

const src = ref("");
/** 是否已经拿到 token：用来区分"验证完成自动返回"和"用户自己返回（取消）"。 */
let done = false;

onLoad((query) => {
  const siteKey = String(query?.siteKey || "");
  const base = resolveApiBase().replace(/\/$/, "");
  src.value = `${base}/turnstile.html?sitekey=${encodeURIComponent(siteKey)}`;
});

onUnload(() => {
  if (done) {
    return;
  }
  // 用户返回但没完成验证：通知发起方给出明确结果（提示由发起方处理，这里只发信号）
  uni.$emit("kean-turnstile-cancel");
});

function pickToken(raw: unknown): string {
  if (!raw) {
    return "";
  }
  if (typeof raw === "string") {
    return raw;
  }
  const item = raw as { token?: string; data?: { token?: string } };
  return item.token || item.data?.token || "";
}

function onMessage(event: { detail?: { data?: unknown } }) {
  const payload = event?.detail?.data;
  const list = Array.isArray(payload) ? payload : [payload];
  for (let i = list.length - 1; i >= 0; i -= 1) {
    const token = pickToken(list[i]);
    if (token) {
      done = true;
      uni.$emit("kean-turnstile", token);
      break;
    }
  }
}
</script>

<template>
  <web-view v-if="src" :src="src" @message="onMessage" @onPostMessage="onMessage" />
</template>
