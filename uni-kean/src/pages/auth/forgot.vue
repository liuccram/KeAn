<script setup lang="ts">
import { fetchTurnstileConfig } from "@/api/auth";
import { resetPassword, sendSms } from "@/api/sms";
import CodeBoxes from "@/components/CodeBoxes.vue";
import TurnstileChallenge from "@/components/TurnstileChallenge.vue";
import { normalizeQqEmail } from "@/utils/qqEmail";
import { useToast } from "wot-design-uni";
import { onMounted, onUnmounted, reactive, ref } from "vue";

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

async function handleSendSms() {
  const email = normalizeQqEmail(model.email);
  if (!email) {
    toast.error("请填写 5-11 位 QQ 号");
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
    toast.success(`验证码已发送到 ${email}`);
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
        toast.error("请填写正确的 QQ 邮箱");
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
  <view class="page">
    <wd-navbar title="忘记密码" left-arrow safe-area-inset-top @click-left="uni.navigateBack()" />
    <wd-form ref="formRef" :model="model" error-type="toast">
      <wd-cell-group border>
        <wd-input
          v-model="model.email"
          label="QQ邮箱"
          label-width="90px"
          prop="email"
          clearable
          placeholder="初始QQ邮箱"
          :rules="[{ required: true, message: '请填写QQ邮箱' }]"
        />
      </wd-cell-group>
      <view class="code-block">
        <view class="code-head">
          <text>邮箱验证码</text>
          <wd-button size="small" :loading="sending" :disabled="countdown > 0" @click="handleSendSms">
            {{ countdown > 0 ? `${countdown}s` : "获取验证码" }}
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
      <view v-else-if="captcha.error" class="captcha-hint">真人验证配置读取失败，请稍后重试</view>
      <!-- 只有后端明确 enabled:true、却没给 siteKey 才是"未配置"；enabled:false 时这里也不显示 -->
      <view v-else-if="captcha.loaded && captcha.enabled" class="captcha-hint">人机验证未配置，暂无法重置密码</view>
      <wd-cell-group border>
        <wd-input
          v-model="model.newPassword"
          label="新密码"
          label-width="90px"
          prop="newPassword"
          show-password
          placeholder="8-32 位新密码"
          :rules="[{ required: true, message: '请填写新密码' }]"
        />
        <wd-input
          v-model="model.confirmPassword"
          label="确认密码"
          label-width="90px"
          prop="confirmPassword"
          show-password
          placeholder="再次输入新密码"
          :rules="[{ required: true, message: '请再次输入新密码' }]"
        />
      </wd-cell-group>
      <view class="footer">
        <wd-button type="primary" size="large" block :loading="loading" @click="handleSubmit">
          重置密码
        </wd-button>
      </view>
    </wd-form>
    <wd-toast />
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: var(--kean-bg);
}
.footer {
  padding: 24px 16px;
}
.code-block {
  margin: 12px 16px 0;
  padding: 14px 16px 16px;
  background: var(--kean-card);
  border-radius: 8px;
}
.code-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
  color: var(--kean-text);
  font-size: 14px;
}
.captcha-hint {
  padding: 12px 16px 0;
  color: #ef4444;
  font-size: 13px;
}
</style>
