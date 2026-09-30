<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  Bell,
  Clock,
  DataAnalysis,
  Document,
  Odometer,
  School,
  Setting,
  User,
  Warning
} from "@element-plus/icons-vue";
import { ElMessage, ElNotification } from "element-plus";
import { fetchMe, logout } from "@/api/auth";
import { changeOwnPassword } from "@/api/system";
import { getOnlineCount } from "@/api/dashboard";
import { listNotifications, markNotificationRead, unreadNotificationCount, type NotificationItem } from "@/api/notification";
import { pendingAppealCount } from "@/api/report";
import { useUserStore } from "@/stores/user";
import { formatTime } from "@/utils/dicts";
import OnlineUsersDialog from "@/components/OnlineUsersDialog.vue";

const router = useRouter();
const route = useRoute();
const userStore = useUserStore();

const menus = [
  { path: "/dashboard", title: "首页概览", icon: Odometer },
  { path: "/users", title: "用户管理", icon: User },
  { path: "/tasks", title: "代课管理", icon: Document },
  { path: "/reports", title: "举报审核及反馈", icon: Warning },
  { path: "/catalog", title: "学校与校区", icon: School },
  { path: "/announcements", title: "公告与消息", icon: Bell },
  { path: "/analytics", title: "数据统计", icon: DataAnalysis },
  { path: "/system", title: "系统管理", icon: Setting }
];

const title = computed(() => (route.meta.title as string) || "课安");
const en = computed(() => (route.meta.en as string) || "");
const displayName = computed(() => userStore.user?.nickname || userStore.user?.username || "管理员");
const pwdForced = computed(() => Boolean(userStore.user?.mustChangePassword));
const onlineCount = ref(0);
const unread = ref(0);
const appealPending = ref(0);
const notices = ref<NotificationItem[]>([]);
const onlineVisible = ref(false);
const pwdVisible = ref(false);
const pwdLoading = ref(false);
const pwdForm = reactive({ oldPassword: "", newPassword: "", confirm: "" });
let lastAppealPending = -1;
let lastUnread = -1;
let onlineTimer: number | undefined;

async function refreshOnline() {
  try {
    onlineCount.value = (await getOnlineCount()) || 0;
  } catch {
    // ignore
  }
}

async function refreshInbox() {
  try {
    const [count, appeals, page] = await Promise.all([
      unreadNotificationCount(),
      pendingAppealCount(),
      listNotifications(1, 8)
    ]);
    unread.value = Number(count || 0);
    appealPending.value = Number(appeals || 0);
    notices.value = page.list || [];
    if (lastAppealPending >= 0 && appealPending.value > lastAppealPending) {
      ElNotification({
        title: "收到用户申诉",
        message: `有 ${appealPending.value} 条申诉待复核`,
        type: "warning",
        duration: 6000,
        onClick: () => router.push({ path: "/reports", query: { appeal: "1" } })
      });
    }
    lastAppealPending = appealPending.value;
    if (lastUnread >= 0 && unread.value > lastUnread) {
      const newest = notices.value[0];
      if (newest?.title?.includes("举报") || newest?.title?.includes("反馈")) {
        ElNotification({
          title: newest.title,
          message: newest.content,
          type: "warning",
          duration: 6000,
          onClick: () => {
            if (newest.bizId) {
              router.push({ path: "/reports", query: { id: String(newest.bizId) } });
            } else {
              router.push("/reports");
            }
          }
        });
      }
    }
    lastUnread = unread.value;
  } catch {
    // ignore
  }
}

async function openNotice(item: NotificationItem) {
  try {
    if (item.readFlag !== 1) {
      await markNotificationRead(item.id);
      item.readFlag = 1;
      unread.value = Math.max(0, unread.value - 1);
    }
  } catch {
    // ignore
  }
  if (item.bizType === "REPORT" && item.bizId) {
    await router.push({ path: "/reports", query: { id: String(item.bizId) } });
  }
}

onMounted(() => {
  if (pwdForced.value) {
    openPassword();
  }
  refreshOnline();
  refreshInbox();
  onlineTimer = window.setInterval(() => {
    refreshOnline();
    refreshInbox();
  }, 15000);
});
onUnmounted(() => {
  if (onlineTimer) {
    window.clearInterval(onlineTimer);
  }
});

