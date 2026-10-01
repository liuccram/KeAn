<script setup lang="ts">
import { fetchTurnstileConfig, login } from "@/api/auth";
import TurnstileChallenge from "@/components/TurnstileChallenge.vue";
import { useUserStore } from "@/store/user";
import { refreshMessageBadge } from "@/utils/messageBadge";
import { startRealtime } from "@/utils/realtime";
import { onLoad } from "@dcloudio/uni-app";
import { useToast } from "wot-design-uni";
import { onMounted, reactive, ref } from "vue";

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
const captcha = reactive({
  enabled: true,
  siteKey: "",
  loaded: false
});

onLoad((query) => {
  redirect.value = String(query?.redirect || "");
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

function afterLogin() {
  if (redirect.value === "publish") {
    uni.switchTab({ url: "/pages/publish/index" });
    return;
  }
  uni.switchTab({ url: "/pages/mine/index" });
}

function onBack() {
  const pages = getCurrentPages();
  if (pages.length > 1) {
    uni.navigateBack();
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
  <view class="page">
    <wd-navbar title="登录" left-arrow safe-area-inset-top @click-left="onBack" />
    <view class="hero">
      <view class="title">课安</view>
      <view class="sub">校园临时代课互助</view>
    </view>
    <wd-form ref="formRef" :model="model" error-type="toast">
      <wd-cell-group border>
        <wd-input
          v-model="model.username"
          label="用户名"
          label-width="80px"
          prop="username"
          clearable
          placeholder="请输入用户名"
          :rules="[{ required: true, message: '请填写用户名' }]"
        />
        <wd-input
          v-model="model.password"
          label="密码"
          label-width="80px"
          prop="password"
          show-password
          clearable
          placeholder="请输入密码"
          :rules="[{ required: true, message: '请填写密码' }]"
        />
      </wd-cell-group>
      <TurnstileChallenge
        v-if="captcha.enabled && captcha.siteKey"
        ref="turnstileRef"
        :site-key="captcha.siteKey"
        v-model="model.turnstileToken"
      />
      <view v-else-if="captcha.loaded && captcha.enabled" class="captcha-hint">人机验证未配置，暂无法登录</view>
      <view class="footer">
        <wd-button type="primary" size="large" block :loading="loading" @click="handleLogin">
          登录
        </wd-button>
        <view class="links">
          <text class="link" @click="goRegister">注册账号</text>
          <text class="link" @click="goForgot">忘记密码</text>
        </view>
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
.hero {
  padding: 48px 24px 24px;
}
.title {
  font-size: 28px;
  font-weight: 600;
  color: #1d2129;
}
.sub {
  margin-top: 8px;
  color: #86909c;
  font-size: 14px;
}
.footer {
  padding: 24px 16px;
}
.links {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 18px;
  padding: 0 4px;
}
.link {
  color: #4d80f0;
  font-size: 14px;
}
.captcha-hint {
  padding: 12px 16px 0;
  color: #ef4444;
  font-size: 13px;
}
</style>
