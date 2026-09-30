<script setup lang="ts">
import { t } from "@/utils/i18n";
import GesturePad from "@/components/GesturePad.vue";
import {
  encodePattern,
  getGesturePattern,
  isGestureEnabled,
  setGestureEnabled
} from "@/utils/gesture";
import { loadDisplayPrefs } from "@/utils/prefs";
import { onShow } from "@dcloudio/uni-app";
import { computed, ref } from "vue";
import { useToast } from "wot-design-uni";

const toast = useToast();
const gestureOn = ref(isGestureEnabled());
const sheet = ref<"off" | "set" | "confirm" | "">("");
const draft = ref("");
const sheetTip = computed(() => {
  if (sheet.value === "set") {
    return "请绘制新手势，至少连接 4 个点";
  }
  if (sheet.value === "confirm") {
    return "请再绘制一次以确认";
  }
  return "请绘制当前手势以关闭";
});

onShow(() => {
  gestureOn.value = isGestureEnabled();
});

function onGestureChange(event: boolean | { value?: boolean }) {
  const value = typeof event === "boolean" ? event : Boolean(event?.value);
  if (value) {
    gestureOn.value = false;
    draft.value = "";
    sheet.value = "set";
    return;
  }
  if (!getGesturePattern()) {
    gestureOn.value = false;
    setGestureEnabled(false);
    return;
  }
  gestureOn.value = true;
  sheet.value = "off";
}

function cancelSheet() {
  sheet.value = "";
  draft.value = "";
  gestureOn.value = isGestureEnabled();
}

function onPattern(points: number[]) {
  const pattern = encodePattern(points);
  if (sheet.value === "set") {
    draft.value = pattern;
    sheet.value = "confirm";
    return;
  }
  if (sheet.value === "confirm") {
    if (pattern !== draft.value) {
      toast.error("两次手势不一致");
      sheet.value = "set";
      draft.value = "";
      return;
    }
    setGestureEnabled(true, pattern);
    gestureOn.value = true;
    sheet.value = "";
    toast.success("已开启手势解锁");
    return;
  }
  if (sheet.value === "off") {
    if (pattern !== getGesturePattern()) {
      toast.error("手势错误");
      return;
    }
    setGestureEnabled(false);
    gestureOn.value = false;
    sheet.value = "";
    toast.success("已关闭手势解锁");
  }
}

function goPassword() {
  uni.navigateTo({ url: "/pages/mine/password" });
}

function goDevices() {
  uni.navigateTo({ url: "/pages/mine/devices" });
}

const copy = computed(() => {
  const lang = loadDisplayPrefs().lang;
  return {
    password: t("changePassword", lang),
    devices: t("loginDevices", lang),
    gesture: t("gestureUnlock", lang),
    hint: t("gestureOffHint", lang)
  };
});
</script>

<template>
  <view class="page">
    <wd-cell-group border>
      <wd-cell :title="copy.password" is-link @click="goPassword" />
      <wd-cell :title="copy.devices" is-link @click="goDevices" />
      <wd-cell :title="copy.gesture" center>
        <wd-switch :model-value="gestureOn" :key="String(gestureOn) + sheet" size="22px" @change="onGestureChange" />
      </wd-cell>
    </wd-cell-group>
    <view class="hint">{{ copy.hint }}</view>

    <view v-if="sheet" class="mask" @click="cancelSheet">
      <view class="sheet" @click.stop>
        <view class="sheet-title">{{ copy.gesture }}</view>
        <view class="sheet-tip">{{ sheetTip }}</view>
        <GesturePad @complete="onPattern" />
        <view class="cancel" @click="cancelSheet">取消</view>
      </view>
    </view>
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
.hint {
  padding: 8px 16px 0;
  color: #86909c;
  font-size: 12px;
}
.mask {
  position: fixed;
  inset: 0;
  z-index: 20;
  background: rgba(15, 23, 42, 0.45);
  display: flex;
  align-items: flex-end;
}
.sheet {
  width: 100%;
  background: #fff;
  border-radius: 16px 16px 0 0;
  padding: 20px 16px 28px;
}
.sheet-title {
  text-align: center;
  font-size: 16px;
  font-weight: 600;
}
.sheet-tip {
  text-align: center;
  margin: 8px 0 12px;
  color: #86909c;
  font-size: 13px;
}
.cancel {
  text-align: center;
  margin-top: 12px;
  color: #4e5969;
}
</style>
