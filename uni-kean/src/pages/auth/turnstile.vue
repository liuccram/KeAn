<script setup lang="ts">
import { resolveApiBase } from "@/utils/apiBase";
import { onLoad } from "@dcloudio/uni-app";
import { ref } from "vue";

const src = ref("");

onLoad((query) => {
  const siteKey = String(query?.siteKey || "");
  const base = resolveApiBase().replace(/\/$/, "");
  src.value = `${base}/turnstile.html?sitekey=${encodeURIComponent(siteKey)}`;
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
      uni.$emit("kean-turnstile", token);
      break;
    }
  }
}
</script>

<template>
  <web-view v-if="src" :src="src" @message="onMessage" @onPostMessage="onMessage" />
</template>
