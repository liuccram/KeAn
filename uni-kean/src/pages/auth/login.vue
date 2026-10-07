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
      <!-- 配置请求失败：单独一行，且绝不在失败时渲染验证控件（H5 与非 H5 都渲染这个普通 view） -->
      <view v-else-if="captcha.error" class="captcha-hint">真人验证配置读取失败，请稍后重试</view>
      <!-- 只有后端明确 enabled:true、却没给 siteKey 才是"未配置"；enabled:false 时这里也不显示 -->
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
  background: var(--kean-bg);
}
.hero {
  padding: 48px 24px 24px;
}
.title {
  font-size: 28px;
  font-weight: 600;
  color: var(--kean-text);
}
.sub {
  margin-top: 8px;
  color: var(--kean-muted);
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
  color: var(--kean-primary);
  font-size: 14px;
}
.captcha-hint {
  padding: 12px 16px 0;
  color: #ef4444;
  font-size: 13px;
}
</style>
