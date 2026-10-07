<script setup lang="ts">
import { fetchMe } from "@/api/auth";
import { updatePrivacy } from "@/api/user";
import { useUserStore } from "@/store/user";
import { useToast } from "wot-design-uni";
import { computed, ref } from "vue";

const toast = useToast();
const userStore = useUserStore();
const saving = ref(false);
const on = computed(() => userStore.state.user?.privateAccount === 1);

async function toggle(value: boolean | { value?: boolean }) {
  const next = typeof value === "boolean" ? value : Boolean(value?.value);
  if (saving.value || next === on.value) {
    return;
  }
  saving.value = true;
  try {
    const user = await updatePrivacy(next ? 1 : 0);
    if (userStore.state.token) {
      const latest = await fetchMe();
      userStore.setLogin(userStore.state.token, { ...latest, privateAccount: user.privateAccount ?? (next ? 1 : 0) });
    }
    toast.success(next ? "已设为隐私账号" : "已取消隐私账号");
  } catch (error) {
    toast.error((error as Error).message || "设置失败");
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <view class="page">
    <wd-cell-group border>
      <wd-cell title="隐私账号" label="开启后，他人只能看到头像、昵称和学校；其他同学不能主动向你发起新的私信（你们已有的会话不受影响）">
        <wd-switch :model-value="on" :disabled="saving" @change="toggle" />
      </wd-cell>
    </wd-cell-group>
    <view class="tip">头像始终公开：任务列表、聊天列表、黑名单等处都会显示你的头像，开启隐私只隐藏统计信息（性别、校区、完成数、评分与评价）。打开后，其他同学不能主动向你发起新的私信，你们已有的会话不受影响。关闭后，其他同学可再次看到完整主页并主动向你发起新的私信。</view>
    <wd-toast />
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: var(--kean-bg);
  padding-top: 12px;
}
.tip {
  padding: 12px 16px;
  color: var(--kean-muted);
  font-size: 12px;
  line-height: 1.6;
}
</style>
