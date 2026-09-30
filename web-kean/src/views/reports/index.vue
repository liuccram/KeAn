<script setup lang="ts">
import { onMounted, reactive, ref, watch, computed } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import { getAdminReport, handleAdminAppeal, handleAdminReport, hasPendingAppeal, listAdminReports, type AdminReportItem } from "@/api/report";
import { updateUserRestrictions, updateUserStatus } from "@/api/user";
import StatusTag from "@/components/StatusTag.vue";
import { formatTime, APPEAL_STATUS, HANDLE_RESULT, REPORT_STATUS, REPORT_STATUS_TONE, REPORT_TARGET } from "@/utils/dicts";

const route = useRoute();
const router = useRouter();
const loading = ref(false);
const list = ref<AdminReportItem[]>([]);
const total = ref(0);
const page = ref(1);
const status = ref("");
const type = ref("");
const kind = ref("");
const targetType = ref("");
const appealOnly = ref(false);
const current = ref<AdminReportItem | null>(null);
const form = reactive({ result: "WARN", remark: "" });
const restrict = reactive({ visible: false, field: "muted" as "forbidPublish" | "forbidApply" | "muted", days: 7 });
const appealRemark = ref("");
const DAY_OPTIONS = [1, 3, 7, 30];
const FLAG_LABEL: Record<"forbidPublish" | "forbidApply" | "muted", string> = {
  forbidPublish: "禁发",
  forbidApply: "禁申",
  muted: "禁言"
};

const reportTypeOptions = [
  { value: "FAKE", label: "虚假信息" },
  { value: "HARASS", label: "骚扰辱骂" },
  { value: "MALICIOUS_CANCEL", label: "恶意取消" },
  { value: "FRAUD", label: "欺诈诱导" },
  { value: "VIOLATION", label: "违规内容" },
  { value: "OTHER", label: "其他" }
];
const feedbackTypeOptions = [
  { value: "SUGGESTION", label: "功能建议" },
  { value: "BUG", label: "故障问题" },
  { value: "ACCOUNT", label: "账号问题" },
  { value: "SERVICE", label: "服务体验" },
  { value: "OTHER", label: "其他事宜" }
];
const typeOptions = computed(() => {
  if (kind.value === "FEEDBACK") return feedbackTypeOptions;
  if (kind.value === "REPORT") return reportTypeOptions;
  return [...reportTypeOptions, ...feedbackTypeOptions.filter((item) => item.value !== "OTHER")];
});
const isFeedback = computed(() => current.value?.targetType === "FEEDBACK");

async function load() {
  loading.value = true;
  try {
    const data = await listAdminReports({
      status: status.value || undefined,
      type: type.value || undefined,
      targetType: targetType.value || undefined,
      appealStatus: appealOnly.value ? "PENDING" : undefined,
      kind: kind.value || undefined,
      page: page.value,
      size: 20
    });
    list.value = data.list || [];
    total.value = data.total || 0;
    if (!current.value && list.value[0]) {
      await openDetail(list.value[0].id, false);
    }
  } finally {
    loading.value = false;
  }
}

function onRowClick(row: AdminReportItem) {
  openDetail(row.id);
}

async function openDetail(id: number, syncQuery = true) {
  current.value = await getAdminReport(id);
  form.result = current.value?.targetType === "FEEDBACK" ? "REPLY" : "WARN";
  form.remark = "";
  if (syncQuery) {
    await router.replace({ path: "/reports", query: { id: String(id) } });
  }
}

async function submitHandle(result?: string) {
  if (!current.value) return;
  if (result) form.result = result;
  await handleAdminReport(current.value.id, form.result, form.remark || undefined);
  ElMessage.success(current.value.targetType === "FEEDBACK" ? "已处理，结果已通知用户" : "已处理，处罚结果已同步通知举报人");
  await openDetail(current.value.id);
  await load();
}

async function refreshCurrent() {
  if (!current.value) return;
  await openDetail(current.value.id, false);
}

async function banTarget(banned: boolean) {
  if (!current.value?.targetUserId) return;
  await ElMessageBox.confirm(banned ? "确认封禁该用户？" : "确认解封该用户？", "提示");
  await updateUserStatus(current.value.targetUserId, banned ? "BANNED" : "NORMAL");
  ElMessage.success("已更新");
  await refreshCurrent();
}

async function toggleTargetFlag(field: "forbidPublish" | "forbidApply" | "muted") {
  if (!current.value?.targetUserId) return;
  const on = current.value[field] === 1;
  if (on) {
    await ElMessageBox.confirm(`确认解除${FLAG_LABEL[field]}？`, "提示");
    await updateUserRestrictions(current.value.targetUserId, { [field]: 0 });
    ElMessage.success("已解除限制");
    await refreshCurrent();
    return;
  }
  restrict.field = field;
  restrict.days = 7;
  restrict.visible = true;
}

