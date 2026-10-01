<script setup lang="ts">
import { register, fetchTurnstileConfig } from "@/api/auth";
import { listCampuses, listProvinces, listSchools } from "@/api/catalog";
import { sendSms } from "@/api/sms";
import CodeBoxes from "@/components/CodeBoxes.vue";
import TurnstileChallenge from "@/components/TurnstileChallenge.vue";
import { normalizeQqEmail } from "@/utils/qqEmail";
import { useToast } from "wot-design-uni";
import { onMounted, onUnmounted, reactive, ref, watch } from "vue";

const toast = useToast();
const loading = ref(false);
const sending = ref(false);
const countdown = ref(0);
const formRef = ref();
const turnstileRef = ref<{ reset: () => void } | null>(null);
let timer: ReturnType<typeof setInterval> | null = null;
const model = reactive({
  username: "",
  password: "",
  nickname: "",
  email: "",
  smsCode: "",
  turnstileToken: "",
  gender: "" as string,
  provinceId: "" as number | string,
  schoolId: "" as number | string,
  campusId: "" as number | string
});
/** 隐私政策同意勾选。未勾选不允许提交（见 handleRegister 的最后一关）。 */
const agreed = ref(false);
const captcha = reactive({
  enabled: true,
  siteKey: "",
  loaded: false
});

const provinceColumns = ref<{ label: string; value: number }[]>([]);
const schoolColumns = ref<{ label: string; value: number }[]>([]);
const campusColumns = ref<{ label: string; value: number }[]>([]);
const genderColumns = [
  { label: "男", value: "MALE" },
  { label: "女", value: "FEMALE" }
];

async function loadProvinces() {
  const provinces = await listProvinces();
  provinceColumns.value = provinces.map((item) => ({ label: item.name, value: item.id }));
}

async function loadSchools() {
  const provinceId = Number(model.provinceId);
  if (!provinceId) {
    schoolColumns.value = [];
    model.schoolId = "";
    campusColumns.value = [];
    model.campusId = "";
    return;
  }
  const schools = await listSchools(provinceId);
  schoolColumns.value = schools.map((item) => ({ label: item.name, value: item.id }));
  if (!schools.some((item) => item.id === Number(model.schoolId))) {
    model.schoolId = schools[0]?.id || "";
  }
}

async function loadCampuses() {
  const schoolId = Number(model.schoolId);
  if (!schoolId) {
    campusColumns.value = [];
    model.campusId = "";
    return;
  }
  const campuses = await listCampuses(schoolId);
  campusColumns.value = campuses.map((item) => ({ label: item.name, value: item.id }));
  if (!campuses.some((item) => item.id === Number(model.campusId))) {
    model.campusId = campuses[0]?.id || "";
  }
}

watch(
  () => model.provinceId,
  () => {
    loadSchools().catch(() => undefined);
  }
);

watch(
  () => model.schoolId,
  () => {
    loadCampuses().catch(() => undefined);
  }
);

