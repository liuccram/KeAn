<script setup lang="ts">
import { register, fetchTurnstileConfig } from "@/api/auth";
import { listProvinces, listSchools } from "@/api/catalog";
import { sendSms } from "@/api/sms";
import CodeBoxes from "@/components/CodeBoxes.vue";
import TurnstileChallenge from "@/components/TurnstileChallenge.vue";
import { goBack } from "@/utils/authNav";
import { QQ_EMAIL_HINT, normalizeQqEmail } from "@/utils/qqEmail";
import { t, tf } from "@/utils/i18n";
import { useToast } from "wot-design-uni";
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from "vue";

const toast = useToast();
const loading = ref(false);
const sending = ref(false);
const countdown = ref(0);
/** 四步各自的表单校验入口：每一步只校验自己这一块的字段，不用一次性校验整张长表单 */
const step1Ref = ref();
const step2Ref = ref();
const step3Ref = ref();
const step4Ref = ref();
/**
 * 三个 wd-picker 的实例，只为「选完确认后主动关掉弹层」兜底。
 * wd-picker 的 defineExpose 是 { open, close, setLoading }，close() 内部就是把自己的
 * popupShow 置 false，不碰选中值、不碰数据源 —— 纯展示层的关闭动作。
 */
const genderPickerRef = ref<{ close: () => void } | null>(null);
const provincePickerRef = ref<{ close: () => void } | null>(null);
const schoolPickerRef = ref<{ close: () => void } | null>(null);
const turnstileRef = ref<{ reset: () => void } | null>(null);
let timer: ReturnType<typeof setInterval> | null = null;

/**
 * 注册分 4 步，但仍然是**同一个页面、同一个 model**：不拆路由，避免"上一步填的内容"
 * 在多个页面之间来回同步。step 只决定显示哪一块，来回翻页时 model 原样保留。
 *
 * 为什么是 4 步而不是 3 步（与本轮设计稿「① 创建账号 ② 学生认证 ③ 完善资料」的差异）：
 * 原页面第 1 步（用户名+密码+确认密码+性别+昵称）和第 2 步（邮箱+验证码）的字段
 * 一共 7 个，硬塞进设计稿的「① 创建账号」一步会让首屏出现 7 个输入胶囊，
 * 既违背「大量留白」也会让注册页第一屏在 iPhone SE 上溢出。所以把「账号」
 * 拆成 **① 账号（用户名+密码+确认密码）** 与 **② 邮箱验证（邮箱+验证码）**，
 * 「学生认证」「完善资料」严格按设计稿执行。字段一个没丢，见文件末尾的映射说明。
 */
const step = ref(1);
/**
 * 四步各自的「标题 + 一句短语」：只做文案国际化，步骤数量与顺序（4 步）都不变。
 * ⚠️ 现在**不再**用它渲染一整条四步进度条，只用 `stepItems[step - 1]` 取「当前这一步」那一条，
 * 每页只显示当前步（见 currentStep）。数组本身因此必须保持 1→4 的顺序，索引即步号。
 */
const stepItems = computed(() => [
  { no: 1, title: t("authStepAccount"), fields: t("authStepFieldAccount") },
  { no: 2, title: t("authStepEmail"), fields: t("authStepFieldEmail") },
  { no: 3, title: t("authStepStudent"), fields: t("authStepFieldStudent") },
  { no: 4, title: t("authStepProfile"), fields: t("authStepFieldProfile") }
]);
/** 当前这一步（step 恒为 1~4，数组恒为 4 条；兜底取第 1 条只为类型安全） */
const currentStep = computed(() => stepItems.value[step.value - 1] || stepItems.value[0]);

