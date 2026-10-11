<script setup lang="ts">
import { fetchMe } from "@/api/auth";
import { updatePrivacy } from "@/api/user";
import PageIntro from "@/components/PageIntro.vue";
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
    <PageIntro eyebrow="PRIVACY CONTROL" title="隐私与可见范围" description="由你决定个人主页展示哪些信息" marker="隐私" />
    <view class="privacy-card">
      <view class="privacy-head">
        <view class="privacy-copy"><text class="privacy-title">隐私账号</text><text class="privacy-state">{{ on ? "已开启 · 主页信息受限" : "已关闭 · 主页完整展示" }}</text></view>
        <wd-switch :model-value="on" :disabled="saving" @change="toggle" />
      </view>
      <view class="divider" />
      <text class="section-label">开启后会发生什么</text>
      <view class="rule"><text class="rule-mark">01</text><text>头像、昵称和学校仍会出现在任务与聊天等场景。</text></view>
      <view class="rule"><text class="rule-mark">02</text><text>性别、校区、完成次数、评分和评价不会展示在公开主页。</text></view>
      <view class="rule"><text class="rule-mark">03</text><text>其他同学无法主动发起新私信；已有会话不受影响。</text></view>
      <view class="privacy-foot">关闭后，完整主页信息和新私信入口会恢复对其他同学可见。</view>
    </view>
    <wd-toast />
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: var(--kean-bg);
}
.privacy-card { margin: 0 16px 24px; padding: 18px; border: 1px solid rgba(224,232,243,.9); border-radius: 18px; background: var(--kean-card); box-shadow: 0 8px 24px rgba(34,59,97,.045); }
.privacy-head { display:flex; align-items:center; justify-content:space-between; gap:12px; }
.privacy-copy { display:flex; flex-direction:column; gap:5px; }
.privacy-title { color:var(--kean-text); font-size:16px; font-weight:700; }
.privacy-state { color:var(--kean-muted); font-size:11px; }
.divider { height:1px; margin:16px 0; background:var(--kean-line); }
.section-label { display:block; margin-bottom:10px; color:var(--kean-sub); font-size:12px; font-weight:700; }
.rule { display:flex; align-items:flex-start; gap:10px; margin:10px 0; color:var(--kean-sub); font-size:12px; line-height:1.65; }
.rule-mark { flex-shrink:0; color:#88a2c4; font-size:10px; font-weight:700; }
.privacy-foot { margin-top:15px; padding:11px 12px; border-radius:11px; background:#f3f7fc; color:var(--kean-muted); font-size:11px; line-height:1.6; }
</style>