onMounted(() => {
  loadProvinces().catch(() => undefined);
  fetchTurnstileConfig()
    .then((data) => {
      captcha.enabled = data.enabled;
      captcha.siteKey = data.siteKey || "";
    })
    .catch(() => {
      captcha.enabled = true;
      captcha.siteKey = "";
    })
    .finally(() => {
      captcha.loaded = true;
    });
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

/** 《隐私政策》在 App 内打开（pages/mine/legal 只渲染隐私政策，用户协议不在 App 内） */
function goPrivacy() {
  uni.navigateTo({ url: "/pages/mine/legal" });
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
    await sendSms({ email, scene: "REGISTER", turnstileToken: model.turnstileToken || undefined });
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

function handleRegister() {
  formRef.value
    .validate()
    .then(async ({ valid }: { valid: boolean }) => {
      if (!valid) {
        return;
      }
      const email = normalizeQqEmail(model.email);
      if (!email) {
        toast.error("请填写 5-11 位 QQ 号");
        return;
      }
      if (!/^\d{6}$/.test(model.smsCode)) {
        toast.error("请填写 6 位验证码");
        return;
      }
      if (!requireCaptcha()) {
        return;
      }
      // 最后一关：必须已阅读并同意《隐私政策》。未勾选不提交，提示风格与本页其它校验一致。
      if (!agreed.value) {
        toast.error("请先阅读并同意《隐私政策》");
        return;
      }
      loading.value = true;
      try {
        await register({
          username: model.username,
          password: model.password,
          nickname: model.nickname,
          gender: String(model.gender),
          schoolId: Number(model.schoolId),
          campusId: Number(model.campusId),
          email,
          smsCode: model.smsCode,
          turnstileToken: model.turnstileToken || undefined
        });
        toast.success("注册成功，请登录");
        setTimeout(() => {
          uni.redirectTo({ url: "/pages/auth/login" });
        }, 400);
      } catch (error) {
        turnstileRef.value?.reset();
        toast.error((error as Error).message || "注册失败");
      } finally {
        loading.value = false;
      }
    })
    .catch(() => undefined);
}
</script>

<template>
  <view class="page">
    <wd-navbar title="注册" left-arrow safe-area-inset-top @click-left="uni.navigateBack()" />
    <view class="hero">
      <view class="title">创建账号</view>
      <view class="sub">仅支持同校学生注册，需 QQ 邮箱验证码</view>
    </view>
    <wd-form ref="formRef" :model="model" error-type="toast">
      <wd-cell-group border>
        <wd-input
          v-model="model.username"
          label="用户名"
          label-width="80px"
          prop="username"
          clearable
          placeholder="4-32 位字母数字下划线"
          :rules="[{ required: true, message: '请填写用户名' }]"
        />
        <wd-input
          v-model="model.password"
          label="密码"
          label-width="80px"
          prop="password"
          show-password
          clearable
          placeholder="8-32 位密码"
          :rules="[{ required: true, message: '请填写密码' }]"
        />
        <wd-input
          v-model="model.nickname"
          label="昵称"
          label-width="80px"
          prop="nickname"
          clearable
          placeholder="请输入昵称"
          :rules="[{ required: true, message: '请填写昵称' }]"
        />
        <wd-picker
          v-model="model.gender"
          label="性别"
          label-width="80px"
          prop="gender"
          :columns="genderColumns"
          :rules="[{ required: true, message: '请选择性别' }]"
        />
        <wd-input
          v-model="model.email"
          label="QQ 邮箱"
          label-width="80px"
          prop="email"
          clearable
          placeholder="请填写正确的 QQ 邮箱"
          :rules="[{ required: true, message: '请填写 QQ 号' }]"
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
        box-id="kean-ts-register"
        :site-key="captcha.siteKey"
        v-model="model.turnstileToken"
      />
      <view v-else-if="captcha.loaded && captcha.enabled" class="captcha-hint">人机验证未配置，暂无法注册</view>
      <wd-cell-group border>
        <wd-picker
          v-model="model.provinceId"
          label="省份"
          label-width="80px"
          prop="provinceId"
          :columns="provinceColumns"
          :rules="[{ required: true, message: '请先选择省份' }]"
        />
        <wd-picker
          v-model="model.schoolId"
          label="学校"
          label-width="80px"
          prop="schoolId"
          :disabled="!model.provinceId"
          :columns="schoolColumns"
          :rules="[{ required: true, message: '请选择学校' }]"
        />
        <wd-picker
          v-model="model.campusId"
          label="校区"
          label-width="80px"
          prop="campusId"
          :columns="campusColumns"
          :rules="[{ required: true, message: '请选择校区' }]"
        />
      </wd-cell-group>
      <view class="agree">
        <wd-checkbox v-model="agreed" shape="circle">
          <text class="agree-text">我已阅读并同意</text>
          <!-- 链接放在勾选框内，点它在跳转前先阻止冒泡，避免顺手把勾选状态也切了 -->
          <text class="agree-link" @click.stop="goPrivacy">《隐私政策》</text>
        </wd-checkbox>
      </view>
      <view class="footer">
        <wd-button type="primary" size="large" block :loading="loading" @click="handleRegister">
          注册
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
.hero {
  padding: 32px 24px 16px;
}
.title {
  font-size: 24px;
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
.agree {
  padding: 16px 16px 0;
}
.agree-text {
  color: var(--kean-muted);
  font-size: 13px;
}
.agree-link {
  color: var(--kean-primary);
  font-size: 13px;
}
</style>
