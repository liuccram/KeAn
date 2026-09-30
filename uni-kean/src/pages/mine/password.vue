<script setup lang="ts">
import { fetchMe } from "@/api/auth";
import { changePassword, sendSms } from "@/api/sms";
import CodeBoxes from "@/components/CodeBoxes.vue";
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
  <view class="page">
    <wd-form ref="formRef" :model="model" error-type="toast">
      <wd-cell-group border>
        <wd-cell title="绑定邮箱" :value="boundEmail || '未绑定'" />
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
      <view class="hint">验证码将发到当前绑定邮箱 {{ boundEmail || "（未绑定）" }}。</view>
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
  background: #f5f6f8;
  padding-top: 12px;
}
.hint {
  padding: 12px 16px 0;
  color: #86909c;
  font-size: 12px;
  line-height: 1.6;
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
</style>
