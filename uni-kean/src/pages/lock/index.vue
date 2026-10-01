<script setup lang="ts">
import GestureLock from "@/components/GestureLock.vue";
import { useUserStore } from "@/store/user";
import { shouldShowGestureLock } from "@/utils/gesture";
import { onBackPress, onShow } from "@dcloudio/uni-app";

/**
 * 锁屏必须是一个独立页面。
 *
 * uni-app 的 `App.vue` 是应用入口，**不能编写视图元素**（官方约束：App.vue 不能写模板），
 * 之前把锁屏组件放在 App.vue 的 template 里，结果在 App / 小程序 上永远不会被渲染 ——
 * 表现就是「开启了手势解锁，锁屏却不出现」。
 *
 * 现在由 App.vue 的应用生命周期负责判断并 reLaunch 到本页（应用生命周期全平台有效）。
 */
const userStore = useUserStore();

function goHome() {
  uni.switchTab({ url: "/pages/home/index" });
}

function goLogin() {
  uni.reLaunch({ url: "/pages/auth/login" });
}

onShow(() => {
  // 已经解锁、或当前不需要锁（未登录 / 未开启 / 未设手势）时，不该停在这一页
  if (!shouldShowGestureLock(userStore.isLoggedIn.value)) {
    goHome();
  }
});

// 锁屏页不允许用 Android 返回键绕过（返回 true 表示拦截默认行为）
onBackPress(() => true);
</script>

<template>
  <GestureLock @unlocked="goHome" @escaped="goLogin" />
</template>
