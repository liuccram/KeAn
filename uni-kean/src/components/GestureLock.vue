<script setup lang="ts">
import { logout } from "@/api/auth";
import GesturePad from "@/components/GesturePad.vue";
import { useUserStore } from "@/store/user";
import { clearGestureLock, encodePattern, getGesturePattern, setGestureEnabled } from "@/utils/gesture";
import { clearAuth } from "@/utils/storage";
import { ref } from "vue";

const emit = defineEmits<{ unlocked: []; escaped: [] }>();
const error = ref("");

function onComplete(points: number[]) {
  const pattern = encodePattern(points);
  if (pattern === getGesturePattern()) {
    error.value = "";
    clearGestureLock();
    emit("unlocked");
    return;
  }
  error.value = "手势错误，请重试";
}

/**
 * 忘记手势的出路：清掉本机手势 + 退出登录，再由外层跳登录页。
 * 这里绝不放行进入 App —— 手势被清除后用户必须用账号密码重新登录。
 */
function onForgot() {
  uni.showModal({
    title: "忘记手势？",
    content: "将清除本机手势并退出登录，需要用账号密码重新登录。",
    confirmText: "重新登录",
    cancelText: "取消",
    success: (res) => {
      if (!res.confirm) {
        return;
      }
      // 尽力通知服务端注销（离线或 token 失效时失败也无妨），随后清本地凭证
      logout().catch(() => undefined);
      setGestureEnabled(false);
      clearGestureLock();
      try {
        useUserStore().logoutLocal();
      } catch {
        clearAuth();
      }
      emit("escaped");
    }
  });
}
</script>

<template>
  <view class="lock">
    <view class="title">手势解锁</view>
    <view class="tip">请绘制解锁手势</view>
    <view v-if="error" class="err">{{ error }}</view>
    <GesturePad @complete="onComplete" />
    <view class="forgot" @click="onForgot">忘记手势？重新登录</view>
  </view>
</template>

<style scoped>
.lock {
  position: fixed;
  inset: 0;
  z-index: 9999;
  background: #0b1224;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: #f4f7ff;
}
.title {
  font-size: 20px;
  font-weight: 700;
}
.tip,
.err {
  margin-top: 8px;
  font-size: 13px;
  color: rgba(235, 244, 255, 0.72);
}
.err {
  color: #ff8b8b;
}
.forgot {
  margin-top: 24px;
  padding: 8px 16px;
  font-size: 14px;
  color: #8ab4ff;
}
</style>