async function confirmRestrict() {
  if (!current.value?.targetUserId) return;
  await updateUserRestrictions(current.value.targetUserId, { [restrict.field]: 1, days: restrict.days });
  ElMessage.success(`已${FLAG_LABEL[restrict.field]} ${restrict.days} 天`);
  restrict.visible = false;
  await refreshCurrent();
}

async function submitAppeal(appealId: number, result: "ACCEPT" | "REJECT") {
  if (!current.value) return;
  await handleAdminAppeal(current.value.id, appealId, result, appealRemark.value || undefined);
  ElMessage.success(result === "ACCEPT" ? "已受理申诉" : "已驳回申诉");
  appealRemark.value = "";
  await refreshCurrent();
}

onMounted(async () => {
  if (route.query.appeal === "1") {
    appealOnly.value = true;
  }
  await load();
  const id = Number(route.query.id);
  if (id) {
    await openDetail(id);
  }
});

watch(
  () => [route.query.appeal, route.query.id],
  async ([appeal, id]) => {
    if (appeal === "1" && !appealOnly.value) {
      appealOnly.value = true;
      page.value = 1;
      await load();
    }
    const reportId = Number(id);
    if (reportId) {
      await openDetail(reportId);
    }
  }
);
</script>

<template>
  <div class="ka-page">
    <div class="ka-split report">
      <section class="ka-card">
        <div class="ka-toolbar">
          <el-select v-model="kind" clearable placeholder="全部类型" style="width: 140px" @change="page = 1; type = ''; targetType = ''; appealOnly = false; load()">
            <el-option label="举报" value="REPORT" />
            <el-option label="反馈" value="FEEDBACK" />
          </el-select>
          <el-select v-model="status" clearable placeholder="全部状态" style="width: 140px" @change="page = 1; load()">
            <el-option label="待处理" value="PENDING" />
            <el-option label="已处理" value="RESOLVED" />
            <el-option label="已驳回" value="REJECTED" />
          </el-select>
          <el-select v-model="type" clearable placeholder="原因" style="width: 140px" @change="page = 1; load()">
            <el-option v-for="item in typeOptions" :key="item.value + item.label" :label="item.label" :value="item.value" />
          </el-select>
          <el-select v-if="kind !== 'FEEDBACK'" v-model="targetType" clearable placeholder="对象" style="width: 120px" @change="page = 1; load()">
            <el-option label="用户" value="USER" />
            <el-option label="代课" value="TASK" />
            <el-option label="消息" value="MESSAGE" />
          </el-select>
          <el-checkbox v-if="kind !== 'FEEDBACK'" v-model="appealOnly" @change="page = 1; load()">待复核申诉</el-checkbox>
          <el-button type="primary" @click="page = 1; load()">查询</el-button>
          <el-button @click="status = ''; type = ''; targetType = ''; kind = ''; appealOnly = false; page = 1; load()">重置</el-button>
        </div>
        <el-table :data="list" v-loading="loading" highlight-current-row @row-click="onRowClick">
          <el-table-column prop="id" label="编号" width="80" />
          <el-table-column prop="reporterNickname" label="提交人" width="100" />
          <el-table-column prop="typeLabel" label="类型" width="100" />
          <el-table-column prop="targetLabel" label="对象" min-width="140" />
          <el-table-column label="时间" width="150">
            <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <StatusTag :text="REPORT_STATUS[row.status] || row.status" :tone="REPORT_STATUS_TONE[row.status]" />
              <StatusTag v-if="hasPendingAppeal(row)" text="待复核申诉" tone="warning" />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="80" fixed="right">
            <template #default="{ row }">
              <button class="link-btn" @click.stop="openDetail(row.id)">详情</button>
            </template>
          </el-table-column>
        </el-table>
        <div class="ka-pager">
          <el-pagination v-model:current-page="page" layout="prev, pager, next, total" :total="total" :page-size="20" @current-change="load" />
        </div>
      </section>

      <aside class="ka-card">
        <template v-if="current">
          <div class="ka-card-head">
            <h3 class="ka-card-title">BJ{{ String(current.id).padStart(8, "0") }}</h3>
            <StatusTag :text="REPORT_STATUS[current.status]" :tone="REPORT_STATUS_TONE[current.status]" />
          </div>
          <h4>基本信息</h4>
          <dl class="ka-kv">
            <dt>{{ isFeedback ? "反馈人" : "举报人" }}</dt>
            <dd>{{ current.reporterNickname }}</dd>
            <dt>{{ isFeedback ? "类别" : "被举报对象" }}</dt>
            <dd>{{ REPORT_TARGET[current.targetType] }}{{ isFeedback ? "" : " · " + current.targetLabel }}</dd>
            <dt>{{ isFeedback ? "反馈类型" : "举报类型" }}</dt>
            <dd>{{ current.typeLabel }}</dd>
            <dt>发生时间</dt>
            <dd>{{ formatTime(current.createdAt) }}</dd>
          </dl>
          <h4>{{ isFeedback ? "反馈内容" : "举报描述" }}</h4>
          <p class="desc">{{ current.description || "—" }}</p>
          <div v-if="current.images?.length" class="images">
            <el-image v-for="src in current.images" :key="src" :src="src" :preview-src-list="current.images" fit="cover" class="img" />
          </div>
          <h4>处理结果</h4>
          <p class="desc">{{ current.handleResult ? HANDLE_RESULT[current.handleResult] || current.handleResult : "尚未处理" }}</p>
          <template v-if="current.status === 'PENDING' || current.status === 'PROCESSING'">
            <h4>处理措施</h4>
            <div v-if="isFeedback" class="acts">
              <el-button type="primary" plain @click="submitHandle('REPLY')">回复处理</el-button>
              <el-button @click="submitHandle('REJECT')">驳回</el-button>
            </div>
            <div v-else class="acts">
              <el-button type="warning" plain @click="submitHandle('WARN')">警告</el-button>
              <el-button type="primary" plain @click="submitHandle('RESTRICT')">限制功能</el-button>
              <el-button type="danger" plain @click="submitHandle('BAN')">封禁账号</el-button>
              <el-button @click="submitHandle('REJECT')">驳回举报</el-button>
            </div>
            <el-input v-model="form.remark" type="textarea" :rows="3" maxlength="500" :placeholder="isFeedback ? '回复或备注' : '处理备注'" class="mt" />
          </template>
          <h4>处理记录</h4>
          <p class="desc">{{ current.handlerNickname || "—" }} · {{ formatTime(current.handledAt) }} · {{ current.handleRemark || "暂无备注" }}</p>

          <template v-if="current.targetUserId">
            <h4>被举报人管控</h4>
            <p class="desc">{{ current.targetUserNickname }} · {{ current.targetUserStatus === "BANNED" ? "已封禁" : "正常" }}</p>
            <div class="acts">
              <el-button v-if="current.targetUserStatus === 'BANNED'" type="success" plain @click="banTarget(false)">解封</el-button>
              <el-button v-else type="danger" plain @click="banTarget(true)">封禁</el-button>
              <el-button plain @click="toggleTargetFlag('forbidPublish')">{{ current.forbidPublish === 1 ? "解除禁发" : "禁发" }}</el-button>
              <el-button plain @click="toggleTargetFlag('forbidApply')">{{ current.forbidApply === 1 ? "解除禁申" : "禁申" }}</el-button>
              <el-button plain @click="toggleTargetFlag('muted')">{{ current.muted === 1 ? "解除禁言" : "禁言" }}</el-button>
            </div>
          </template>

          <template v-if="!isFeedback">
            <h4>申诉</h4>
            <template v-if="current.appeals?.length">
              <div v-for="item in current.appeals" :key="item.id" class="appeal">
                <p class="desc"><strong>{{ item.nickname }}</strong> · {{ APPEAL_STATUS[item.status] || item.status }} · {{ formatTime(item.createdAt) }}</p>
                <p class="desc">{{ item.content }}</p>
                <div v-if="item.images?.length" class="images">
                  <el-image v-for="src in item.images" :key="src" :src="src" :preview-src-list="item.images" fit="cover" class="img" />
                </div>
                <p v-if="item.handleRemark" class="desc">复核：{{ item.handleRemark }}</p>
                <div v-if="item.status === 'PENDING'" class="acts">
                  <el-button type="primary" plain @click="submitAppeal(item.id, 'ACCEPT')">受理</el-button>
                  <el-button @click="submitAppeal(item.id, 'REJECT')">驳回申诉</el-button>
                </div>
              </div>
              <el-input v-model="appealRemark" type="textarea" :rows="2" maxlength="500" placeholder="申诉复核备注" class="mt" />
            </template>
            <p v-else class="desc">暂无申诉</p>
          </template>
        </template>
        <div v-else class="empty-panel">请选择记录查看详情</div>
      </aside>
    </div>
    <el-dialog v-model="restrict.visible" :title="`设置${FLAG_LABEL[restrict.field]}天数`" width="400px">
      <el-radio-group v-model="restrict.days">
        <el-radio-button v-for="item in DAY_OPTIONS" :key="item" :label="item">{{ item }} 天</el-radio-button>
      </el-radio-group>
      <template #footer>
        <el-button @click="restrict.visible = false">取消</el-button>
        <el-button type="primary" @click="confirmRestrict">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
h4 {
  margin: 16px 0 8px;
  font-size: 13px;
  color: var(--ka-muted);
  font-weight: 600;
}
.desc {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
}
.images {
  display: flex;
  gap: 8px;
  margin-top: 8px;
}
.img {
  width: 72px;
  height: 72px;
  border-radius: 8px;
}
.acts {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.mt {
  margin-top: 10px;
}
.appeal {
  padding: 8px 0;
  border-bottom: 1px solid var(--ka-border);
}
</style>
