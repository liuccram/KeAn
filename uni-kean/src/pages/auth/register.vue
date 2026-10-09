<script setup lang="ts">
import { register, fetchTurnstileConfig } from "@/api/auth";
import { listProvinces, listSchools } from "@/api/catalog";
import { sendSms } from "@/api/sms";
import CodeBoxes from "@/components/CodeBoxes.vue";
import TurnstileChallenge from "@/components/TurnstileChallenge.vue";
import { QQ_EMAIL_HINT, normalizeQqEmail } from "@/utils/qqEmail";
import { useToast } from "wot-design-uni";
import { onMounted, onUnmounted, reactive, ref, watch } from "vue";

const toast = useToast();
const loading = ref(false);
const sending = ref(false);
const countdown = ref(0);
/** 三步各自的表单校验入口：每一步只校验自己这一块的字段，不用一次性校验整张长表单 */
const step1Ref = ref();
const step2Ref = ref();
const step3Ref = ref();
const turnstileRef = ref<{ reset: () => void } | null>(null);
let timer: ReturnType<typeof setInterval> | null = null;

/**
 * 注册分三步，但仍然是**同一个页面、同一个 model**：不拆路由，避免"上一步填的内容"
 * 在多个页面之间来回同步。step 只决定显示哪一块，回上一步时 model 原样保留。
 */
const step = ref(1);
const stepItems = [
  { no: 1, title: "账号" },
  { no: 2, title: "邮箱验证" },
  { no: 3, title: "其他信息" }
];

const model = reactive({
  // —— 第 1 步 · 账号（这一步不出现邮箱）——
  username: "",
  password: "",
  confirmPassword: "",
  gender: "" as string,
  /** 昵称选填：不填时服务端用雪花算法自动分配「课安用户xxxxxx」 */
  nickname: "",
  // —— 第 2 步 · 邮箱验证 ——
  email: "",
  smsCode: "",
  // —— 第 3 步 · 其他信息 ——
  provinceId: "" as number | string,
  schoolId: "" as number | string,
  campusText: "",
  // —— 真人验证 token：第 2 步发码与第 3 步提交共用 ——
  turnstileToken: ""
});
/** 隐私政策同意勾选。未勾选不允许提交（见 handleRegister 的最后一关）。 */
const agreed = ref(false);
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

const provinceColumns = ref<{ label: string; value: number }[]>([]);
const schoolColumns = ref<{ label: string; value: number }[]>([]);
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
    return;
  }
  const schools = await listSchools(provinceId);
  schoolColumns.value = schools.map((item) => ({ label: item.name, value: item.id }));
  if (!schools.some((item) => item.id === Number(model.schoolId))) {
    model.schoolId = schools[0]?.id || "";
  }
}

watch(
  () => model.provinceId,
  () => {
    loadSchools().catch(() => undefined);
  }
);

