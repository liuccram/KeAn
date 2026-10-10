<script setup lang="ts">
import { fetchTurnstileConfig } from "@/api/auth";
import { resetPassword, sendSms } from "@/api/sms";
import CodeBoxes from "@/components/CodeBoxes.vue";
import TurnstileChallenge from "@/components/TurnstileChallenge.vue";
import { goBack } from "@/utils/authNav";
import { QQ_EMAIL_HINT, normalizeQqEmail } from "@/utils/qqEmail";
import { t, tf } from "@/utils/i18n";
import { useToast } from "wot-design-uni";
import { computed, onMounted, onUnmounted, reactive, ref } from "vue";

const toast = useToast();
const loading = ref(false);
const sending = ref(false);
const countdown = ref(0);
const formRef = ref();
const turnstileRef = ref<{ reset: () => void } | null>(null);
let timer: ReturnType<typeof setInterval> | null = null;
const model = reactive({
  email: "",
  smsCode: "",
  newPassword: "",
  confirmPassword: "",
  turnstileToken: ""
});
/**
 * 页面文案：统一走 i18n，不在模板里硬编码中文。
 * 用 computed 包一层，语言偏好变更后重新进入页面就会整体跟着变。
 */
const i18n = computed(() => ({
  brand: t("authBrand"),
  headline: t("authResetHeadline"),
  headlineSub: t("authResetSub"),
  emailPlaceholder: t("authEmailExample"),
  smsLabel: t("authSmsLabel"),
  sendSms: t("authSendSms"),
  newPasswordPlaceholder: t("authPasswordNewPlaceholder"),
  confirmPlaceholder: t("authPasswordConfirmPlaceholder"),
  submit: t("authResetSubmit"),
  slogan: t("authSloganText"),
  captchaFailed: t("authCaptchaFailed"),
  captchaMissing: t("authCaptchaMissingReset"),
  ruleEmail: t("authRuleEmailRequired"),
  ruleNewPassword: t("authRuleNewPasswordRequired"),
  ruleConfirm: t("authRuleNewConfirmRequired")
}));
/**
 * 真人验证配置。必须严格区分这几种状态，否则运维会被误导：
 * - enabled:true  + siteKey     → 正常渲染控件
 * - enabled:true  + 空 siteKey  → 后端明确开启但没配 siteKey（真·未配置）
 * - enabled:false + error:false → 后端明确关闭：界面什么都不显示、提交/发码直接放行
 * - error:true                  → 配置请求本身失败（网络/404/超时）：既不是"已开启"也不是"未配置"
 * enabled 初始为 false，配置回来前不冒充"已开启"（此时界面不显示任何控件，
 * 提交/发码也会被"配置读取中，请稍后重试"拦住，不会抢跑放行）。
 */
