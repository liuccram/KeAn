<script setup lang="ts">
import { fetchMe } from "@/api/auth";
import { updateSingleDevice } from "@/api/user";
import { useUserStore } from "@/store/user";
import { t } from "@/utils/i18n";
import { loadDisplayPrefs } from "@/utils/prefs";
import { computed, ref } from "vue";
import { useToast } from "wot-design-uni";

const toast = useToast();
const userStore = useUserStore();
const saving = ref(false);
const singleDeviceOn = computed(() => userStore.state.user?.singleDevice === 1);

function goPassword() {
  uni.navigateTo({ url: "/pages/mine/password" });
}

function goDevices() {
  uni.navigateTo({ url: "/pages/mine/devices" });
}

/**
 * 切换「仅允许一台设备在线」。
 * 写法与 pages/mine/privacy.vue 改隐私账号一致：调接口 → 拉一次 /api/auth/me →
 * 用接口返回值兜底覆盖本地用户信息，保证切页回来开关状态不会跳。
 */
async function toggleSingleDevice(value: boolean | { value?: boolean }) {
  const next = typeof value === "boolean" ? value : Boolean(value?.value);
  if (saving.value || next === singleDeviceOn.value) {
    return;
  }
  saving.value = true;
  try {
    const user = await updateSingleDevice(next ? 1 : 0);
    if (userStore.state.token) {
      const latest = await fetchMe();
      userStore.setLogin(userStore.state.token, {
        ...latest,
        singleDevice: user.singleDevice ?? (next ? 1 : 0)
      });
    }
    toast.success(next ? "已开启仅允许一台设备在线" : "已关闭仅允许一台设备在线");
  } catch (error) {
    toast.error((error as Error).message || "设置失败");
  } finally {
    saving.value = false;
  }
}

const copy = computed(() => {
  const lang = loadDisplayPrefs().lang;
  return {
    password: t("changePassword", lang),
    devices: t("loginDevices", lang)
  };
});
</script>

<template>
  <view class="page">
    <wd-cell-group border>
      <wd-cell :title="copy.password" is-link @click="goPassword" />
      <wd-cell :title="copy.devices" is-link @click="goDevices" />
      <wd-cell
        title="仅允许一台设备在线"
        label="关闭时：多端可同时在线，新设备登录只会提醒你。打开后：在新设备登录会把其他设备退出。"
      >
        <wd-switch :model-value="singleDeviceOn" :disabled="saving" @change="toggleSingleDevice" />
      </wd-cell>
    </wd-cell-group>
    <view class="tip">打开后立即生效：其他设备上的登录会马上失效，那些设备会提示「你的账号已在其他设备登录」并回到登录页，需要重新登录才能使用；之后你每次在新设备登录，也会把其他设备退出。当前正在使用的这一台不受影响。关闭后不会再顶掉任何设备，只会照常发送新设备登录提醒。</view>
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
.tip {
  padding: 12px 16px;
  color: #86909c;
  font-size: 12px;
  line-height: 1.6;
}
</style>