onMounted(() => {
  loadProvinces().catch(() => undefined);
  fetchTurnstileConfig()
    .then((data) => {
      captcha.enabled = data.enabled === true;
      captcha.siteKey = data.siteKey || "";
      captcha.error = false;
    })
    .catch(() => {
      // 配置没拿到：不能伪装成"验证已开启"，也不能说成"未配置"，单独标记为读取失败。
      captcha.enabled = false;
      captcha.siteKey = "";
      captcha.error = true;
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

/** 《隐私政策》在 App 内打开（pages/mine/legal 只渲染隐私政策，用户协议不在 App 内） */
function goPrivacy() {
  uni.navigateTo({ url: "/pages/mine/legal" });
}

/** 当前这一步的 wd-form 实例（每步一个，validate() 只会校验本步的字段） */
function currentFormRef() {
  if (step.value === 1) {
    return step1Ref.value;
  }
  if (step.value === 2) {
    return step2Ref.value;
  }
  return step3Ref.value;
}

/** 先跑本步的字段规则（必填等），不通过就不放行 */
async function validateCurrentStep(): Promise<boolean> {
  const form = currentFormRef();
  if (!form) {
    return false;
  }
  try {
    const result = await form.validate();
    return result?.valid === true;
  } catch {
    return false;
  }
}

/** 「下一步」：先校验本步，再放行；回上一步不重置 model，已填内容自然保留 */
async function nextStep() {
  if (!(await validateCurrentStep())) {
    return;
  }
  if (step.value === 1) {
    // 第 1 步专属规则：两次密码必须一致
    if (model.password !== model.confirmPassword) {
      toast.error("两次输入的密码不一致");
      return;
    }
    step.value = 2;
    return;
  }
  if (step.value === 2) {
    // 必须是完整 QQ 邮箱：只填 QQ 号、缺 @、非 qq.com 域名一律不放行
    const email = normalizeQqEmail(model.email);
    if (!email) {
      toast.error(QQ_EMAIL_HINT);
      return;
    }
    if (!/^\d{6}$/.test(model.smsCode)) {
      toast.error("请填写 6 位验证码");
      return;
    }
    // 回写规范化后的邮箱：返回上一步看到的是完整邮箱，提交时也用它
    model.email = email;
    step.value = 3;
  }
}

function prevStep() {
  if (step.value > 1) {
    step.value -= 1;
    // 回到第 1 步时真人验证控件会被卸载（v-if="step >= 2"）：连同 token 一起清掉，
    // 免得下次进第 2 步时拿残留 token 抢跑通过验证。
    if (step.value === 1) {
      model.turnstileToken = "";
    }
  }
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
    await sendSms({ email, scene: "REGISTER", turnstileToken: model.turnstileToken || undefined });
    // 发码成功即作废本次真人验证 token：第 3 步提交前必须重新完成验证（保持既有逻辑不变）
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

async function handleRegister() {
  if (!(await validateCurrentStep())) {
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
      // 昵称选填：留空（不传）由服务端用雪花算法自动分配
      nickname: model.nickname.trim() || undefined,
      gender: String(model.gender),
      schoolId: Number(model.schoolId),
      campusText: model.campusText.trim() || null,
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
}
</script>

<template>
  <view class="page">
    <wd-navbar title="注册" left-arrow safe-area-inset-top @click-left="uni.navigateBack()" />
    <view class="hero">
      <view class="title">创建账号</view>
      <view class="sub">仅支持同校学生注册，需 QQ 邮箱验证码</view>
    </view>
    <!-- 步骤条 1-2-3 -->
    <view class="steps-wrap">
      <wd-steps :active="step - 1">
        <wd-step v-for="item in stepItems" :key="item.no" :title="item.title" />
      </wd-steps>
    </view>

    <!-- 第 1 步 · 账号（此步不出现邮箱） -->
    <wd-form v-if="step === 1" key="step-account" ref="step1Ref" :model="model" error-type="toast">
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
          v-model="model.confirmPassword"
          label="确认密码"
          label-width="80px"
          prop="confirmPassword"
          show-password
          clearable
          placeholder="请再次输入密码"
          :rules="[{ required: true, message: '请再次输入密码' }]"
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
          v-model="model.nickname"
          label="昵称"
          label-width="80px"
          prop="nickname"
          clearable
          :maxlength="32"
          placeholder="选填，不填自动分配"
        />
      </wd-cell-group>
      <view class="step-tip">昵称不填也可以：注册成功后系统会分配一个「课安用户xxxxxx」，之后可在个人资料里修改。</view>
    </wd-form>

    <!-- 第 2 步 · 邮箱验证：必须是完整 QQ 邮箱（如 12345678@qq.com） -->
    <wd-form v-else-if="step === 2" key="step-email" ref="step2Ref" :model="model" error-type="toast">
      <wd-cell-group border>
        <wd-input
          v-model="model.email"
          label="QQ 邮箱"
          label-width="80px"
          prop="email"
          clearable
          placeholder="例如 12345678@qq.com"
          :rules="[{ required: true, message: '请填写完整 QQ 邮箱' }]"
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
      <view class="step-tip">必须填写完整邮箱（例如 12345678@qq.com）：只填 QQ 号通不过校验。</view>
    </wd-form>

    <!-- 第 3 步 · 其他信息：学校必填下拉、校区选填手输 -->
    <wd-form v-else key="step-other" ref="step3Ref" :model="model" error-type="toast">
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
        <wd-input
          v-model="model.campusText"
          label="校区"
          label-width="80px"
          prop="campusText"
          clearable
          :maxlength="50"
          placeholder="选填，例如 西校区"
        />
      </wd-cell-group>
    </wd-form>

    <!--
      真人验证：第 2 步发码和第 3 步提交都需要 token，用**同一个控件实例**（不重复渲染 iframe）。
      发码成功后既有逻辑会 reset() 清空 token，所以第 3 步提交前必须重新完成验证。
    -->
    <view v-if="step >= 2">
      <TurnstileChallenge
        v-if="captcha.enabled && captcha.siteKey"
        ref="turnstileRef"
        box-id="kean-ts-register"
        :site-key="captcha.siteKey"
        v-model="model.turnstileToken"
      />
      <!-- 配置请求失败：单独一行，且绝不在失败时渲染验证控件（H5 与非 H5 都渲染这个普通 view） -->
      <view v-else-if="captcha.error" class="captcha-hint">真人验证配置读取失败，请稍后重试</view>
      <!-- 只有后端明确 enabled:true、却没给 siteKey 才是"未配置"；enabled:false 时这里也不显示 -->
      <view v-else-if="captcha.loaded && captcha.enabled" class="captcha-hint">人机验证未配置，暂无法注册</view>
    </view>

    <!-- 第 3 步 · 隐私政策（未勾选不能提交） -->
    <view v-if="step === 3" class="agree">
      <wd-checkbox v-model="agreed" shape="circle">
        <text class="agree-text">我已阅读并同意</text>
        <!-- 链接放在勾选框内，点它在跳转前先阻止冒泡，避免顺手把勾选状态也切了 -->
        <text class="agree-link" @click.stop="goPrivacy">《隐私政策》</text>
      </wd-checkbox>
    </view>

    <view class="footer">
      <view v-if="step > 1" class="footer-prev">
        <wd-button size="large" plain block @click="prevStep">上一步</wd-button>
      </view>
      <view class="footer-next">
        <wd-button v-if="step < 3" type="primary" size="large" block @click="nextStep">下一步</wd-button>
        <wd-button v-else type="primary" size="large" block :loading="loading" @click="handleRegister">
          提交注册
        </wd-button>
      </view>
    </view>
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
.steps-wrap {
  padding: 8px 16px 0;
}
.step-tip {
  padding: 10px 16px 0;
  color: var(--kean-muted);
  font-size: 12px;
  line-height: 18px;
}
.footer {
  display: flex;
  gap: 12px;
  padding: 24px 16px;
}
.footer-prev {
  flex: 1;
}
.footer-next {
  flex: 2;
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
