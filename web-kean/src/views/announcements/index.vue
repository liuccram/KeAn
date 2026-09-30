<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { ElMessage } from "element-plus";
import {
  createAnnouncement,
  listAnnouncements,
  offlineAnnouncement,
  publishAnnouncement,
  type AnnouncementItem
} from "@/api/announcement";
import { listSchools, type SchoolItem } from "@/api/catalog";
import StatusTag from "@/components/StatusTag.vue";
import { ANNOUNCEMENT_STATUS, ANNOUNCEMENT_STATUS_TONE, formatTime } from "@/utils/dicts";

const loading = ref(false);
const list = ref<AnnouncementItem[]>([]);
const total = ref(0);
const page = ref(1);
const status = ref("");
const schools = ref<SchoolItem[]>([]);
const dialog = ref(false);
const form = reactive({
  title: "",
  content: "",
  scope: "ALL",
  schoolId: undefined as number | undefined,
  publish: false
});

async function load() {
  loading.value = true;
  try {
    const data = await listAnnouncements({ status: status.value || undefined, page: page.value, size: 20 });
    list.value = data.list || [];
    total.value = data.total || 0;
  } finally {
    loading.value = false;
  }
}

function openCreate() {
  form.title = "";
  form.content = "";
  form.scope = "ALL";
  form.schoolId = undefined;
  form.publish = false;
  dialog.value = true;
}

async function submit() {
  await createAnnouncement({
    title: form.title.trim(),
    content: form.content.trim(),
    scope: form.scope,
    schoolId: form.scope === "SCHOOL" ? form.schoolId : null,
    publish: form.publish
  });
  ElMessage.success(form.publish ? "已发布" : "已保存草稿");
  dialog.value = false;
  await load();
}

async function publish(row: AnnouncementItem) {
  await publishAnnouncement(row.id);
  ElMessage.success("已发布，目标用户将收到站内信");
  await load();
}

async function offline(row: AnnouncementItem) {
  await offlineAnnouncement(row.id);
  ElMessage.success("已下线");
  await load();
}

onMounted(async () => {
  schools.value = (await listSchools()).list || [];
  await load();
});
</script>

<template>
  <div class="ka-page">
    <section class="ka-card">
      <div class="ka-toolbar">
        <el-select v-model="status" clearable placeholder="状态" style="width: 140px" @change="page = 1; load()">
          <el-option label="草稿" value="DRAFT" />
          <el-option label="已发布" value="PUBLISHED" />
          <el-option label="已下线" value="OFFLINE" />
        </el-select>
        <el-button type="primary" @click="openCreate">新建公告</el-button>
      </div>
      <el-table :data="list" v-loading="loading">
        <el-table-column prop="title" label="标题" min-width="200" />
        <el-table-column label="范围" width="160">
          <template #default="{ row }">{{ row.scope === "SCHOOL" ? row.schoolName : row.scope === "USER" ? "指定用户" : "全站" }}</template>
        </el-table-column>
        <el-table-column prop="publisherName" label="发布人" width="110" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <StatusTag :text="ANNOUNCEMENT_STATUS[row.status] || row.status" :tone="ANNOUNCEMENT_STATUS_TONE[row.status]" />
          </template>
        </el-table-column>
        <el-table-column label="时间" width="160">
          <template #default="{ row }">{{ formatTime(row.publishedAt || row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="140">
          <template #default="{ row }">
            <button v-if="row.status === 'DRAFT'" class="link-btn" @click="publish(row)">发布</button>
            <button v-if="row.status === 'PUBLISHED'" class="link-btn danger" @click="offline(row)">下线</button>
          </template>
        </el-table-column>
      </el-table>
      <div class="ka-pager">
        <el-pagination v-model:current-page="page" layout="prev, pager, next, total" :total="total" :page-size="20" @current-change="load" />
      </div>
    </section>
  </div>

  <el-dialog v-model="dialog" title="新建公告" width="560px">
    <el-form label-width="80px">
      <el-form-item label="标题"><el-input v-model="form.title" maxlength="100" /></el-form-item>
      <el-form-item label="范围">
        <el-radio-group v-model="form.scope">
          <el-radio label="ALL">全站</el-radio>
          <el-radio label="SCHOOL">指定学校</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item v-if="form.scope === 'SCHOOL'" label="学校">
        <el-select v-model="form.schoolId" style="width: 100%">
          <el-option v-for="item in schools" :key="item.id" :label="item.name" :value="item.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="正文"><el-input v-model="form.content" type="textarea" :rows="6" maxlength="2000" /></el-form-item>
      <el-form-item label="立即发布"><el-switch v-model="form.publish" /></el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialog = false">取消</el-button>
      <el-button type="primary" :disabled="!form.title.trim() || !form.content.trim()" @click="submit">确定</el-button>
    </template>
  </el-dialog>
</template>
