<script setup lang="ts">
import { ref, watch } from "vue";
import { useRouter } from "vue-router";
import { listOnlineUsers, type OnlineUser } from "@/api/dashboard";
import { formatTime } from "@/utils/dicts";

const visible = defineModel<boolean>({ default: false });
const router = useRouter();
const loading = ref(false);
const list = ref<OnlineUser[]>([]);

async function load() {
  loading.value = true;
  try {
    list.value = (await listOnlineUsers()) || [];
  } catch {
    list.value = [];
  } finally {
    loading.value = false;
  }
}

watch(visible, (open) => {
  if (open) {
    load();
  }
});

async function openUser(row: OnlineUser) {
  visible.value = false;
  await router.push({ path: "/users", query: { id: String(row.id) } });
}
</script>

<template>
  <el-dialog v-model="visible" title="当前在线用户" width="640px">
    <el-table v-loading="loading" :data="list" max-height="420" @row-click="openUser">
      <el-table-column label="用户" min-width="160">
        <template #default="{ row }">
          <div class="user">
            <el-avatar :size="28" :src="row.avatarUrl || undefined">{{ (row.nickname || "用").slice(0, 1) }}</el-avatar>
            <span>{{ row.nickname || row.username }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="schoolName" label="学校" min-width="140" />
      <el-table-column prop="campusName" label="校区" width="110" />
      <el-table-column label="最近登录" width="160">
        <template #default="{ row }">{{ formatTime(row.lastLoginAt) }}</template>
      </el-table-column>
    </el-table>
    <p v-if="!loading && !list.length" class="empty">当前没有在线用户</p>
  </el-dialog>
</template>

<style scoped>
.user {
  display: flex;
  align-items: center;
  gap: 8px;
}
.empty {
  margin: 16px 0 0;
  color: var(--ka-muted);
  font-size: 13px;
  text-align: center;
}
</style>