const captcha = reactive({
  enabled: false,
  siteKey: "",
  loaded: false,
  error: false
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

onUnmounted(() => {
  if (timer) {
    clearInterval(timer);
  }
});

function startCountdown() {
  countdown.value = 60;
  if (timer) {
    clearInterval(timer);
  }
  timer = setInterval(() => {
    countdown.value -= 1;
    if (countdown.value <= 0 && timer) {
      clearInterval(timer);
      timer = null;
    }
  }, 1000);
}

function requireCaptcha(): boolean {
  // 配置还在请求中：既不抢跑放行也不误报"未配置"（uni.request 有 15s 超时，loaded 必然会在有限时间内变 true）。
  // 放在最前面：能拿到 token 就说明控件已渲染、配置早已 loaded，所以这不会误伤"已有 token"的情况。
  if (!captcha.loaded) {
    toast.error("配置读取中，请稍后重试");
    return false;
  }
  // 已有 token 照常放行
  if (model.turnstileToken) {
    return true;
  }
  // 配置请求失败 ≠ 未配置：单独提示，不要误导
  if (captcha.error) {
    toast.error("配置读取失败，请稍后重试");
    return false;
  }
  // 只有确实拿到 enabled:true 才拦；enabled:false（后端显式关闭）直接放行
  if (captcha.enabled) {
    toast.error(captcha.siteKey ? "请完成真人验证" : "人机验证未配置");
    return false;
  }
  return true;
}

/**
 * 返回：**不再直接调 navigateBack**（理由同注册页）。
 * 本页可能从登录页 navigateTo 压栈进来，也可能是 redirectTo / 直接打开 URL 进来的；
 * 后者栈里没有上一页，navigateBack() 会静默失败 → 用户被困。
 * 统一走 utils/authNav.ts 的 goBack()：能返回就返回，退无可退就回登录页。
 */
function onBack() {
  goBack();
}

async function handleSendSms() {
  const email = normalizeQqEmail(model.email);
  if (!email) {
    toast.error(QQ_EMAIL_HINT);
    return;
  }
  if (sending.value || countdown.value > 0) {
    return;
  }
  if (!requireCaptcha()) {
    return;
  }
  sending.value = true;
  try {
    await sendSms({ email, scene: "FORGOT_PASSWORD", turnstileToken: model.turnstileToken || undefined });
    turnstileRef.value?.reset();
    startCountdown();
    toast.success(tf("authSmsSent", { email }));
  } catch (error) {
    turnstileRef.value?.reset();
    toast.error((error as Error).message || "发送失败");
  } finally {
    sending.value = false;
  }
}

function handleSubmit() {
  formRef.value
    .validate()
    .then(async ({ valid }: { valid: boolean }) => {
      if (!valid) {
        return;
      }
      if (model.newPassword !== model.confirmPassword) {
        toast.error("两次输入的密码不一致");
        return;
      }
      const email = normalizeQqEmail(model.email);
      if (!email) {
        toast.error(QQ_EMAIL_HINT);
        return;
      }
      if (!/^\d{6}$/.test(model.smsCode)) {
        toast.error("请填写 6 位验证码");
        return;
      }
      if (!requireCaptcha()) {
        return;
      }
      loading.value = true;
      try {
        await resetPassword({
          email,
          smsCode: model.smsCode,
          newPassword: model.newPassword,
          turnstileToken: model.turnstileToken || undefined
        });
        toast.success("密码已重置，请登录");
        setTimeout(() => {
          uni.redirectTo({ url: "/pages/auth/login" });
        }, 400);
      } catch (error) {
        turnstileRef.value?.reset();
        toast.error((error as Error).message || "重置失败");
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
         它是 .auth-content 的兄弟节点，永远不是弹层的祖先，filter: blur() 安全。 -->
    <view class="auth-sky" />
    <!-- 返回按钮：走 utils/authNav.ts 的 goBack()，任何入口进来都出得去 -->
    <view class="auth-back" @click="onBack">
      <view class="auth-back__arrow" />
    </view>

    <view class="auth-content">
      <view class="auth-hero auth-hero--compact">
        <image class="auth-logo" src="/static/app-logo.jpg" mode="aspectFit" />
        <view class="auth-title">{{ i18n.brand }}</view>
      </view>

      <!-- 悬浮玻璃卡片 = 外层玻璃框（.auth-card）+ 内层玻璃板（.auth-card__inner），
           两层之间留 2px 缝做厚度。本页没有 wd-picker，外框用 backdrop-filter 是安全的。 -->
      <view class="auth-card">
        <view class="auth-card__inner">
          <view class="auth-card-title">{{ i18n.headline }}</view>
          <view class="auth-card-sub">{{ i18n.headlineSub }}</view>

          <wd-form ref="formRef" :model="model" error-type="toast">
            <view class="auth-field auth-field--mail">
              <wd-input
                v-model="model.email"
                prop="email"
                :no-border="true"
                clearable
                :placeholder="i18n.emailPlaceholder"
                :rules="[{ required: true, message: i18n.ruleEmail }]"
              />
            </view>

            <view class="auth-block">
              <view class="auth-block-head">
                <text>{{ i18n.smsLabel }}</text>
                <wd-button
                  size="small"
                  custom-class="auth-code-btn"
                  :loading="sending"
                  :disabled="countdown > 0"
                  @click="handleSendSms"
                >
                  {{ countdown > 0 ? `${countdown}s` : i18n.sendSms }}
                </wd-button>
              </view>
              <CodeBoxes v-model="model.smsCode" />
            </view>

            <TurnstileChallenge
              v-if="captcha.enabled && captcha.siteKey"
              ref="turnstileRef"
              box-id="kean-ts-forgot"
              :site-key="captcha.siteKey"
              v-model="model.turnstileToken"
            />
            <!-- 配置请求失败：单独一行，且绝不在失败时渲染验证控件（H5 与非 H5 都渲染这个普通 view） -->
            <view v-else-if="captcha.error" class="auth-hint">{{ i18n.captchaFailed }}</view>
            <!-- 只有后端明确 enabled:true、却没给 siteKey 才是"未配置"；enabled:false 时这里也不显示 -->
            <view v-else-if="captcha.loaded && captcha.enabled" class="auth-hint">{{ i18n.captchaMissing }}</view>

            <!-- 极细分隔线：验证码区与「新密码」区属于两件事，用一条淡线分开即可 -->
            <view class="auth-divider" />

            <view class="auth-field auth-field--lock">
              <wd-input
                v-model="model.newPassword"
                prop="newPassword"
                :no-border="true"
                show-password
                :placeholder="i18n.newPasswordPlaceholder"
                :rules="[{ required: true, message: i18n.ruleNewPassword }]"
              />
            </view>
            <view class="auth-field auth-field--lock">
              <wd-input
                v-model="model.confirmPassword"
                prop="confirmPassword"
                :no-border="true"
                show-password
                :placeholder="i18n.confirmPlaceholder"
                :rules="[{ required: true, message: i18n.ruleConfirm }]"
              />
            </view>

            <view class="auth-actions">
              <wd-button
                type="primary"
                size="large"
                block
                custom-class="auth-btn auth-btn--gradient"
                :loading="loading"
                @click="handleSubmit"
              >
                {{ i18n.submit }}
              </wd-button>
            </view>
          </wd-form>
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
