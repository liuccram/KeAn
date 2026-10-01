<script setup lang="ts">
import { changeEmail, updateAvatar, updateProfile } from "@/api/auth";
import { listCampuses, listProvinces, listSchools } from "@/api/catalog";
import { sendSms } from "@/api/sms";
import CodeBoxes from "@/components/CodeBoxes.vue";
import PersonAvatar from "@/components/PersonAvatar.vue";
import { useUserStore } from "@/store/user";
import { normalizeQqEmail } from "@/utils/qqEmail";
import { chooseAndCrop } from "@/utils/imageCrop";
import { resolveMediaUrl, uploadFile } from "@/utils/request";
import { useUploadProgress } from "@/composables/useUploadProgress";
import { useToast } from "wot-design-uni";
import { computed, onMounted, onUnmounted, reactive, ref, watch } from "vue";

const toast = useToast();
const userStore = useUserStore();
const formRef = ref();
const loading = ref(false);
const sendingEmail = ref(false);
const emailCountdown = ref(0);
let emailTimer: ReturnType<typeof setInterval> | null = null;
const user = computed(() => userStore.state.user);
const bound = computed(() => Boolean(user.value?.email));

const model = reactive({
  nickname: "",
  gender: "" as string,
  provinceId: "" as number | string,
  schoolId: "" as number | string,
  campusId: "" as number | string,
  email: "",
  smsCode: ""
});

const provinceColumns = ref<{ label: string; value: number }[]>([]);
const schoolColumns = ref<{ label: string; value: number }[]>([]);
const campusColumns = ref<{ label: string; value: number }[]>([]);
const genderColumns = [
  { label: "男", value: "MALE" },
  { label: "女", value: "FEMALE" }
];

const usedChanges = computed(() => user.value?.schoolChangeCount || 0);
const remainChanges = computed(() => Math.max(0, 3 - usedChanges.value));
const schoolLocked = computed(() => remainChanges.value <= 0);
const avatarSrc = computed(() => resolveMediaUrl(user.value?.avatarUrl));
// 解构到顶层，模板才会自动解包 ref
const { active: uploading, label: uploadLabel, onProgress, reset: resetUpload } = useUploadProgress();
const emailChanged = computed(() => {
  const next = normalizeQqEmail(model.email) || model.email.trim().toLowerCase();
  const current = (user.value?.email || "").trim().toLowerCase();
  return next !== current;
});
const needEmailCode = computed(() => !bound.value || emailChanged.value);

function fillModel() {
  const current = user.value;
  if (!current) {
    return;
  }
  model.nickname = current.nickname || "";
  model.gender = current.gender || "";
  model.schoolId = current.schoolId || "";
  model.campusId = current.campusId || "";
  model.email = current.email || "";
  model.smsCode = "";
}

async function loadProvinces() {
  const provinces = await listProvinces();
  provinceColumns.value = provinces.map((item) => ({ label: item.name, value: item.id }));
}

async function loadSchools() {
  const provinceId = Number(model.provinceId);
  if (!provinceId) {
    schoolColumns.value = [];
    return;
  }
  const schools = await listSchools(provinceId);
  schoolColumns.value = schools.map((item) => ({ label: item.name, value: item.id }));
  if (!schools.some((item) => item.id === Number(model.schoolId)) && schools.length) {
    model.schoolId = schools[0].id;
  }
}

async function loadCampuses() {
  const schoolId = Number(model.schoolId);
  if (!schoolId) {
    campusColumns.value = [];
    return;
  }
  const campuses = await listCampuses(schoolId);
  campusColumns.value = campuses.map((item) => ({ label: item.name, value: item.id }));
  if (!campuses.some((item) => item.id === Number(model.campusId)) && campuses.length) {
    model.campusId = campuses[0].id;
  }
}

watch(
  () => model.provinceId,
  (value, previous) => {
    if (previous !== undefined && value !== previous) {
      loadSchools().catch(() => undefined);
    }
  }
);

watch(
  () => model.schoolId,
  () => {
    loadCampuses().catch(() => undefined);
  }
);

onMounted(async () => {
  if (!userStore.isLoggedIn.value) {
    uni.redirectTo({ url: "/pages/auth/login" });
    return;
  }
  fillModel();
  try {
    await loadProvinces();
    const all = await listSchools();
    const mine = all.find((item) => item.id === Number(model.schoolId));
    if (mine?.provinceId) {
      model.provinceId = mine.provinceId;
    }
    await loadSchools();
    await loadCampuses();
  } catch (error) {
    toast.error((error as Error).message || "加载失败");
  }
});

onUnmounted(() => {
  if (emailTimer) {
    clearInterval(emailTimer);
  }
});

function chooseAvatar() {
  if (uploading.value) {
    return;
  }
  chooseAndCrop("avatar").then(async (filePath) => {
    if (!filePath) {
      return;
    }
    try {
      const uploaded = await uploadFile(filePath, "AVATAR", { onProgress });
      const latest = await updateAvatar(uploaded.objectKey);
      if (userStore.state.token) {
        userStore.setLogin(userStore.state.token, latest);
      }
      toast.success("头像已更新");
    } catch (error) {
      toast.error((error as Error).message || "头像上传失败");
    } finally {
      resetUpload();
    }
  });
}

function startEmailCountdown() {
  emailCountdown.value = 60;
  if (emailTimer) {
    clearInterval(emailTimer);
  }
  emailTimer = setInterval(() => {
    emailCountdown.value -= 1;
    if (emailCountdown.value <= 0 && emailTimer) {
      clearInterval(emailTimer);
      emailTimer = null;
    }
  }, 1000);
}

