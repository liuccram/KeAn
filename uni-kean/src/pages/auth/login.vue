<script setup lang="ts">
import { fetchTurnstileConfig, login } from "@/api/auth";
import TurnstileChallenge from "@/components/TurnstileChallenge.vue";
import { useUserStore } from "@/store/user";
import { canGoBack, goBack } from "@/utils/authNav";
import { t } from "@/utils/i18n";
import { refreshMessageBadge } from "@/utils/messageBadge";
import { startRealtime } from "@/utils/realtime";
import { onLoad } from "@dcloudio/uni-app";
import { useToast } from "wot-design-uni";
import { computed, onMounted, reactive, ref } from "vue";

const toast = useToast();
const userStore = useUserStore();
const loading = ref(false);
const formRef = ref();
const turnstileRef = ref<{ reset: () => void } | null>(null);
const redirect = ref("");
const model = reactive({
  username: "",
  password: "",
  turnstileToken: ""
});
/**
 * 页面文案：统一走 i18n，不在模板里硬编码中文。
 * 用 computed 包一层，语言偏好变更后重新进入页面就会整体跟着变。
 */
const i18n = computed(() => ({
  brand: t("authBrand"),
  heroSlogan: t("authHeroSlogan"),
  welcome: t("authWelcomeBack"),
  welcomeSub: t("authWelcomeSub"),
  usernamePlaceholder: t("authUsernamePlaceholder"),
  passwordPlaceholder: t("authPasswordPlaceholder"),
  submit: t("authLogin"),
  register: t("authRegisterAccount"),
  forgot: t("authForgotPassword"),
  slogan: t("authSloganText"),
  ruleUsername: t("authRuleUsernameRequired"),
  rulePassword: t("authRulePasswordRequired"),
  captchaFailed: t("authCaptchaFailed"),
  captchaMissing: t("authCaptchaMissingLogin"),
  back: t("authBack")
}));
/**
 * 真人验证配置。必须严格区分这几种状态，否则运维会被误导：
 * - enabled:true  + siteKey     → 正常渲染控件
 * - enabled:true  + 空 siteKey  → 后端明确开启但没配 siteKey（真·未配置）
 * - enabled:false + error:false → 后端明确关闭：界面什么都不显示、提交直接放行
 * - error:true                  → 配置请求本身失败（网络/404/超时）：既不是"已开启"也不是"未配置"
 * enabled 初始为 false，配置回来前不冒充"已开启"（此时界面不显示任何控件，
 * 提交也会被"配置读取中，请稍后重试"拦住，不会抢跑放行）。
 */
const captcha = reactive({
  enabled: false,
  siteKey: "",
  loaded: false,
  error: false
});
/**
 * 登录页要不要显示返回入口。
 *
 * 设计图上登录页没有返回键，因为它是 auth 流程的入口页；但登录页同样可能被
 * navigateTo / redirectTo 压栈进来（例如未登录时点「发布」被送到
 * /pages/auth/login?redirect=publish），这时如果没有任何出口，用户就只能靠系统返回。
 * 所以这里只在**页面栈里确实还有上一页**时才把返回入口显示出来：
 * 正常冷启动进登录页时栈底就是它，不显示（与设计图一致）；被压栈进来时显示，且一定点得动。
 */
const showBack = computed(() => canGoBack());

onLoad((query) => {
  redirect.value = String(query?.redirect || "");
});

onMounted(async () => {
  try {
    const data = await fetchTurnstileConfig();
    captcha.enabled = data.enabled === true;
    captcha.siteKey = data.siteKey || "";
    captcha.error = false;
  } catch {
    // 配置没拿到：不能伪装成"验证已开启"，也不能说成"未配置"，单独标记为读取失败。
    captcha.enabled = false;
    captcha.siteKey = "";
    captcha.error = true;
  } finally {
    captcha.loaded = true;
  }
});

function afterLogin() {
  if (redirect.value === "publish") {
    uni.switchTab({ url: "/pages/publish/index" });
    return;
  }
  uni.switchTab({ url: "/pages/mine/index" });
}

function goRegister() {
  uni.navigateTo({ url: "/pages/auth/register" });
}

function goForgot() {
  uni.navigateTo({ url: "/pages/auth/forgot" });
}

