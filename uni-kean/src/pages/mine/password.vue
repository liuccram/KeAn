<script setup lang="ts">
import { fetchMe } from "@/api/auth";
import { changePassword, sendSms } from "@/api/sms";
import CodeBoxes from "@/components/CodeBoxes.vue";
import PageIntro from "@/components/PageIntro.vue";
import { onMounted, onUnmounted, reactive, ref } from "vue";
import { useToast } from "wot-design-uni";

const toast = useToast();
const loading = ref(false);
const sending = ref(false);
const countdown = ref(0);
const formRef = ref();
const boundEmail = ref("");
let timer: ReturnType<typeof setInterval> | null = null;
const model = reactive({
  smsCode: "",
  newPassword: "",
  confirmPassword: ""
});

onMounted(async () => {
  try {
    const me = await fetchMe();
    boundEmail.value = me.email || "";
  } catch (error) {
    toast.error((error as Error).message || "无法获取绑定邮箱");
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

async function handleSendSms() {
  if (!boundEmail.value) {
    toast.error("当前账号未绑定 QQ 邮箱");
    return;
  }
  if (sending.value || countdown.value > 0) {
    return;
  }
  sending.value = true;
  try {
    await sendSms({ scene: "CHANGE_PASSWORD" });
    startCountdown();
    toast.success(`验证码已发送到 ${boundEmail.value}`);
  } catch (error) {
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
      if (!/^\d{6}$/.test(model.smsCode)) {
        toast.error("请填写 6 位验证码");
        return;
      }
      loading.value = true;
      try {
        await changePassword({
          smsCode: model.smsCode,
          newPassword: model.newPassword
        });
        toast.success("密码已修改");
        setTimeout(() => {
          uni.navigateBack();
        }, 400);
      } catch (error) {
        toast.error((error as Error).message || "修改失败");
      } finally {
        loading.value = false;
      }
    })
    .catch(() => undefined);
}
</script>

<template>
  <view class="page password-page">
    <PageIntro eyebrow="ACCOUNT SECURITY" title="修改密码" description="完成邮箱验证，为账号换一把新钥匙" marker="安全" />
    <view class="security-summary">
      <view class="security-symbol">盾</view>
      <view class="security-copy"><text class="security-title">绑定邮箱</text><text class="security-email">{{ boundEmail || '正在读取绑定邮箱…' }}</text></view>
      <view class="security-state">已保护</view>
    </view>
    <wd-form ref="formRef" :model="model" error-type="toast">
      <view class="password-section">
        <view class="form-heading"><text class="form-step">01</text><view><text class="form-title">验证你的身份</text><text class="form-sub">验证码将发送到绑定邮箱</text></view></view>
        <view class="code-block">
        <view class="code-head">
          <text>邮箱验证码</text>
          <wd-button size="small" :loading="sending" :disabled="countdown > 0" @click="handleSendSms">
            {{ countdown > 0 ? `${countdown}s` : "获取验证码" }}
          </wd-button>
        </view>
        <CodeBoxes v-model="model.smsCode" />
        </view>
      </view>
      <view class="password-section">
        <view class="form-heading"><text class="form-step">02</text><view><text class="form-title">设置新密码</text><text class="form-sub">请设置 8–32 位的新密码</text></view></view>
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
      </view>
      <view class="hint">密码修改后，其他设备可能需要重新登录。</view>
      <view class="footer">
        <wd-button type="primary" size="large" block :loading="loading" @click="handleSubmit">
          确认修改
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
  padding-top: 12px;
}
.security-summary,.password-section { margin: 14px 16px 0; padding: 15px; border: 1px solid rgba(224,232,243,.9); border-radius: 17px; background: var(--kean-card); box-shadow: 0 5px 16px rgba(34,59,97,.035); }
.security-summary { display: flex; align-items: center; gap: 11px; }
.security-symbol { width: 38px; height: 38px; display: flex; align-items: center; justify-content: center; border-radius: 12px; color: #5484bd; background: #edf4ff; font-size: 12px; font-weight: 700; }
.security-copy { display: flex; flex: 1; flex-direction: column; min-width: 0; }.security-title { color: var(--kean-muted); font-size: 10px; }.security-email { margin-top: 4px; color: var(--kean-text); font-size: 13px; font-weight: 600; overflow-wrap: anywhere; }.security-state { padding: 5px 8px; border-radius: 10px; color: #438772; background: #eaf6f1; font-size: 10px; }
.password-section :deep(.wd-cell-group) { overflow: hidden; border-radius: 11px; }.form-heading { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }.form-step { width: 28px; height: 28px; display: flex; align-items: center; justify-content: center; border-radius: 9px; color: #6487bc; background: #edf4ff; font-size: 10px; font-weight: 700; }.form-title { display: block; color: var(--kean-text); font-size: 14px; font-weight: 700; }.form-sub { display: block; margin-top: 3px; color: var(--kean-muted); font-size: 10px; }
.hint {
  padding: 12px 16px 0;
  color: var(--kean-muted);
  font-size: 12px;
  line-height: 1.6;
}
.footer {
  padding: 24px 16px;
}
.code-block {
  margin: 0;
  padding: 13px 14px 14px;
  background: var(--kean-card);
  border: 1px solid rgba(224,232,243,.9);
  border-radius: 13px;
}
.code-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
  color: var(--kean-text);
  font-size: 14px;
}
</style>
