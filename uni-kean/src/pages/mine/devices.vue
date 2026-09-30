<script setup lang="ts">
import { kickLoginDevice, listLoginDevices, type LoginDevice } from "@/api/device";
import { t } from "@/utils/i18n";
import { loadDisplayPrefs } from "@/utils/prefs";
import { onShow } from "@dcloudio/uni-app";
import { computed, ref } from "vue";
import { useToast } from "wot-design-uni";

const toast = useToast();
const devices = ref<LoginDevice[]>([]);
const loading = ref(false);
const copy = computed(() => {
  const lang = loadDisplayPrefs().lang;
  return {
    current: t("thisDevice", lang),
    kick: t("kick", lang),
    empty: t("noDevices", lang)
  };
});

onShow(() => {
  loadDevices();
});

async function loadDevices() {
  loading.value = true;
  try {
    devices.value = await listLoginDevices();
  } catch {
    devices.value = [];
  } finally {
    loading.value = false;
  }
}

function formatTime(value?: string) {
  if (!value) {
    return "";
  }
  return value.replace("T", " ").slice(0, 16);
}

function deviceLabel(item: LoginDevice) {
  const raw = item as LoginDevice & { login_count?: number };
  const count = raw.loginCount && raw.loginCount > 0 ? raw.loginCount : raw.login_count && raw.login_count > 0 ? raw.login_count : 1;
  const parts = [item.ip || "", `登录${count}次`, formatTime(item.lastSeenAt)].filter(Boolean);
  return parts.join(" · ");
}

async function kick(item: LoginDevice) {
  if (item.current) {
    toast.info("当前设备请使用退出登录");
    return;
  }
  try {
    await kickLoginDevice(item.id);
    toast.success("已下线");
    loadDevices();
  } catch (error) {
    toast.error((error as Error).message || "下线失败");
  }
}
</script>

<template>
  <view class="page">
    <wd-cell-group v-if="devices.length" border>
      <wd-cell
        v-for="item in devices"
        :key="item.id"
        :title="item.deviceName"
        :label="deviceLabel(item)"
      >
        <view class="device-actions">
          <text v-if="item.current" class="tag">{{ copy.current }}</text>
          <text v-else class="kick" @click="kick(item)">{{ copy.kick }}</text>
        </view>
      </wd-cell>
    </wd-cell-group>
    <view v-else class="empty">{{ loading ? "加载中…" : copy.empty }}</view>
    <wd-toast />
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: #f5f6f8;
  padding-top: 12px;
  padding-bottom: 32px;
}
.empty {
  padding: 24px 16px;
  color: #86909c;
  font-size: 13px;
}
.device-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.tag {
  font-size: 12px;
  color: #4d80f0;
}
.kick {
  font-size: 13px;
  color: #d94b4b;
}
</style>
