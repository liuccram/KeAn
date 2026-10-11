<script setup lang="ts">
import { fetchMe } from "@/api/auth";
import TaskForm from "@/components/TaskForm.vue";
import PageBackdrop from "@/components/PageBackdrop.vue";
import { usePageWallpaper } from "@/composables/usePageWallpaper";
import { useUserStore } from "@/store/user";
import { actionBlockReason } from "@/utils/format";
import { onShow } from "@dcloudio/uni-app";
import { computed, ref } from "vue";

const userStore = useUserStore();
const { wallpaperOn, wallpaperImage } = usePageWallpaper();
// 顶部标题栏改成页面内自绘（pages.json 的 navigationStyle: custom）后，刘海屏要自己
// 让出状态栏高度；取值与 pages/mine/index.vue 完全同一套（同一行、同一个 20 兜底）。
const statusBarHeight = uni.getSystemInfoSync().statusBarHeight || 20;
const loggedIn = ref(userStore.isLoggedIn.value);
const formKey = ref(0);
const publishBlock = computed(() => actionBlockReason(userStore.state.user, "publish"));

onShow(async () => {
  loggedIn.value = userStore.isLoggedIn.value;
  if (!loggedIn.value) {
    return;
  }
  try {
    const latest = await fetchMe();
    if (userStore.state.token) {
      userStore.setLogin(userStore.state.token, latest);
    }
  } catch {
    // 使用本地缓存
  }
});

function goLogin() {
  uni.navigateTo({ url: "/pages/auth/login?redirect=publish" });
}

function onSuccess(id: number) {
  // 仅在发布成功后重建表单：下次进入发布页是干净表单，而切 Tab / 返回时未提交内容得以保留
  formKey.value += 1;
  uni.navigateTo({ url: `/pages/task/detail?id=${id}` });
}

function onReset() {
  // 重建 TaskForm 即回到初始状态：默认值只在组件内 model 声明处定义一份，不会漏字段
  formKey.value += 1;
}
</script>

<template>
  <view class="page" :class="{ skinned: wallpaperOn }">
    <PageBackdrop :src="wallpaperImage" />
    <!-- 顶部标题栏（原框架导航栏，现按「我的」页的做法自绘）：
         ⚠️ 放在三种状态（未登录 / 受限 / 表单）**之外**：原框架导航栏在三种状态下都在，
         放进来才能保证三种状态都还有标题栏；它自己不依赖 loggedIn / publishBlock。
         ⚠️ 它就在页面内容的滚动流里，**没有** position: fixed / sticky → 随内容一起滚走。
         文案取 pages.json 的 navigationBarTitleText「发布」，不另造文案。 -->
    <view class="status-bar" :style="{ height: statusBarHeight + 'px' }" />
    <view class="nav-title">发布</view>
    <view v-if="!loggedIn" class="guest">
      <wd-status-tip image="content" tip="登录后才能发布代课" />
      <wd-button type="primary" @click="goLogin">去登录</wd-button>
    </view>
    <view v-else-if="publishBlock" class="guest">
      <wd-status-tip image="content" :tip="publishBlock" />
    </view>
    <TaskForm v-else :key="formKey" @success="onSuccess" @reset="onReset" />
    <wd-toast />
  </view>
</template>

<style scoped>
/* ===========================================================================
 * 顶部标题栏（自绘，替代被 navigationStyle: custom 移除的框架导航栏）
 * ---------------------------------------------------------------------------
 * 排版逐字照抄 pages/mine/index.vue 的 .status-bar / .nav-title（含全局表
 * design-kit.css 的 `.kean-mine .nav-title`：17px / 600 / 居中 / 10-6 内边距），
 * 一个字都没有新发明。
 * ⚠️ 这里**不写** position: fixed / sticky：它必须是普通文档流里的元素，
 *    才会随页面内容一起滚走（这正是本次需求）。
 * ⚠️ 也**不写** backdrop-filter / transform / filter / will-change / contain /
 *    perspective：本页 <wd-toast> 与 TaskForm 内部的 wd-picker / wd-popup 都是
 *    position: fixed，祖先链上出现这些属性会把它们劫持成"关不掉的弹层"。
 * 颜色：原框架栏是全局 style 的白底黑字（navigationBarBackgroundColor #FFFFFF /
 *    navigationBarTextStyle black），所以字色取 --kean-text（#1d2129）并带同值兜底；
 *    底色刻意留透明，与「我的」页的自绘标题一致。壁纸态改浅色字（见下）。
 * =========================================================================== */
.status-bar {
  width: 100%;
}
.nav-title {
  text-align: center;
  font-size: 17px;
  font-weight: 600;
  color: var(--kean-text, #1d2129);
  padding: 10px 0 6px;
}
/* 壁纸态：本页页面底变成深色流星图（wallpaper-skin.css），黑字会看不见 ——
   与「我的」页同向改浅色。特异性 0,3,0 > 上面 scoped 的 0,2,0。 */
.page.skinned .nav-title {
  color: #f4f7ff;
}
.page {
  position: relative;
  min-height: 100vh;
  background: var(--kean-bg);
}
.page.skinned {
  background: transparent;
}
.guest {
  padding-top: 80px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
}
</style>
