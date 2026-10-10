<script setup lang="ts">
// 注意：App.vue 是应用入口，**不能编写视图元素 / 不能写模板**（uni-app 官方约束）。
// 全局性的界面请放到页面里。
import { onHide, onLaunch, onShow } from "@dcloudio/uni-app";
import { heartbeat } from "@/api/auth";
import { useUserStore } from "@/store/user";
import { refreshMessageBadge } from "@/utils/messageBadge";
import { applyDisplayAppearance } from "@/utils/prefs";
import { startRealtime, stopRealtime } from "@/utils/realtime";

const userStore = useUserStore();
let timer: ReturnType<typeof setInterval> | null = null;

function ping() {
  if (!userStore.isLoggedIn.value) {
    stopRealtime();
    return;
  }
  startRealtime();
  heartbeat().catch(() => undefined);
}

function startHeartbeat() {
  ping();
  if (timer) {
    return;
  }
  timer = setInterval(ping, 10000);
}

function stopHeartbeat() {
  if (timer) {
    clearInterval(timer);
    timer = null;
  }
}

onLaunch(() => {
  applyDisplayAppearance();
});

onShow(() => {
  refreshMessageBadge();
  startHeartbeat();
  applyDisplayAppearance();
});

onHide(() => {
  stopHeartbeat();
  stopRealtime();
});
</script>

<style>
@import "./styles/theme-vars.css";
@import "./styles/wot-theme.css";
/* 设计令牌必须在 theme-vars 之后：design-tokens 里 --kean-t3 / --kean-grad-primary
   是引用 theme-vars 的变量算出来的，反过来会把它们变成无效值。 */
@import "./styles/design-tokens.css";
/* 设计套件（可复用零件）：全部收在 .kean-mine 作用域下，只有带该 class 的页面命中。
   ⚠️ 它**必须放在 wot 的异步分包 CSS 之前**没有意义 —— 组件异步 CSS 永远在入口 CSS
   之后，所以 kit 里覆盖组件的规则一律写到 0,3,0 以上（见 design-kit.css 文件头约束 1）。 */
@import "./styles/design-kit.css";
/* 消息页（消息 Tab / 会话列表 / 通知列表）的皮肤与零件：全部收在 .kean-msg 之下。 */
@import "./styles/design-messages.css";
@import "./styles/wallpaper-skin.css";
@import "./styles/display-appearance.css";
/* auth 三页（登录 / 注册 / 忘记密码）共用的皮肤：选择器全部收在 .kean-auth 之下，
   只有带该 class 的页面会命中，对其它页面零影响。 */
@import "./styles/auth-theme.css";
page {
  background-color: var(--kean-page-bg, #f5f6f8);
  font-size: var(--kean-fs, 16px);
  overflow-x: hidden;
}
</style>
