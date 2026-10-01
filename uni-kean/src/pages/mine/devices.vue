<script setup lang="ts">
import { kickLoginDevice, listLoginDevices, type LoginDevice } from "@/api/device";
import ListState from "@/components/ListState.vue";
import { t } from "@/utils/i18n";
import { loadDisplayPrefs } from "@/utils/prefs";
import { onShow } from "@dcloudio/uni-app";
import { computed, ref } from "vue";
import { useToast } from "wot-design-uni";

const toast = useToast();
const devices = ref<LoginDevice[]>([]);
const loading = ref(false);
const error = ref("");
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
  const first = devices.value.length === 0;
  if (first) {
    loading.value = true;
  }
  error.value = "";
  try {
    devices.value = await listLoginDevices();
  } catch (err) {
    // 绝不能把失败当成「暂无登录设备」：设备被顶下线或接口异常时，
    // 那会让用户以为账号没有任何登录设备 —— 安全场景下最危险的假阴性。
    const message = (err as Error).message || "加载失败";
    error.value = message;
    // 已有列表时保留上一次的设备并只用轻提示，避免失败态把列表顶掉。
    if (!first) {
      toast.error(message);
    }
  } finally {
    if (first) {
      loading.value = false;
    }
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
    <ListState
      :loading="loading"
      :error="error"
      :empty="devices.length === 0"
      :empty-text="copy.empty"
      @retry="loadDevices"
    >
      <wd-cell-group border>
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
    </ListState>
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
