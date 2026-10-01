<script setup lang="ts">
import { deleteAccount, fetchMe } from "@/api/auth";
import { updateSingleDevice } from "@/api/user";
import { useUserStore } from "@/store/user";
import { t } from "@/utils/i18n";
import { refreshMessageBadge } from "@/utils/messageBadge";
import { loadDisplayPrefs } from "@/utils/prefs";
import { computed, reactive, ref } from "vue";
import { useToast } from "wot-design-uni";

const toast = useToast();
const userStore = useUserStore();
const saving = ref(false);
const singleDeviceOn = computed(() => userStore.state.user?.singleDevice === 1);

/** 注销账号：不可逆，两段确认（uni.showModal 警告 → 弹层输入当前密码） */
const deleteOpen = ref(false);
const deleting = ref(false);
const deleteForm = reactive({ password: "" });

function goPassword() {
  uni.navigateTo({ url: "/pages/mine/password" });
}

function goDevices() {
  uni.navigateTo({ url: "/pages/mine/devices" });
}

/**
 * 切换「仅允许一台设备在线」。
 * 写法与 pages/mine/privacy.vue 改隐私账号一致：调接口 → 拉一次 /api/auth/me →
 * 用接口返回值兜底覆盖本地用户信息，保证切页回来开关状态不会跳。
 */
async function toggleSingleDevice(value: boolean | { value?: boolean }) {
  const next = typeof value === "boolean" ? value : Boolean(value?.value);
  if (saving.value || next === singleDeviceOn.value) {
    return;
  }
  saving.value = true;
  try {
    const user = await updateSingleDevice(next ? 1 : 0);
    if (userStore.state.token) {
      const latest = await fetchMe();
      userStore.setLogin(userStore.state.token, {
        ...latest,
        singleDevice: user.singleDevice ?? (next ? 1 : 0)
      });
    }
    toast.success(next ? "已开启仅允许一台设备在线" : "已关闭仅允许一台设备在线");
  } catch (error) {
    toast.error((error as Error).message || "设置失败");
  } finally {
    saving.value = false;
  }
}

const copy = computed(() => {
  const lang = loadDisplayPrefs().lang;
  return {
    password: t("changePassword", lang),
    devices: t("loginDevices", lang)
  };
});

/**
 * 第一段：不可逆警告（uni.showModal，写法与 blacklist.vue 的确认弹窗一致）。
 * 只有用户点了「继续注销」才进入第二步输密码。
 */
function openDeleteAccount() {
  uni.showModal({
    title: "注销账号",
    content:
      "注销不可恢复：账号将被永久注销，无法找回；你所有设备上的登录都会立刻失效，邮箱、手机号、头像等个人信息会被清空，该账号也不能再登录。你发布/申请过的代课任务、聊天记录与信用评价将以去标识化方式保留。确定要继续吗？",
    confirmText: "继续注销",
    confirmColor: "#e34d59",
    cancelText: "取消",
    success: (res) => {
      if (!res.confirm) {
        return;
      }
      deleteForm.password = "";
      deleteOpen.value = true;
    }
  });
}

/** 第二段：输入当前密码确认后提交（表单校验风格照 password.vue：不通过只轻提示，不发请求） */
async function confirmDeleteAccount() {
  if (deleting.value) {
    return;
  }
  if (!deleteForm.password) {
    toast.error("请输入当前密码");
    return;
  }
  deleting.value = true;
  try {
    await deleteAccount({ password: deleteForm.password });
    deleteOpen.value = false;
    // 服务端已注销并拉黑全部设备：清掉本地登录态，回登录页
    userStore.logoutLocal();
    refreshMessageBadge();
    toast.success("账号已注销");
    setTimeout(() => {
      uni.reLaunch({ url: "/pages/auth/login" });
    }, 400);
  } catch (error) {
    toast.error((error as Error).message || "注销失败");
  } finally {
    deleting.value = false;
  }
}
</script>

<template>
  <view class="page">
    <wd-cell-group border>
      <wd-cell :title="copy.password" is-link @click="goPassword" />
      <wd-cell :title="copy.devices" is-link @click="goDevices" />
      <wd-cell
        title="仅允许一台设备在线"
        label="关闭时：多端可同时在线，新设备登录只会提醒你。打开后：在新设备登录会把其他设备退出。"
      >
        <wd-switch :model-value="singleDeviceOn" :disabled="saving" @change="toggleSingleDevice" />
      </wd-cell>
    </wd-cell-group>
    <view class="tip">打开后立即生效：其他设备上的登录会马上失效，那些设备会提示「你的账号已在其他设备登录」并回到登录页，需要重新登录才能使用；之后你每次在新设备登录，也会把其他设备退出。当前正在使用的这一台不受影响。关闭后不会再顶掉任何设备，只会照常发送新设备登录提醒。</view>

    <view class="danger-group">
      <wd-cell
        title="注销账号"
        is-link
        custom-class="danger-cell"
        @click="openDeleteAccount"
      />
    </view>
    <view class="danger-tip">注销不可恢复。注销后所有设备会立刻退出登录，个人信息被清空，账号无法找回；你发布/申请过的代课任务、聊天记录与信用评价会以去标识化方式保留。</view>

    <!-- 第二步：输入当前密码确认。用底部弹层承载表单，交互与 pages/message/index.vue 的通知详情一致 -->
    <wd-popup
      v-model="deleteOpen"
      position="bottom"
      safe-area-inset-bottom
      :z-index="999"
      custom-style="border-radius:16px 16px 0 0"
    >
      <view class="del-sheet">
        <view class="del-title">确认注销账号</view>
        <view class="del-warn">
          此操作不可恢复：账号无法找回，所有设备会立刻退出登录，该账号也不能再登录。
          请输入当前密码以确认是你本人操作。
        </view>
        <wd-input
          v-model="deleteForm.password"
          label="当前密码"
          label-width="90px"
          show-password
          clearable
          placeholder="请输入当前密码"
        />
        <view class="del-foot">
          <view class="del-btn">
            <wd-button size="large" plain block :disabled="deleting" @click="deleteOpen = false">取消</wd-button>
          </view>
          <view class="del-btn">
            <wd-button type="error" size="large" block :loading="deleting" @click="confirmDeleteAccount">
              确认注销
            </wd-button>
          </view>
        </view>
      </view>
    </wd-popup>
    <wd-toast />
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: var(--kean-bg);
  padding-top: 12px;
  padding-bottom: 32px;
}
.tip {
  padding: 12px 16px;
  color: var(--kean-muted);
  font-size: 12px;
  line-height: 1.6;
}
.danger-group {
  margin-top: 12px;
}
/* wd-cell 内部元素在组件作用域里，必须用 :deep 才能把标题染成危险色 */
:deep(.danger-cell .wd-cell__title) {
  color: #e34d59;
}
.danger-tip {
  padding: 12px 16px 0;
  color: var(--kean-muted);
  font-size: 12px;
  line-height: 1.6;
}
.del-sheet {
  padding: 20px 16px 24px;
}
.del-title {
  color: var(--kean-text);
  font-size: 16px;
  font-weight: 600;
}
.del-warn {
  margin: 10px 0 14px;
  color: #e34d59;
  font-size: 12px;
  line-height: 1.6;
}
.del-foot {
  display: flex;
  gap: 12px;
  margin-top: 20px;
}
.del-btn {
  flex: 1;
}
</style>
