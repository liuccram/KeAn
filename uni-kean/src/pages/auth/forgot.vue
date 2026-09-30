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
const captcha = reactive({
  enabled: true,
  siteKey: "",
  loaded: false
});

onMounted(async () => {
  try {
    const data = await fetchTurnstileConfig();
    captcha.enabled = data.enabled;
    captcha.siteKey = data.siteKey || "";
  } catch {
    captcha.enabled = true;
    captcha.siteKey = "";
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
  if (captcha.enabled && !model.turnstileToken) {
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
        toast.error("请填写正确的qq邮箱");
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
  background: #f5f6f8;
}
.footer {
  padding: 24px 16px;
}
.code-block {
  margin: 12px 16px 0;
  padding: 14px 16px 16px;
  background: #fff;
  border-radius: 8px;
}
.code-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
  color: #1d2129;
  font-size: 14px;
}
.captcha-hint {
  padding: 12px 16px 0;
  color: #ef4444;
  font-size: 13px;
}
</style>
