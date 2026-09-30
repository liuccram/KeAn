<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import { cancelTask, getTask, listTaskApplications, listTasks, type ApplicationItem, type TaskDetail } from "@/api/task";
import { listCampuses, listSchools, type CampusItem, type SchoolItem } from "@/api/catalog";
import type { TaskItem } from "@/api/user";
import StatusTag from "@/components/StatusTag.vue";
import { APP_STATUS, formatTime, TASK_STATUS, TASK_STATUS_TONE } from "@/utils/dicts";

const route = useRoute();
const router = useRouter();
const loading = ref(false);
const list = ref<TaskItem[]>([]);
const total = ref(0);
const page = ref(1);
const schools = ref<SchoolItem[]>([]);
const campuses = ref<CampusItem[]>([]);
const query = reactive({
  keyword: "",
  schoolId: undefined as number | undefined,
  campusId: undefined as number | undefined,
  status: "",
  taskDate: ""
});
const detail = ref<TaskDetail | null>(null);
const applications = ref<ApplicationItem[]>([]);
const cancelForm = reactive({ visible: false, reason: "" });

async function load() {
  loading.value = true;
  try {
    const data = await listTasks({ ...query, page: page.value, size: 20 });
    list.value = data.list || [];
    total.value = data.total || 0;
  } finally {
    loading.value = false;
  }
}

async function onSchoolChange() {
  query.campusId = undefined;
  campuses.value = [];
  if (query.schoolId) {
    campuses.value = (await listCampuses(query.schoolId)).list || [];
  }
  page.value = 1;
  await load();
}

async function openDetail(id: number) {
  detail.value = await getTask(id);
  applications.value = await listTaskApplications(id);
  if (String(route.query.id) !== String(id)) {
    await router.replace({ path: "/tasks", query: { id: String(id) } });
  }
}

function closeDetail() {
  detail.value = null;
  router.replace({ path: "/tasks" });
}

function canCancel(status?: string) {
  return ["WAITING", "APPLYING", "MATCHED", "CONFIRMED", "IN_PROGRESS"].includes(status || "");
}

async function submitCancel() {
  if (!detail.value) return;
  await ElMessageBox.confirm("确认强制取消该代课？双方将收到通知。", "提示");
  await cancelTask(detail.value.task.id, cancelForm.reason);
  ElMessage.success("已取消");
  cancelForm.visible = false;
  cancelForm.reason = "";
  await openDetail(detail.value.task.id);
  await load();
}

onMounted(async () => {
  schools.value = (await listSchools()).list || [];
  await load();
  const id = Number(route.query.id);
  if (id) {
    await openDetail(id);
  }
});
</script>