async function handleLogout() {
  try {
    await logout();
  } catch {
    // ignore
  }
  userStore.logoutLocal();
  await router.push("/login");
}

function openPassword() {
  pwdForm.oldPassword = "";
  pwdForm.newPassword = "";
  pwdForm.confirm = "";
  pwdVisible.value = true;
}

function beforePwdClose(done: () => void) {
  if (pwdForced.value) {
    ElMessage.warning("请先修改初始密码");
    return;
  }
  done();
}

async function submitPassword() {
  if (pwdForm.newPassword.length < 8 || pwdForm.newPassword.length > 32) {
    ElMessage.warning("新密码长度为 8-32 位");
    return;
  }
  if (pwdForm.newPassword !== pwdForm.confirm) {
    ElMessage.warning("两次输入的新密码不一致");
    return;
  }
  pwdLoading.value = true;
  try {
    await changeOwnPassword(pwdForm.oldPassword, pwdForm.newPassword);
    const me = await fetchMe();
    if (userStore.token) {
      userStore.setLogin(userStore.token, {
        id: me.id,
        role: me.role,
        username: me.username,
        nickname: me.nickname,
        mustChangePassword: me.mustChangePassword
      });
    }
    ElMessage.success("密码已修改");
    pwdVisible.value = false;
  } catch (error) {
    ElMessage.error((error as Error).message || "修改失败");
  } finally {
    pwdLoading.value = false;
  }
}
</script>