function handleLogin() {
  formRef.value
    .validate()
    .then(async ({ valid }: { valid: boolean }) => {
      if (!valid) {
        return;
      }
      // 配置还在请求中：不要抢跑放行（uni.request 有 15s 超时，loaded 必然会在有限时间内变 true）。
      if (!captcha.loaded) {
        toast.error("配置读取中，请稍后重试");
        return;
      }
      // 已有 token 直接放行；配置读取失败单独提示；只有确实拿到 enabled:true 才拦住。
      // enabled:false（后端显式关闭）时两条都不成立 → 直接放行。
      if (captcha.error && !model.turnstileToken) {
        toast.error("配置读取失败，请稍后重试");
        return;
      }
      if (captcha.enabled && !model.turnstileToken) {
        toast.error(captcha.siteKey ? "请完成真人验证" : "人机验证未配置");
        return;
      }
      loading.value = true;
      try {
        const data = await login({
          username: model.username,
          password: model.password,
          turnstileToken: model.turnstileToken || undefined
        });
        userStore.setLogin(data.token, data.user);
        startRealtime();
        toast.success("登录成功");
        refreshMessageBadge();
        setTimeout(() => {
          afterLogin();
        }, 400);
      } catch (error) {
        turnstileRef.value?.reset();
        toast.error((error as Error).message || "登录失败");
      } finally {
        loading.value = false;
      }
    })
    .catch(() => undefined);
}
</script>

<template>
  <view class="page kean-auth">
    <!-- 背景装饰层：手写 SVG 几何校园场景（矢量 / 已柔化），纯装饰、不参与交互。
         它是 .auth-content 的兄弟节点，永远不是 wd-picker 弹层的祖先，
         所以这里的 filter: blur() 不会劫持任何 position:fixed 的定位。 -->
    <view class="auth-sky" />

    <!-- 返回入口：只有从别的页面压栈进来时才出现（栈底直接进登录页时不显示）。
         放在 .auth-sky 之后，z-index 稳定压住背景装饰层。 -->
    <view v-if="showBack" class="auth-back auth-back--login" @click="goBack">
      <view class="auth-back__arrow" />
      <text class="auth-back__label">{{ i18n.back }}</text>
    </view>

    <view class="auth-content">
      <view class="auth-hero">
        <image class="auth-logo" src="/static/app-logo.jpg" mode="aspectFit" />
        <view class="auth-title">{{ i18n.brand }}</view>
        <view class="auth-subtitle">— {{ i18n.heroSlogan }} —</view>
      </view>

      <!-- 悬浮玻璃登录区 = 外层玻璃框（.auth-card：亮边 + 冷调软阴影 + backdrop-filter 模糊）
           + 内层玻璃板（.auth-card__inner：板面 + 自己的细边与顶部高光），两层之间留 2px 缝做厚度。
           本页没有 wd-picker，所以外框可以安全地用 backdrop-filter（见 auth-theme.css 文件头约束 3）。 -->
      <view class="auth-card">
        <view class="auth-card__inner">
          <view class="auth-card-title">{{ i18n.welcome }}</view>
          <view class="auth-card-sub">{{ i18n.welcomeSub }}</view>

          <wd-form ref="formRef" :model="model" error-type="toast">
            <view class="auth-field auth-field--user">
              <wd-input
                v-model="model.username"
                prop="username"
                :no-border="true"
                clearable
                :placeholder="i18n.usernamePlaceholder"
                :rules="[{ required: true, message: i18n.ruleUsername }]"
              />
            </view>
            <view class="auth-field auth-field--lock">
              <wd-input
                v-model="model.password"
                prop="password"
                :no-border="true"
                show-password
                :placeholder="i18n.passwordPlaceholder"
                :rules="[{ required: true, message: i18n.rulePassword }]"
              />
            </view>

            <TurnstileChallenge
              v-if="captcha.enabled && captcha.siteKey"
              ref="turnstileRef"
              :site-key="captcha.siteKey"
              v-model="model.turnstileToken"
            />
            <!-- 配置请求失败：单独一行，且绝不在失败时渲染验证控件 -->
            <view v-else-if="captcha.error" class="auth-hint">{{ i18n.captchaFailed }}</view>
            <!-- 只有后端明确 enabled:true、却没给 siteKey 才是"未配置"；enabled:false 时这里也不显示 -->
            <view v-else-if="captcha.loaded && captcha.enabled" class="auth-hint">{{ i18n.captchaMissing }}</view>

            <view class="auth-actions">
              <wd-button
                type="primary"
                size="large"
                block
                custom-class="auth-btn auth-btn--gradient"
                :loading="loading"
                @click="handleLogin"
              >
                {{ i18n.submit }}
              </wd-button>
            </view>
          </wd-form>

          <!-- 极细分隔线：把「表单」与「底部两个链接」轻轻分开，不用边框框住任何区域 -->
          <view class="auth-divider" />

          <view class="auth-links">
            <text class="auth-link" @click="goRegister">{{ i18n.register }}</text>
            <text class="auth-link auth-link--muted" @click="goForgot">{{ i18n.forgot }}</text>
          </view>
        </view>
      </view>

      <view class="auth-slogan">— {{ i18n.slogan }} —</view>
    </view>
    <wd-toast />
  </view>
</template>

<style scoped>
/* 视觉全部由 src/styles/auth-theme.css 统一提供（.kean-auth 作用域），
   本页无需额外样式。 */
</style>