const model = reactive({
  // —— 第 1 步 · 账号 ——
  username: "",
  password: "",
  confirmPassword: "",
  // —— 第 2 步 · 邮箱验证 ——
  email: "",
  smsCode: "",
  // —— 第 3 步 · 学生认证 ——
  provinceId: "" as number | string,
  schoolId: "" as number | string,
  campusText: "",
  // —— 第 4 步 · 完善资料 ——
  gender: "" as string,
  /** 昵称选填：不填时服务端用雪花算法自动分配「课安用户xxxxxx」 */
  nickname: "",
  // —— 真人验证 token：第 2 步发码与第 4 步提交共用（控件只挂载一次，用 v-show 切换显隐） ——
  turnstileToken: ""
});
/** 隐私政策同意勾选。未勾选不允许提交（见 handleRegister 的最后一关）。 */
const agreed = ref(false);
/**
 * 页面文案：统一走 i18n，不在模板里硬编码中文。
 * 用 computed 包一层，语言偏好变更后重新进入页面就会整体跟着变。
 */
const i18n = computed(() => ({
  brand: t("authBrand"),
  headline: t("authRegisterHeadline"),
  heroSub: t("authRegisterSub"),
  subTip: t("authRegisterSubTip"),
  usernamePlaceholder: t("authUsernamePlaceholder"),
  passwordPlaceholder: t("authPasswordPlaceholder"),
  confirmPlaceholder: t("authPasswordConfirmPlaceholder"),
  gender: t("authGender"),
  genderPlaceholder: t("authGenderPlaceholder"),
  nickname: t("authNickname"),
  nicknamePlaceholder: t("authNicknamePlaceholder"),
  nicknameTip: t("authNicknameTip"),
  emailPlaceholder: t("authEmailExample"),
  smsLabel: t("authSmsLabel"),
  sendSms: t("authSendSms"),
  agreePrefix: t("authAgreePrefix"),
  privacy: t("authPrivacyPolicy"),
  prevStep: t("authPrevStep"),
  nextStep: t("authNextStep"),
  submit: t("authSubmitRegister"),
  province: t("authProvince"),
  provincePlaceholder: t("authProvincePlaceholder"),
  school: t("authSchool"),
  schoolPlaceholder: t("authSchoolPlaceholder"),
  campusPlaceholder: t("authCampusPlaceholder"),
  emailTip: t("authEmailTip"),
  slogan: t("authSloganText"),
  captchaFailed: t("authCaptchaFailed"),
  captchaMissing: t("authCaptchaMissingRegister"),
  ruleUsername: t("authRuleUsernameRequired"),
  rulePassword: t("authRulePasswordRequired"),
  ruleConfirm: t("authRuleConfirmRequired"),
  ruleGender: t("authRuleGenderRequired"),
  ruleEmail: t("authRuleEmailRequired"),
  ruleProvince: t("authRuleProvinceRequired"),
  ruleSchool: t("authRuleSchoolRequired"),
  /** 下面两条是从「下一步」搬到「提交前」的补充规则文案（不是新增校验，只是换了个触发时机） */
  rulePasswordMismatch: t("authPasswordMismatch"),
  ruleSmsCodeInvalid: t("authSmsCodeInvalid")
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

/**
 * 返回：**不再直接调 navigateBack**。
 * 本页可能被 navigateTo 压栈进来（栈里有上一页），也可能是 redirectTo / reLaunch /
 * 直接打开 URL 进来的（当前页就是栈底）—— 后一种情况 navigateBack() 会静默失败，
 * 用户就被困在注册页出不去（用户反馈「进入注册页面后就出不来了」）。
 * 统一交给 utils/authNav.ts 的 goBack()：能返回就返回，退无可退就回登录页。
 */
function onBack() {
  goBack();
}

/** 当前这一步的 wd-form 实例（每步一个，validate() 只会校验本步的字段） */
function formRefOf(target: number) {
  if (target === 1) {
    return step1Ref.value;
  }
  if (target === 2) {
    return step2Ref.value;
  }
  if (target === 3) {
    return step3Ref.value;
  }
  return step4Ref.value;
}

/**
 * 切到某一步，并等这一步的 wd-form 挂载完成。
 *
 * 为什么需要它：四步的 wd-form 是 v-if / v-else-if 互斥渲染的，同一时刻只有一个实例存在，
 * 所以「拿四个实例一次性全量校验」在这里做不到，只能切过去逐个校验。
 * 模板 ref 要等渲染 flush 之后才有值，所以必须 await nextTick()。
 * nextTick / validate 都只排微任务、不让出宏任务，浏览器不会在这中间重绘，
 * 用户看不到步骤条闪烁。
 */
async function gotoStep(target: number) {
  if (step.value !== target) {
    step.value = target;
    await nextTick();
  }
}

/**
 * 某一步在 wd-form 字段规则之外的「补充规则」（跨字段/需要规范化的，写不进单字段 rules 里）。
 * 返回 true=通过；不通过时**由它自己负责 toast 提示**，调用方只负责"别放行"。
 *
 * 抽成独立函数是为了让「下一步」（校验当前步）和「提交注册」（全量校验）走**同一套口径**，
 * 避免同一套规则在两处各写一份、改一处漏一处。
 */
function stepExtraRule(target: number): boolean {
  if (target === 1) {
    // 两次密码必须一致：跨字段规则，挂不到 password / confirmPassword 各自的 rules 上
    if (model.password !== model.confirmPassword) {
      toast.error(i18n.value.rulePasswordMismatch);
      return false;
    }
  }
  if (target === 2) {
    // 必须是完整 QQ 邮箱：只填 QQ 号、缺 @、非 qq.com 域名一律不放行
    const email = normalizeQqEmail(model.email);
    if (!email) {
      toast.error(QQ_EMAIL_HINT);
      return false;
    }
    if (!/^\d{6}$/.test(model.smsCode)) {
      toast.error(i18n.value.ruleSmsCodeInvalid);
      return false;
    }
    // 回写规范化后的邮箱：返回上一步看到的是完整邮箱，提交时也用它
    model.email = email;
  }
  return true;
}

/**
 * 只校验某一步，**绝不切页**。
 *
 * 为什么不复用 validateStepForm：那个会先 gotoStep(target) 再校验，是给"全量校验需要切过去拿实例"
 * 用的；而「下一步」按下时用户**已经站在要校验的那一步**了，再切页会把用户甩到别的步骤上，
 * 失败时看起来像"刚填的内容被丢了"。
 *
 * 校验内容 = 本步 wd-form 的字段规则 + stepExtraRule 的补充规则，与提交时的口径完全一致。
 */
async function validateStep(target: number): Promise<boolean> {
  const form = formRefOf(target);
  if (!form) {
    return false;
  }
  try {
    const result = await form.validate();
    if (result?.valid !== true) {
      return false;
    }
  } catch {
    return false;
  }
  return stepExtraRule(target);
}

/**
 * 切到某一步并校验这一步自己的字段规则（必填等）。
 * 保留原行为：它带"切页"，只适合全量校验时逐个切过去校验的场景；
 * 「下一步」请用不切页的 validateStep。
 */
async function validateStepForm(target: number): Promise<boolean> {
  await gotoStep(target);
  return validateStep(target);
}

/**
 * 提交前的全量校验。返回 0 表示四步全部通过；否则返回**第一个出问题的步骤号**，
 * 调用方据此把界面停在那一页（这就是「校验失败自动跳回出问题的那一步」）。
 *
 * 校验内容 = 每一步各自的 wd-form 规则 + stepExtraRule 里的补充规则
 * （第 1 步两次密码一致、第 2 步必须是完整 QQ 邮箱且 6 位验证码）。
 * 顺序固定 1 → 2 → 3 → 4，保证提示给用户的是最靠前的问题，不会来回跳。
 */
async function validateAllSteps(): Promise<number> {
  for (const target of [1, 2, 3, 4]) {
    // 四步的 wd-form 是互斥渲染的，不切过去拿不到实例，所以这里必须 gotoStep；
    // 校验失败即停在出错的那一步（return，不再往后切）
    await gotoStep(target);
    if (!(await validateStep(target))) {
      return target;
    }
  }
  return 0;
}

/**
 * 「下一步」：**先校验当前这一步，不通过就留在本步**。
 *
 * 与上一版"自由翻页"的差异（用户明确要求改回每步校验）：
 * - 校验的就是**用户此刻看到的那一步**，所以用不切页的 validateStep(current)，
 *   绝不允许"先跳页再报错"——那会让用户以为刚填的内容被丢了；
 * - 失败时 wd-form（error-type="toast"）自己会弹出字段错误，本函数只负责拦住不放行；
 * - 失败必须 return，不能改 step，用户原地改完再点一次「下一步」即可。
 *
 * 「上一步」prevStep() 仍然完全不校验（往回走不拦），提交时 handleRegister()
 * 再全量校验一次兜底。第 2 步离开时补全 QQ 邮箱的逻辑保留。
 */
async function nextStep() {
  if (step.value >= 4) {
    return;
  }
  const current = step.value;
  // 校验失败：留在原步（step 一个字节都不动）
  if (!(await validateStep(current))) {
    return;
  }
  step.value = current + 1;
  // 在第 2 步离开时顺手把 QQ 号补全成完整邮箱（stepExtraRule 已回写过一次，
  // 这里保留原有的兜底动作：补全不了就不动），这样回到前面的步骤或直接提交时看到的都是完整邮箱。
  if (step.value === 3) {
    const email = normalizeQqEmail(model.email);
    if (email) {
      model.email = email;
    }
  }
}

function prevStep() {
  if (step.value > 1) {
    step.value -= 1;
    // 回到第 1 步时把真人验证 token 清掉，保持与上一版一致的行为：
    // 回到第一步等于这次验证作废，重新往后走时必须重新完成验证。
    if (step.value === 1) {
      model.turnstileToken = "";
    }
  }
}

/**
 * 选完点「确定」之后，主动把弹层再关一次（幂等兜底）。
 * 用的是 wd-picker 自己 defineExpose 出来的 close()，内部就是 popupShow = false，
 * 不碰选中值、不碰 columns 数据源，也不动 v-model；弹层本来就会自动关，
 * 这里只是保证「点了确定就一定会消失」，不依赖组件的过渡状态机。
 * 只调对应那一个 picker，不去碰另外两个没打开的实例。
 */
function closePickerAfterConfirm(which: "gender" | "province" | "school") {
  if (which === "gender") {
    genderPickerRef.value?.close();
    return;
  }
  if (which === "province") {
    provincePickerRef.value?.close();
    return;
  }
  schoolPickerRef.value?.close();
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
    // 发码成功即作废本次真人验证 token：第 4 步提交前必须重新完成验证（保持既有逻辑不变）
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

async function handleRegister() {
  // 提交这一关仍然严格：四步全量校验，任何一步不通过就停在那一页并提示。
  // （校验失败时用户已经站在出错的那一步，改完再点一次提交即可。）
  const failedStep = await validateAllSteps();
  if (failedStep !== 0) {
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
  <view class="page kean-auth">
    <!-- 背景装饰层：手写 SVG 几何校园场景（矢量 / 已柔化），纯装饰、不参与交互。
         它是 .auth-content 的兄弟节点，永远不是 wd-picker 弹层的祖先，
         所以这里的 filter: blur() 不会劫持任何 position:fixed 的定位。 -->
    <view class="auth-sky" />
    <!-- 返回按钮：走 utils/authNav.ts 的 goBack()，任何入口进来都出得去 -->
    <view class="auth-back" @click="onBack">
      <view class="auth-back__arrow" />
    </view>

    <view class="auth-content">
      <view class="auth-hero auth-hero--compact">
        <image class="auth-logo" src="/static/app-logo.jpg" mode="aspectFit" />
        <view class="auth-title">{{ i18n.brand }}</view>
        <view class="auth-subtitle">{{ i18n.headline }}</view>
      </view>

      <!-- ⚠️ 本页用 .auth-card--plain（外层框与内层板都不带 backdrop-filter）：卡片内部装着
           三个 wd-picker，而 backdrop-filter 会成为 position:fixed 后代的包含块，会把底部
           弹层与遮罩劫持到卡片坐标系里（遮罩点不到、弹层关不掉）。
           玻璃感改由「多层半透明白 + 上下亮暗边 + 内高光 + 冷调软阴影 + 2px 缝」模拟。 -->
      <view class="auth-card auth-card--plain">
        <view class="auth-card__inner">
          <view class="auth-card-title">{{ i18n.heroSub }}</view>
          <view class="auth-card-sub">{{ i18n.subTip }}</view>

          <!-- 步骤提示：这一版**只显示当前这一步**（不再把 4 步全部铺出来、没有圆点连线、
               也没有「已完成 / 未完成」状态）。结构 = 左侧一小段竖线 + 「当前步骤标题」+
               一句短语；右侧「2 / 4」是唯一一处极轻的进度暗示（数字与斜杠，全语言通用，
               不新增任何 i18n 文案）。文案与序号来自 stepItems，当前步直接复用既有的 step。 -->
          <view class="auth-step-head">
            <view class="auth-step-head__main">
              <view class="auth-step-head__title">
                <text class="auth-step-head__no">{{ currentStep.no }}</text>
                <text class="auth-step-head__dot">·</text>
                <text class="auth-step-head__label">{{ currentStep.title }}</text>
              </view>
              <view class="auth-step-head__fields">{{ currentStep.fields }}</view>
            </view>
            <text class="auth-step-head__meta">{{ step }} / {{ stepItems.length }}</text>
          </view>

          <!-- 第 1 步 · 账号 -->
          <wd-form v-if="step === 1" key="step-account" ref="step1Ref" :model="model" error-type="toast" custom-class="auth-form">
            <view class="auth-field auth-field--user">
              <wd-input
                v-model="model.username"
                prop="username"
                :no-border="true"
                clearable
                :placeholder="i18n.usernamePlaceholder"
                :rules="[{ required: true, message: i18n.ruleUsername }]"
              />
            </view>
            <view class="auth-field auth-field--lock">
              <wd-input
                v-model="model.password"
                prop="password"
                :no-border="true"
                show-password
                clearable
                :placeholder="i18n.passwordPlaceholder"
                :rules="[{ required: true, message: i18n.rulePassword }]"
              />
            </view>
            <view class="auth-field auth-field--lock">
              <wd-input
                v-model="model.confirmPassword"
                prop="confirmPassword"
                :no-border="true"
                show-password
                clearable
                :placeholder="i18n.confirmPlaceholder"
                :rules="[{ required: true, message: i18n.ruleConfirm }]"
              />
            </view>
          </wd-form>

          <!-- 第 2 步 · 邮箱验证：必须是完整 QQ 邮箱（如 12345678@qq.com） -->
          <wd-form v-else-if="step === 2" key="step-email" ref="step2Ref" :model="model" error-type="toast" custom-class="auth-form">
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
            <view class="auth-tip">{{ i18n.emailTip }}</view>
          </wd-form>

          <!-- 第 3 步 · 学生认证：省份 / 学校（均必填）+ 校区（选填手输） -->
          <wd-form v-else-if="step === 3" key="step-student" ref="step3Ref" :model="model" error-type="toast" custom-class="auth-form">
            <view class="auth-field auth-field--pick">
              <wd-picker
                ref="provincePickerRef"
                v-model="model.provinceId"
                prop="provinceId"
                :label="i18n.province"
                label-width="64px"
                :placeholder="i18n.provincePlaceholder"
                :columns="provinceColumns"
                :rules="[{ required: true, message: i18n.ruleProvince }]"
                @confirm="closePickerAfterConfirm('province')"
              />
            </view>
            <view class="auth-field auth-field--pick">
              <wd-picker
                ref="schoolPickerRef"
                v-model="model.schoolId"
                prop="schoolId"
                :label="i18n.school"
                label-width="64px"
                :disabled="!model.provinceId"
                :placeholder="i18n.schoolPlaceholder"
                :columns="schoolColumns"
                :rules="[{ required: true, message: i18n.ruleSchool }]"
                @confirm="closePickerAfterConfirm('school')"
              />
            </view>
            <view class="auth-field auth-field--campus">
              <wd-input
                v-model="model.campusText"
                :no-border="true"
                clearable
                :maxlength="50"
                :placeholder="i18n.campusPlaceholder"
              />
            </view>
          </wd-form>

          <!-- 第 4 步 · 完善资料：性别（必填下拉）+ 昵称（选填） -->
          <wd-form v-else key="step-profile" ref="step4Ref" :model="model" error-type="toast" custom-class="auth-form">
            <view class="auth-field auth-field--pick auth-field--gender">
              <wd-picker
                ref="genderPickerRef"
                v-model="model.gender"
                prop="gender"
                :label="i18n.gender"
                label-width="64px"
                :placeholder="i18n.genderPlaceholder"
                :columns="genderColumns"
                :rules="[{ required: true, message: i18n.ruleGender }]"
                @confirm="closePickerAfterConfirm('gender')"
              />
            </view>
            <view class="auth-field auth-field--nick">
              <wd-input
                v-model="model.nickname"
                :no-border="true"
                clearable
                :maxlength="32"
                :placeholder="i18n.nicknamePlaceholder"
              />
            </view>
            <view class="auth-tip">{{ i18n.nicknameTip }}</view>
          </wd-form>

          <!--
            真人验证：第 2 步发码与第 4 步提交共用**同一个控件实例**（不重复渲染 iframe）。
            这里用 v-show 而不是 v-if：切步骤时不卸载控件，不会重新发起 turnstile 渲染。
            发码成功后既有逻辑会 reset() 清空 token，所以第 4 步提交前必须重新完成验证。
          -->
          <view v-show="step >= 2">
            <TurnstileChallenge
              v-if="captcha.enabled && captcha.siteKey"
              ref="turnstileRef"
              box-id="kean-ts-register"
              :site-key="captcha.siteKey"
              v-model="model.turnstileToken"
            />
            <!-- 配置请求失败：单独一行，且绝不在失败时渲染验证控件（H5 与非 H5 都渲染这个普通 view） -->
            <view v-else-if="captcha.error" class="auth-hint">{{ i18n.captchaFailed }}</view>
            <!-- 只有后端明确 enabled:true、却没给 siteKey 才是"未配置"；enabled:false 时这里也不显示 -->
            <view v-else-if="captcha.loaded && captcha.enabled" class="auth-hint">{{ i18n.captchaMissing }}</view>
          </view>

          <!-- 第 4 步 · 隐私政策（未勾选不能提交） -->
          <view v-if="step === 4" class="auth-agree">
            <wd-checkbox v-model="agreed" shape="circle">
              <text class="auth-agree-text">{{ i18n.agreePrefix }}</text>
              <!-- 链接放在勾选框内，点它在跳转前先阻止冒泡，避免顺手把勾选状态也切了 -->
              <text class="auth-agree-link" @click.stop="goPrivacy">{{ i18n.privacy }}</text>
            </wd-checkbox>
          </view>
        </view>
      </view>

      <!-- 动作区：「上一步」+「下一步 / 提交注册」。每步点「下一步」都会校验当前这一步
           （nextStep 内部先 validateStep 再翻页，失败留在本步并提示）；「上一步」不校验；
           提交时 handleRegister 再用 validateAllSteps 全量校验一次兜底。 -->
      <view class="auth-actions" :class="{ 'auth-actions--row': step > 1 }">
        <view v-if="step > 1" class="auth-actions-prev">
          <wd-button size="large" block custom-class="auth-btn auth-btn--ghost" @click="prevStep">
            {{ i18n.prevStep }}
          </wd-button>
        </view>
        <view class="auth-actions-next">
          <wd-button
            v-if="step < 4"
            type="primary"
            size="large"
            block
            custom-class="auth-btn auth-btn--gradient"
            @click="nextStep()"
          >
            {{ i18n.nextStep }}
          </wd-button>
          <wd-button
            v-else
            type="primary"
            size="large"
            block
            custom-class="auth-btn auth-btn--gradient"
            :loading="loading"
            @click="handleRegister"
          >
            {{ i18n.submit }}
          </wd-button>
        </view>
      </view>

      <view class="auth-slogan">— {{ i18n.slogan }} —</view>
    </view>
    <wd-toast />
  </view>
</template>

<style scoped>
/* 视觉全部由 src/styles/auth-theme.css 统一维护（选择器收在 .kean-auth 之下），
   本页无需额外样式。 */
</style>
