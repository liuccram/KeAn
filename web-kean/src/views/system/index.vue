<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import {
  createAdmin,
  listAdmins,
  listConfig,
  listLogs,
  updateAdminStatus,
  updateConfig,
  type AdminAccount,
  type ConfigItem,
  type OperationLogItem
} from "@/api/system";
import StatusTag from "@/components/StatusTag.vue";
import { formatTime, USER_STATUS } from "@/utils/dicts";
import { useUserStore } from "@/stores/user";

const userStore = useUserStore();
const tab = ref("admins");
const admins = ref<AdminAccount[]>([]);
const logs = ref<OperationLogItem[]>([]);
const logTotal = ref(0);
const logPage = ref(1);
const configs = ref<ConfigItem[]>([]);
const registerEnabled = ref(true);
const imageUploadEnabled = ref(true);
const siteName = ref("课安");
const createForm = reactive({ visible: false, username: "", password: "", nickname: "" });

async function loadAdmins() {
  admins.value = await listAdmins();
}

async function loadLogs() {
  const data = await listLogs({ page: logPage.value, size: 20 });
  logs.value = data.list || [];
  logTotal.value = data.total || 0;
}

async function loadConfig() {
  configs.value = await listConfig();
  siteName.value = configs.value.find((item) => item.key === "site.name")?.value || "课安";
  registerEnabled.value = (configs.value.find((item) => item.key === "register.enabled")?.value || "1") !== "0";
  imageUploadEnabled.value = (configs.value.find((item) => item.key === "upload.image.enabled")?.value || "1") !== "0";
}

async function submitAdmin() {
  await createAdmin({ ...createForm });
  ElMessage.success("已创建管理员");
  createForm.visible = false;
  createForm.username = "";
  createForm.password = "";
  createForm.nickname = "";
  await loadAdmins();
}

async function toggleAdmin(row: AdminAccount) {
  const next = row.status === "BANNED" ? "NORMAL" : "BANNED";
  await ElMessageBox.confirm(next === "BANNED" ? "确认停用该管理员？" : "确认启用？", "提示");
  await updateAdminStatus(row.id, next);
  ElMessage.success("已更新");
  await loadAdmins();
}

async function saveConfig() {
  await updateConfig([
    { key: "site.name", value: siteName.value },
    { key: "register.enabled", value: registerEnabled.value ? "1" : "0" },
    { key: "upload.image.enabled", value: imageUploadEnabled.value ? "1" : "0" }
  ]);
  ElMessage.success("配置已保存");
  await loadConfig();
}

onMounted(async () => {
  await loadAdmins();
  await loadLogs();
  await loadConfig();
});
</script>

<template>
  <div class="ka-page">
    <section class="ka-card">
      <el-tabs v-model="tab">
        <el-tab-pane label="账号管理" name="admins">
          <div class="ka-toolbar">
            <el-button type="primary" @click="createForm.visible = true">新建管理员</el-button>
          </div>
          <el-table :data="admins" class="mt">
            <el-table-column prop="username" label="用户名" />
            <el-table-column prop="nickname" label="昵称" />
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <StatusTag :text="USER_STATUS[row.status] || row.status" :tone="row.status === 'BANNED' ? 'danger' : 'success'" />
              </template>
            </el-table-column>
            <el-table-column label="最近登录" width="170">
              <template #default="{ row }">{{ formatTime(row.lastLoginAt) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="100">
              <template #default="{ row }">
                <button v-if="row.id !== userStore.user?.id" class="link-btn" @click="toggleAdmin(row)">
                  {{ row.status === "BANNED" ? "启用" : "停用" }}
                </button>
                <span v-else>—</span>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
        <el-tab-pane label="操作日志" name="logs">
          <el-table :data="logs">
            <el-table-column prop="adminName" label="操作人" width="120" />
            <el-table-column prop="operationType" label="类型" width="180" />
            <el-table-column label="对象" width="160">
              <template #default="{ row }">{{ row.targetType }} #{{ row.targetId }}</template>
            </el-table-column>
            <el-table-column prop="description" label="说明" />
            <el-table-column label="时间" width="170">
              <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
            </el-table-column>
          </el-table>
          <div class="ka-pager">
            <el-pagination v-model:current-page="logPage" layout="prev, pager, next, total" :total="logTotal" :page-size="20" @current-change="loadLogs" />
          </div>
        </el-tab-pane>
        <el-tab-pane label="站点配置" name="config">
          <el-form label-width="120px" style="max-width: 480px">
            <el-form-item label="站点名称">
              <el-input v-model="siteName" />
            </el-form-item>
            <el-form-item label="开放注册">
              <el-switch v-model="registerEnabled" />
            </el-form-item>
            <el-form-item label="开放上传图片">
              <el-switch v-model="imageUploadEnabled" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="saveConfig">保存</el-button>
            </el-form-item>
          </el-form>
        </el-tab-pane>
      </el-tabs>
    </section>
  </div>

  <el-dialog v-model="createForm.visible" title="新建管理员" width="420px">
    <el-form label-width="80px">
      <el-form-item label="用户名"><el-input v-model="createForm.username" /></el-form-item>
      <el-form-item label="昵称"><el-input v-model="createForm.nickname" /></el-form-item>
      <el-form-item label="密码"><el-input v-model="createForm.password" type="password" show-password /></el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="createForm.visible = false">取消</el-button>
      <el-button type="primary" :disabled="createForm.username.length < 4 || createForm.password.length < 8" @click="submitAdmin">确定</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.mt {
  margin-top: 12px;
}
</style>