async function handleSendEmailCode() {
  const email = normalizeQqEmail(model.email);
  if (!email) {
    toast.error("请填写正确的QQ邮箱");
    return;
  }
  if (!needEmailCode.value) {
    toast.info("QQ邮箱未改动，无需验证");
    return;
  }
  if (sendingEmail.value || emailCountdown.value > 0) {
    return;
  }
  sendingEmail.value = true;
  try {
    await sendSms({ email, scene: "CHANGE_EMAIL" });
    startEmailCountdown();
    toast.success(bound.value ? "验证码已发送到原邮箱，请查收" : `验证码已发送到 ${email}`);
  } catch (error) {
    toast.error((error as Error).message || "发送失败");
  } finally {
    sendingEmail.value = false;
  }
}

function handleSave() {
  formRef.value
    .validate()
    .then(async ({ valid }: { valid: boolean }) => {
      if (!valid) {
        return;
      }
      if (schoolLocked.value && Number(model.schoolId) !== Number(user.value?.schoolId)) {
        toast.error("学校最多只能修改 3 次");
        model.schoolId = user.value?.schoolId || model.schoolId;
        return;
      }
      if (!bound.value && !normalizeQqEmail(model.email)) {
        toast.error("请先绑定QQ邮箱");
        return;
      }
      if (needEmailCode.value) {
        const email = normalizeQqEmail(model.email);
        if (!email) {
          toast.error("请填写正确的QQ邮箱");
          return;
        }
        if (!/^\d{6}$/.test(model.smsCode)) {
          toast.error(bound.value ? "换绑请填写 6 位验证码" : "请填写 6 位验证码");
          return;
        }
      }
      loading.value = true;
      try {
        let latest = user.value;
        if (needEmailCode.value) {
          latest = await changeEmail({
            email: normalizeQqEmail(model.email) || model.email.trim(),
            smsCode: model.smsCode.trim()
          });
        }
        latest = await updateProfile({
          nickname: model.nickname.trim(),
          gender: String(model.gender),
          schoolId: Number(model.schoolId),
          campusId: Number(model.campusId)
        });
        if (userStore.state.token && latest) {
          userStore.setLogin(userStore.state.token, latest);
        }
        fillModel();
        toast.success("资料已保存");
      } catch (error) {
        toast.error((error as Error).message || "保存失败");
      } finally {
        loading.value = false;
      }
    })
    .catch(() => undefined);
}
</script>

<template>
  <view class="page">
    <view class="head">
      <image v-if="avatarSrc" class="avatar" :src="avatarSrc" mode="aspectFill" @click="chooseAvatar" />
      <view v-else class="avatar avatar-fallback" @click="chooseAvatar">
        <PersonAvatar :size="72" />
      </view>
      <view class="name">{{ user?.nickname || user?.username || "未登录" }}</view>
      <view class="hint-avatar">{{ uploading ? uploadLabel || "上传中..." : "点击头像更换，可框选出展示区域" }}</view>
    </view>
    <wd-form ref="formRef" :model="model" error-type="toast">
      <wd-cell-group border>
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
        <wd-picker
          v-model="model.provinceId"
          label="省份"
          label-width="80px"
          prop="provinceId"
          :disabled="schoolLocked"
          :columns="provinceColumns"
          :rules="[{ required: true, message: '请先选择省份' }]"
        />
        <wd-picker
          v-model="model.schoolId"
          label="学校"
          label-width="80px"
          prop="schoolId"
          :disabled="schoolLocked || !model.provinceId"
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
        <wd-input
          v-model="model.email"
          label="QQ邮箱"
          label-width="80px"
          prop="email"
          clearable
          :placeholder="bound ? '换绑请填写新QQ邮箱' : '请先绑定QQ邮箱'"
          :rules="[{ required: true, message: bound ? '请填写QQ邮箱' : '请先绑定QQ邮箱' }]"
        />
      </wd-cell-group>
      <view v-if="needEmailCode" class="code-block">
        <view class="code-head">
          <text>邮箱验证码</text>
          <wd-button size="small" :loading="sendingEmail" :disabled="emailCountdown > 0" @click="handleSendEmailCode">
            {{ emailCountdown > 0 ? `${emailCountdown}s` : "获取验证码" }}
          </wd-button>
        </view>
        <CodeBoxes v-model="model.smsCode" />
      </view>
    </wd-form>
    <view class="hint">
      {{ bound ? "更换QQ邮箱需向原邮箱发送验证码。" : "请先绑定QQ邮箱后再保存。" }}学校还可修改 {{ remainChanges }} 次，校区可随时修改。
    </view>
    <view class="footer">
      <wd-button type="primary" size="large" block :loading="loading" @click="handleSave">保存</wd-button>
    </view>
    <wd-toast />
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: #f5f6f8;
}
.head {
  background: #fff;
  padding: 28px 16px 20px;
  display: flex;
  flex-direction: column;
  align-items: center;
  margin-bottom: 12px;
}
.avatar {
  width: 72px;
  height: 72px;
  border-radius: 50%;
  background: #eef3ff;
  overflow: hidden;
}
.avatar-fallback {
  display: flex;
  align-items: center;
  justify-content: center;
}
.name {
  margin-top: 12px;
  font-size: 18px;
  font-weight: 600;
}
.hint-avatar {
  margin-top: 8px;
  color: #86909c;
  font-size: 12px;
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
