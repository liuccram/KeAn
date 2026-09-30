<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage, type FormInstance, type FormRules } from "element-plus";
import { fetchTurnstileConfig, login } from "@/api/auth";
import TurnstileBox from "@/components/TurnstileBox.vue";
import { useUserStore } from "@/stores/user";

const router = useRouter();
const route = useRoute();
const userStore = useUserStore();
const formRef = ref<FormInstance>();
const turnstileRef = ref<{ reset: () => void } | null>(null);
const loading = ref(false);
const form = reactive({
  username: "admin",
  password: "",
  turnstileToken: ""
});
const captcha = reactive({
  enabled: true,
  siteKey: "",
  loaded: false
});

const rules: FormRules = {
  username: [{ required: true, message: "请输入用户名", trigger: "blur" }],
  password: [{ required: true, message: "请输入密码", trigger: "blur" }]
};

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

async function handleLogin() {
  const valid = await formRef.value?.validate().catch(() => false);
  if (!valid) {
    return;
  }
  if (captcha.enabled && !form.turnstileToken) {
    ElMessage.error(captcha.siteKey ? "请完成真人验证" : "人机验证未配置");
    return;
  }
  loading.value = true;
  try {
    const data = await login(form.username, form.password, form.turnstileToken || undefined);
    if (data.user.role !== "ADMIN") {
      ElMessage.error("该账号不是管理员");
      turnstileRef.value?.reset();
      return;
    }
    userStore.setLogin(data.token, data.user);
    if (data.user.mustChangePassword) {
      ElMessage.warning("请先修改初始密码后再使用管理端");
    } else {
      ElMessage.success("登录成功");
    }
    const redirect = typeof route.query.redirect === "string" ? route.query.redirect : "/dashboard";
    await router.replace(redirect);
  } catch {
    turnstileRef.value?.reset();
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <div class="login-page">
    <div class="panel">
      <div class="brand">
        <span class="mark">
          <svg viewBox="0 0 32 32" width="28" height="28">
            <path d="M16 3.5c4.2 2.4 8.2 2.7 11 2.7v10.2c0 6.1-3.9 10.3-11 13.1-7.1-2.8-11-7-11-13.1V6.2c2.8 0 6.8-.3 11-2.7z" fill="#3b82f6" />
            <path d="M11 16.2l3.1 3.1 6.9-7" fill="none" stroke="#fff" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
        </span>
        <h1>课安</h1>
      </div>
      <p class="sub">管理后台登录</p>
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @keyup.enter="handleLogin">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" placeholder="admin" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input v-model="form.password" type="password" show-password placeholder="请输入密码" />
        </el-form-item>
        <el-form-item v-if="captcha.enabled && captcha.siteKey">
          <TurnstileBox ref="turnstileRef" :site-key="captcha.siteKey" v-model="form.turnstileToken" />
        </el-form-item>
        <p v-else-if="captcha.loaded && captcha.enabled" class="captcha-hint">人机验证未配置，暂无法登录</p>
        <el-button type="primary" :loading="loading" class="submit" @click="handleLogin">登录</el-button>
      </el-form>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: radial-gradient(circle at 20% 20%, #1d4ed8 0, transparent 32%), linear-gradient(180deg, #0d1b36 0%, #122445 100%);
}
.panel {
  width: 400px;
  background: #fff;
  border-radius: 16px;
  padding: 32px 32px 36px;
  box-shadow: 0 20px 50px rgba(2, 6, 23, 0.25);
}
.brand {
  display: flex;
  align-items: center;
  gap: 10px;
}
.mark {
  width: 40px;
  height: 40px;
  border-radius: 12px;
  background: #eff6ff;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
h1 {
  margin: 0;
  font-size: 24px;
}
.sub {
  margin: 10px 0 24px;
  color: #64748b;
}
.submit {
  width: 100%;
  height: 40px;
}
.captcha-hint {
  margin: 0 0 12px;
  color: #ef4444;
  font-size: 13px;
}
</style>