<template>
  <div class="ka-page">
    <template v-if="!detail">
      <section class="ka-card">
        <div class="ka-toolbar">
          <el-input v-model="query.keyword" placeholder="课程 / 教室 / 任务 ID" clearable style="width: 200px" @keyup.enter="page = 1; load()" />
          <el-select v-model="query.schoolId" clearable placeholder="学校" style="width: 180px" @change="onSchoolChange">
            <el-option v-for="item in schools" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
          <el-select v-model="query.campusId" clearable placeholder="校区" style="width: 150px" @change="page = 1; load()">
            <el-option v-for="item in campuses" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
          <el-select v-model="query.status" clearable placeholder="状态" style="width: 130px" @change="page = 1; load()">
            <el-option v-for="(label, value) in TASK_STATUS" :key="value" :label="label" :value="value" />
          </el-select>
          <el-date-picker v-model="query.taskDate" value-format="YYYY-MM-DD" placeholder="上课日期" style="width: 150px" @change="page = 1; load()" />
          <el-button type="primary" @click="page = 1; load()">查询</el-button>
          <el-button @click="query.keyword = ''; query.schoolId = undefined; query.campusId = undefined; query.status = ''; query.taskDate = ''; page = 1; load()">重置</el-button>
        </div>
        <el-table :data="list" v-loading="loading">
          <el-table-column prop="id" label="任务ID" width="80" />
          <el-table-column prop="courseName" label="课程" min-width="140" />
          <el-table-column prop="publisherNickname" label="发布者" width="100" />
          <el-table-column prop="applicantNickname" label="代课者" width="100" />
          <el-table-column label="上课时间" width="170">
            <template #default="{ row }">{{ row.taskDate }} {{ row.startTime }}-{{ row.endTime }}</template>
          </el-table-column>
          <el-table-column prop="campusName" label="校区" width="110" />
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <StatusTag :text="TASK_STATUS[row.status] || row.status" :tone="TASK_STATUS_TONE[row.status]" />
            </template>
          </el-table-column>
          <el-table-column prop="applyCount" label="申请" width="70" />
          <el-table-column label="操作" width="90" fixed="right">
            <template #default="{ row }">
              <button class="link-btn" @click="openDetail(row.id)">详情</button>
            </template>
          </el-table-column>
        </el-table>
        <div class="ka-pager">
          <el-pagination v-model:current-page="page" layout="prev, pager, next, total" :total="total" :page-size="20" @current-change="load" />
        </div>
      </section>
    </template>

    <template v-else>
      <el-button link type="primary" @click="closeDetail">← 返回列表</el-button>
      <div class="ka-split task-detail">
        <section class="ka-card">
          <div class="ka-card-head">
            <div>
              <h3 class="ka-card-title">{{ detail.task.courseName }} · TK{{ String(detail.task.id).padStart(8, "0") }}</h3>
              <p class="sub">基本信息 · 申请记录 · 履约人员 · 操作日志</p>
            </div>
            <StatusTag :text="TASK_STATUS[detail.task.status]" :tone="TASK_STATUS_TONE[detail.task.status]" />
          </div>
          <div class="detail-grid">
            <dl class="ka-kv">
              <dt>课程名称</dt>
              <dd>{{ detail.task.courseName }}</dd>
              <dt>发布者</dt>
              <dd>{{ detail.task.publisherNickname || "—" }}</dd>
              <dt>学校 / 校区</dt>
              <dd>{{ detail.task.schoolName }} / {{ detail.task.campusName }}</dd>
              <dt>上课地点</dt>
              <dd>{{ detail.task.building }} {{ detail.task.classroom }}</dd>
              <dt>上课时间</dt>
              <dd>{{ detail.task.taskDate }} {{ detail.task.startTime }}-{{ detail.task.endTime }}</dd>
              <dt>代课人</dt>
              <dd>{{ detail.task.applicantNickname || "暂无" }}</dd>
              <dt>性别要求</dt>
              <dd>{{ !detail.genderRequirement || detail.genderRequirement === "ANY" ? "不限" : detail.genderRequirement }}</dd>
              <dt>是否拍照</dt>
              <dd>{{ detail.requirePhoto === 1 ? "是" : "否" }}</dd>
              <dt>取消原因</dt>
              <dd>{{ detail.cancelReason || "—" }}</dd>
            </dl>
            <div>
              <h4>状态时间轴</h4>
              <el-timeline>
                <el-timeline-item v-for="item in detail.timeline" :key="item.event + item.at" :timestamp="formatTime(item.at)">
                  {{ item.label }}
                </el-timeline-item>
              </el-timeline>
            </div>
          </div>
          <div v-if="detail.fulfillPhotoUrl" class="photo">
            <img :src="detail.fulfillPhotoUrl" alt="履约照片" />
          </div>
          <el-button v-if="canCancel(detail.task.status)" type="danger" @click="cancelForm.visible = true">强制取消</el-button>
        </section>
        <section class="ka-card">
          <div class="ka-card-head">
            <h3 class="ka-card-title">申请记录</h3>
          </div>
          <el-table :data="applications" size="small">
            <el-table-column prop="nickname" label="申请人" width="90" />
            <el-table-column prop="schoolName" label="学校" />
            <el-table-column label="时间" width="130">
              <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
            </el-table-column>
            <el-table-column label="状态" width="80">
              <template #default="{ row }">
                <StatusTag :text="APP_STATUS[row.status] || row.status" :tone="row.status === 'ACCEPTED' ? 'success' : row.status === 'REJECTED' ? 'danger' : 'warning'" />
              </template>
            </el-table-column>
          </el-table>
        </section>
      </div>
    </template>

    <el-dialog v-model="cancelForm.visible" title="强制取消" width="420px">
      <el-input v-model="cancelForm.reason" type="textarea" :rows="3" maxlength="255" placeholder="请填写取消原因" />
      <template #footer>
        <el-button @click="cancelForm.visible = false">取消</el-button>
        <el-button type="danger" :disabled="cancelForm.reason.trim().length < 2" @click="submitCancel">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.sub {
  margin: 4px 0 0;
  color: var(--ka-muted);
  font-size: 12px;
}
.detail-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 24px;
}
h4 {
  margin: 0 0 12px;
  font-size: 14px;
}
.photo img {
  max-width: 180px;
  max-height: 160px;
  object-fit: cover;
  margin: 12px 0;
  border-radius: 8px;
}
@media (max-width: 1100px) {
  .detail-grid {
    grid-template-columns: 1fr;
  }
}
</style>