<template>
  <div class="shell">
    <aside class="aside">
      <div class="brand">
        <span class="mark" aria-hidden="true">
          <svg viewBox="0 0 32 32" width="22" height="22">
            <path
              d="M16 3.5c4.2 2.4 8.2 2.7 11 2.7v10.2c0 6.1-3.9 10.3-11 13.1-7.1-2.8-11-7-11-13.1V6.2c2.8 0 6.8-.3 11-2.7z"
              fill="#3b82f6"
            />
            <path d="M11 16.2l3.1 3.1 6.9-7" fill="none" stroke="#fff" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
        </span>
        <span class="name">课安</span>
      </div>
      <nav class="nav">
        <router-link
          v-for="item in menus"
          :key="item.path"
          :to="item.path"
          class="nav-item"
          :class="{ active: route.path === item.path }"
        >
          <el-icon><component :is="item.icon" /></el-icon>
          <span>{{ item.title }}</span>
          <el-badge v-if="item.path === '/reports' && appealPending > 0" :value="appealPending" :max="99" class="nav-badge" />
        </router-link>
      </nav>
    </aside>
    <div class="body">
      <header class="top">
        <div class="crumb">
          <h1>{{ title }}</h1>
          <span v-if="en">/ {{ en }}</span>
        </div>
        <div class="right">
          <div class="chip online" role="button" @click="onlineVisible = true">
            <el-icon><Clock /></el-icon>
            在线 {{ onlineCount }} 人
          </div>
          <el-popover placement="bottom-end" :width="360" trigger="click">
            <template #reference>
              <el-badge :value="unread || undefined" :hidden="unread <= 0" :max="99">
                <el-icon class="bell"><Bell /></el-icon>
              </el-badge>
            </template>
            <div class="inbox">
              <div class="inbox-head">
                <strong>通知</strong>
                <el-button v-if="appealPending > 0" link type="warning" @click="router.push({ path: '/reports', query: { appeal: '1' } })">
                  {{ appealPending }} 条申诉待复核
                </el-button>
              </div>
              <div v-if="notices.length" class="inbox-list">
                <button v-for="item in notices" :key="item.id" class="inbox-item" :class="{ unread: item.readFlag !== 1 }" @click="openNotice(item)">
                  <strong>{{ item.title }}</strong>
                  <p>{{ item.content }}</p>
                  <small>{{ formatTime(item.createdAt) }}</small>
                </button>
              </div>
              <p v-else class="inbox-empty">暂无通知</p>
            </div>
          </el-popover>
          <el-dropdown trigger="click">
            <div class="who">
              <el-avatar :size="32" class="avatar">{{ displayName.slice(0, 1) }}</el-avatar>
              <div class="who-text">
                <strong>{{ displayName }}</strong>
                <small>超级管理员</small>
              </div>
            </div>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item @click="openPassword">修改密码</el-dropdown-item>
                <el-dropdown-item @click="handleLogout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>
      <main class="main">
        <router-view />
      </main>
    </div>
    <OnlineUsersDialog v-model="onlineVisible" />
    <el-dialog
      v-model="pwdVisible"
      :title="pwdForced ? '请先修改初始密码' : '修改密码'"
      width="400px"
      :close-on-click-modal="!pwdForced"
      :close-on-press-escape="!pwdForced"
      :show-close="!pwdForced"
      :before-close="beforePwdClose"
    >
      <el-form label-width="84px">
        <el-form-item label="原密码">
          <el-input v-model="pwdForm.oldPassword" type="password" show-password placeholder="请输入原密码" />
        </el-form-item>
        <el-form-item label="新密码">
          <el-input v-model="pwdForm.newPassword" type="password" show-password placeholder="8-32 位新密码" />
        </el-form-item>
        <el-form-item label="确认密码">
          <el-input v-model="pwdForm.confirm" type="password" show-password placeholder="再次输入新密码" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button v-if="!pwdForced" @click="pwdVisible = false">取消</el-button>
        <el-button type="primary" :loading="pwdLoading" :disabled="!pwdForm.oldPassword || !pwdForm.newPassword" @click="submitPassword">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.shell {
  display: flex;
  height: 100vh;
  overflow: hidden;
  background: var(--ka-bg);
}
.aside {
  width: 212px;
  flex-shrink: 0;
  height: 100%;
  overflow: auto;
  background: linear-gradient(180deg, #0d1b36 0%, #102144 100%);
  color: #fff;
  display: flex;
  flex-direction: column;
}
.brand {
  height: 64px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 20px;
  font-weight: 700;
  font-size: 18px;
  letter-spacing: 1px;
}
.mark {
  width: 32px;
  height: 32px;
  border-radius: 10px;
  background: rgba(59, 130, 246, 0.18);
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.nav {
  padding: 8px 12px 24px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.nav-item {
  height: 42px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 12px;
  color: var(--ka-sidebar-text);
  text-decoration: none;
  font-size: 14px;
}
.nav-item:hover {
  background: rgba(255, 255, 255, 0.06);
  color: #fff;
}
.nav-item.active {
  background: linear-gradient(90deg, #23407a, #1d4ed8);
  color: #fff;
  font-weight: 600;
}
.nav-badge {
  margin-left: auto;
}
.body {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.top {
  height: 64px;
  background: #fff;
  border-bottom: 1px solid var(--ka-border);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
}
.crumb {
  display: flex;
  align-items: baseline;
  gap: 8px;
}
.crumb h1 {
  margin: 0;
  font-size: 18px;
  font-weight: 650;
}
.crumb span {
  color: #94a3b8;
  font-size: 13px;
}
.right {
  display: flex;
  align-items: center;
  gap: 16px;
}
.chip {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--ka-muted);
  font-size: 13px;
  background: #f8fafc;
  border-radius: 999px;
  padding: 6px 10px;
}
.chip.online {
  color: #0f766e;
  background: #ccfbf1;
  font-weight: 600;
  cursor: pointer;
}
.bell {
  font-size: 18px;
  color: #64748b;
  cursor: pointer;
}
.inbox-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}
.inbox-list {
  max-height: 360px;
  overflow: auto;
}
.inbox-item {
  display: block;
  width: 100%;
  text-align: left;
  border: 0;
  background: transparent;
  padding: 8px 0;
  border-bottom: 1px solid var(--ka-border);
  cursor: pointer;
}
.inbox-item strong {
  font-size: 13px;
}
.inbox-item p {
  margin: 4px 0 0;
  font-size: 12px;
  color: #64748b;
  line-height: 1.5;
}
.inbox-item small {
  color: #94a3b8;
  font-size: 11px;
}
.inbox-item.unread strong {
  color: #1d4ed8;
}
.inbox-empty {
  margin: 12px 0 4px;
  color: #94a3b8;
  font-size: 13px;
}
.who {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
}
.avatar {
  background: #3b82f6;
  color: #fff;
}
.who-text {
  display: flex;
  flex-direction: column;
  line-height: 1.2;
}
.who-text strong {
  font-size: 13px;
}
.who-text small {
  color: #94a3b8;
  font-size: 11px;
}
.main {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 20px 24px 28px;
}
</style>
