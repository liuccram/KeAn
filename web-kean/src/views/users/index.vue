<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import type { EChartsCoreOption } from "echarts/core";
import {
  getUser,
  listUsers,
  resetUserPassword,
  updateUserRestrictions,
  updateUserStatus,
  type AdminUser,
  type GenderGroup,
  type StatusStats,
  type UserDetail
} from "@/api/user";
import { listCampuses, listProvinces, listSchools, type CampusItem, type SchoolItem } from "@/api/catalog";
import KpiCard from "@/components/KpiCard.vue";
import KaChart from "@/components/KaChart.vue";
import StatusTag from "@/components/StatusTag.vue";
import { formatNumber, formatTime, GENDER, TASK_STATUS, TASK_STATUS_TONE, USER_STATUS } from "@/utils/dicts";

const route = useRoute();

const loading = ref(false);
const list = ref<AdminUser[]>([]);
const total = ref(0);
const page = ref(1);
const summary = reactive({ total: 0, male: 0, female: 0, banned: 0 });
const genderGroups = ref<GenderGroup[]>([]);
const statusStats = reactive<StatusStats>({
  normal: 0,
  banned: 0,
  restricted: 0,
  forbidPublish: 0,
  forbidApply: 0,
  muted: 0
});
const pieSchoolId = ref<number | undefined>(undefined);
const schools = ref<SchoolItem[]>([]);
const campuses = ref<CampusItem[]>([]);
const provinces = ref<{ id: number; name: string }[]>([]);
const query = reactive({
  keyword: "",
  provinceId: undefined as number | undefined,
  schoolId: undefined as number | undefined,
  campusId: undefined as number | undefined,
  gender: "",
  status: ""
});
const detail = ref<UserDetail | null>(null);
const tab = ref("info");
const pwd = reactive({ visible: false, password: "" });
const restrict = reactive({
  visible: false,
  field: "muted" as "forbidPublish" | "forbidApply" | "muted",
  days: 7,
  user: null as AdminUser | null
});
const DAY_OPTIONS = [1, 3, 7, 30];
const FLAG_LABEL: Record<"forbidPublish" | "forbidApply" | "muted", string> = {
  forbidPublish: "禁发",
  forbidApply: "禁申",
  muted: "禁言"
};

async function loadSchools() {
  schools.value = (await listSchools({ provinceId: query.provinceId, size: 500 })).list || [];
}

async function onProvinceChange() {
  query.schoolId = undefined;
  query.campusId = undefined;
  campuses.value = [];
  await loadSchools();
  page.value = 1;
  await load();
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

async function load() {
  loading.value = true;
  try {
    const data = await listUsers({ ...query, page: page.value, size: 20 });
    list.value = data.list || [];
    total.value = data.total || 0;
    Object.assign(summary, data.summary || summary);
    Object.assign(statusStats, data.statusStats || statusStats);
    genderGroups.value = data.genderGroups || [];
    if (!detail.value && list.value[0]) {
      await openDetail(list.value[0]);
    }
  } finally {
    loading.value = false;
  }
}

async function openDetail(row: AdminUser) {
  tab.value = "info";
  detail.value = await getUser(row.id);
}

async function search() {
  page.value = 1;
  await load();
}

function reset() {
  query.keyword = "";
  query.provinceId = undefined;
  query.schoolId = undefined;
  query.campusId = undefined;
  query.gender = "";
  query.status = "";
  campuses.value = [];
  page.value = 1;
  loadSchools().then(() => load());
}

async function ban(user: AdminUser, banned: boolean) {
  await ElMessageBox.confirm(banned ? "确认封禁该用户？登录将被立即失效。" : "确认解封该用户？", "提示");
  await updateUserStatus(user.id, banned ? "BANNED" : "NORMAL");
  ElMessage.success("已更新");
  await load();
  if (detail.value?.user.id === user.id) {
    detail.value = await getUser(user.id);
  }
}

async function toggleFlag(user: AdminUser, field: "forbidPublish" | "forbidApply" | "muted") {
  if (user[field] === 1) {
    await ElMessageBox.confirm(`确认解除${FLAG_LABEL[field]}？`, "提示");
    await updateUserRestrictions(user.id, { [field]: 0 });
    ElMessage.success("已解除限制");
    await load();
    if (detail.value?.user.id === user.id) {
      detail.value = await getUser(user.id);
    }
    return;
  }
  restrict.user = user;
  restrict.field = field;
  restrict.days = 7;
  restrict.visible = true;
}

async function confirmRestrict() {
  if (!restrict.user) {
    return;
  }
  await updateUserRestrictions(restrict.user.id, { [restrict.field]: 1, days: restrict.days });
  ElMessage.success(`已${FLAG_LABEL[restrict.field]} ${restrict.days} 天`);
  restrict.visible = false;
  await load();
  if (detail.value?.user.id === restrict.user.id) {
    detail.value = await getUser(restrict.user.id);
  }
}

function untilText(until?: string | null) {
  return until ? `至 ${formatTime(until)}` : "";
}

async function submitPassword() {
  if (!detail.value) return;
  await resetUserPassword(detail.value.user.id, pwd.password);
  ElMessage.success("密码已重置，对方需重新登录");
  pwd.visible = false;
  pwd.password = "";
}

onMounted(async () => {
  provinces.value = (await listProvinces()) || [];
  await loadSchools();
  await load();
  await openFromQuery();
});

watch(
  () => route.query.id,
  () => {
    openFromQuery();
  }
);

async function openFromQuery() {
  const id = Number(route.query.id);
  if (!id) {
    return;
  }
  await openDetail({ id } as AdminUser);
}

function pieOption(
  slices: { name: string; value: number; color: string }[],
  radius: [string, string] = ["55%", "78%"]
): EChartsCoreOption {
  const data = slices
    .filter((item) => item.value > 0)
    .map((item) => ({ name: item.name, value: item.value, itemStyle: { color: item.color } }));
  return {
    tooltip: { trigger: "item" },
    series: [{ type: "pie", radius, label: { show: false }, data }]
  };
}

function genderPie(male: number, female: number, unknown: number) {
  return pieOption([
    { name: "男", value: male, color: "#14b8a6" },
    { name: "女", value: female, color: "#8b5cf6" },
    { name: "未填", value: unknown, color: "#94a3b8" }
  ]);
}

const statusPie = computed(() =>
  pieOption(
    [
      { name: "正常", value: statusStats.normal, color: "#22c55e" },
      { name: "封禁", value: statusStats.banned, color: "#f97316" },
      { name: "限制", value: statusStats.restricted, color: "#8b5cf6" }
    ],
    ["48%", "72%"]
  )
);

const statusTotal = computed(
  () => statusStats.normal + statusStats.banned + statusStats.restricted
);

const overallPie = computed(() => {
  const male = genderGroups.value.reduce((sum, item) => sum + (item.male || 0), 0);
  const female = genderGroups.value.reduce((sum, item) => sum + (item.female || 0), 0);
  const unknown = genderGroups.value.reduce((sum, item) => sum + (item.unknown || 0), 0);
  return genderPie(male, female, unknown);
});

const overallTotal = computed(() => genderGroups.value.reduce((sum, item) => sum + (item.total || 0), 0));

const displayedGenderGroups = computed(() => {
  if (pieSchoolId.value != null) {
    return genderGroups.value.filter((item) => (item.schoolId || 0) === pieSchoolId.value);
  }
  return genderGroups.value.slice(0, 2);
});

const displayedGenderPies = computed(() =>
  displayedGenderGroups.value.map((item) => ({
    key: item.schoolId || 0,
    title: item.schoolName || "未分组",
    male: item.male,
    female: item.female,
    option: genderPie(item.male, item.female, item.unknown)
  }))
);

const schoolPieOptions = computed(() =>
  genderGroups.value.map((item) => ({
    id: item.schoolId || 0,
    name: item.schoolName || "未填写学校"
  }))
);
</script>

<template>
  <div class="ka-page">
    <div class="kpis">
      <KpiCard compact label="用户数" :value="formatNumber(summary.total)" accent="blue" />
      <KpiCard compact label="男生" :value="formatNumber(summary.male)" accent="teal" />
      <KpiCard compact label="女生" :value="formatNumber(summary.female)" accent="purple" />
      <KpiCard compact label="封禁" :value="formatNumber(summary.banned)" accent="orange" />
    </div>

    <section class="ka-card">
      <div class="ka-card-head">
        <h3 class="ka-card-title">用户状态</h3>
      </div>
      <div class="status-row">
        <div class="gender-overall">
          <KaChart :option="statusPie" height="180px" />
          <div class="pie-center">{{ statusTotal }}</div>
          <p>学生账号</p>
        </div>
        <ul class="status-legend">
          <li><i class="dot normal" />正常 {{ formatNumber(statusStats.normal) }}</li>
          <li><i class="dot banned" />封禁 {{ formatNumber(statusStats.banned) }}</li>
          <li><i class="dot restricted" />限制 {{ formatNumber(statusStats.restricted) }}</li>
        </ul>
        <div class="measure-box">
          <p class="measure-title">限制举措（可叠加）</p>
          <div class="measure-grid">
            <div>
              <strong>{{ formatNumber(statusStats.forbidPublish) }}</strong>
              <span>禁发</span>
            </div>
            <div>
              <strong>{{ formatNumber(statusStats.forbidApply) }}</strong>
              <span>禁申</span>
            </div>
            <div>
              <strong>{{ formatNumber(statusStats.muted) }}</strong>
              <span>禁言</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <section class="ka-card">
      <div class="ka-card-head">
        <h3 class="ka-card-title">学校男女比例</h3>
        <el-select
          v-model="pieSchoolId"
          filterable
          clearable
          placeholder="搜索学校查看比例"
          style="width: 240px"
        >
          <el-option v-for="item in schoolPieOptions" :key="item.id" :label="item.name" :value="item.id" />
        </el-select>
      </div>
      <div class="gender-row">
        <div class="gender-overall">
          <KaChart :option="overallPie" height="180px" />
          <div class="pie-center">{{ overallTotal }}</div>
          <p>全站合计</p>
        </div>
        <div class="gender-groups">
          <div v-for="item in displayedGenderPies" :key="item.key" class="gender-item">
            <KaChart :option="item.option" height="120px" />
            <strong>{{ item.title }}</strong>
            <span>男 {{ item.male }} · 女 {{ item.female }}</span>
          </div>
          <p v-if="!displayedGenderPies.length" class="empty-panel">暂无该学校性别统计</p>
        </div>
      </div>
    </section>

    <div class="ka-split user">
      <section class="ka-card">
        <div class="ka-toolbar">
          <el-input v-model="query.keyword" placeholder="用户名 / 昵称 / 手机 / 邮箱" clearable style="width: 220px" @keyup.enter="search" />
          <el-select v-model="query.provinceId" clearable placeholder="省份" style="width: 130px" @change="onProvinceChange">
            <el-option v-for="item in provinces" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
          <el-select v-model="query.schoolId" clearable placeholder="学校" style="width: 180px" @change="onSchoolChange">
            <el-option v-for="item in schools" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
          <el-select v-model="query.campusId" clearable placeholder="校区" style="width: 150px" @change="search">
            <el-option v-for="item in campuses" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
          <el-select v-model="query.gender" clearable placeholder="性别" style="width: 110px" @change="search">
            <el-option label="男" value="MALE" />
            <el-option label="女" value="FEMALE" />
          </el-select>
          <el-select v-model="query.status" clearable placeholder="状态" style="width: 110px" @change="search">
            <el-option label="正常" value="NORMAL" />
            <el-option label="封禁" value="BANNED" />
          </el-select>
          <el-button type="primary" @click="search">查询</el-button>
          <el-button @click="reset">重置</el-button>
        </div>
        <el-table :data="list" v-loading="loading" highlight-current-row @row-click="openDetail">
          <el-table-column prop="id" label="用户ID" width="80" />
          <el-table-column label="用户" width="170">
            <template #default="{ row }">
              <div class="user-cell">
                <el-avatar :size="32" :src="row.avatarUrl || undefined">{{ (row.nickname || "用").slice(0, 1) }}</el-avatar>
                <span>{{ row.nickname }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="性别" width="70">
            <template #default="{ row }">{{ GENDER[row.gender || ""] || "—" }}</template>
          </el-table-column>
          <el-table-column prop="schoolName" label="学校" min-width="140" />
          <el-table-column prop="campusName" label="校区" width="110" />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <StatusTag :text="USER_STATUS[row.status]" :tone="row.status === 'BANNED' ? 'danger' : 'success'" />
            </template>
          </el-table-column>
          <el-table-column label="在线" width="80">
            <template #default="{ row }">
              <StatusTag :text="row.online ? '在线' : '离线'" :tone="row.online ? 'success' : 'muted'" />
            </template>
          </el-table-column>
          <el-table-column label="注册时间" width="150">
            <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="130" fixed="right">
            <template #default="{ row }">
              <button class="link-btn" @click.stop="openDetail(row)">详情</button>
              <button v-if="row.status !== 'BANNED'" class="link-btn danger" @click.stop="ban(row, true)">封禁</button>
              <button v-else class="link-btn" @click.stop="ban(row, false)">解封</button>
            </template>
          </el-table-column>
        </el-table>
        <div class="ka-pager">
          <el-pagination v-model:current-page="page" layout="prev, pager, next, total" :total="total" :page-size="20" @current-change="load" />
        </div>
      </section>

      <aside class="ka-card detail">
        <template v-if="detail">
          <div class="profile">
            <el-avatar :size="56" class="avatar" :src="detail.user.avatarUrl || undefined">{{ (detail.user.nickname || "用").slice(0, 1) }}</el-avatar>
            <div>
              <div class="name">
                {{ detail.user.nickname }}
                <StatusTag :text="USER_STATUS[detail.user.status]" :tone="detail.user.status === 'BANNED' ? 'danger' : 'success'" />
                <StatusTag :text="detail.user.online ? '在线' : '离线'" :tone="detail.user.online ? 'success' : 'muted'" />
              </div>
              <div class="uid">ID：{{ detail.user.id }} · {{ detail.user.username }}</div>
            </div>
          </div>
          <dl class="ka-kv">
            <dt>学校</dt>
            <dd>{{ detail.user.schoolName || "—" }}</dd>
            <dt>校区</dt>
            <dd>{{ detail.user.campusName || "—" }}</dd>
            <dt>性别</dt>
            <dd>{{ GENDER[detail.user.gender || ""] || "—" }}</dd>
            <dt>注册时间</dt>
            <dd>{{ formatTime(detail.user.createdAt) }}</dd>
            <dt>最近登录</dt>
            <dd>{{ formatTime(detail.user.lastLoginAt) }}</dd>
            <dt>发布 / 完成</dt>
            <dd>{{ detail.published.total || 0 }} / {{ detail.user.completedCount || 0 }}</dd>
          </dl>
          <div class="acts">
            <el-button v-if="detail.user.status === 'BANNED'" type="success" plain @click="ban(detail.user, false)">解封账户</el-button>
            <el-button v-else type="danger" plain @click="ban(detail.user, true)">禁用账户</el-button>
            <el-button @click="pwd.visible = true">重置密码</el-button>
          </div>
          <el-tabs v-model="tab">
            <el-tab-pane label="基本信息" name="info">
              <div class="flags">
                <div class="flag-row">
                  <el-switch :model-value="detail.user.forbidPublish === 1" active-text="禁发" @change="toggleFlag(detail.user, 'forbidPublish')" />
                  <span v-if="detail.user.forbidPublish === 1" class="until">{{ untilText(detail.user.forbidPublishUntil) }}</span>
                </div>
                <div class="flag-row">
                  <el-switch :model-value="detail.user.forbidApply === 1" active-text="禁申" @change="toggleFlag(detail.user, 'forbidApply')" />
                  <span v-if="detail.user.forbidApply === 1" class="until">{{ untilText(detail.user.forbidApplyUntil) }}</span>
                </div>
                <div class="flag-row">
                  <el-switch :model-value="detail.user.muted === 1" active-text="禁言" @change="toggleFlag(detail.user, 'muted')" />
                  <span v-if="detail.user.muted === 1" class="until">{{ untilText(detail.user.mutedUntil) }}</span>
                </div>
              </div>
              <p class="hint">代课完成 {{ detail.user.completedCount || 0 }} · 发布完成 {{ detail.user.publishCompletedCount || 0 }} · 取消 {{ detail.user.cancelledCount || 0 }} · 被举报 {{ detail.user.reportedCount || 0 }}</p>
              <p class="hint">发布评分 {{ detail.user.publishRatingAvg ?? "—" }}（{{ detail.user.publishRatingCount || 0 }}） · 代课评分 {{ detail.user.applyRatingAvg ?? "—" }}（{{ detail.user.applyRatingCount || 0 }}）</p>
            </el-tab-pane>
            <el-tab-pane label="代课记录" name="tasks">
              <el-table :data="detail.published.list" size="small">
                <el-table-column prop="courseName" label="课程" />
                <el-table-column label="状态" width="90">
                  <template #default="{ row }">
                    <StatusTag :text="TASK_STATUS[row.status] || row.status" :tone="TASK_STATUS_TONE[row.status]" />
                  </template>
                </el-table-column>
              </el-table>
            </el-tab-pane>
            <el-tab-pane label="履约记录" name="reviews">
              <el-table :data="detail.received" size="small">
                <el-table-column prop="fromNickname" label="评价人" width="90" />
                <el-table-column label="对象" width="90">
                  <template #default="{ row }">
                    {{ row.targetRole === "PUBLISHER" ? "发布可信度" : row.targetRole === "APPLICANT" ? "代课可信度" : "—" }}
                  </template>
                </el-table-column>
                <el-table-column prop="rating" label="评分" width="60" />
                <el-table-column prop="content" label="内容" />
              </el-table>
            </el-tab-pane>
          </el-tabs>
        </template>
        <div v-else class="empty-panel">请选择用户查看详情</div>
      </aside>
    </div>

    <el-dialog v-model="restrict.visible" :title="`设置${FLAG_LABEL[restrict.field]}天数`" width="400px">
      <p class="hint">限制到期后自动解除。</p>
      <el-radio-group v-model="restrict.days">
        <el-radio-button v-for="item in DAY_OPTIONS" :key="item" :label="item">{{ item }} 天</el-radio-button>
      </el-radio-group>
      <template #footer>
        <el-button @click="restrict.visible = false">取消</el-button>
        <el-button type="primary" @click="confirmRestrict">确定</el-button>
      </template>
    </el-dialog>
    <el-dialog v-model="pwd.visible" title="重置密码" width="400px">
      <el-input v-model="pwd.password" type="password" show-password placeholder="8-32 位新密码" />
      <template #footer>
        <el-button @click="pwd.visible = false">取消</el-button>
        <el-button type="primary" :disabled="pwd.password.length < 8" @click="submitPassword">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.kpis {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
}
.status-row {
  display: grid;
  grid-template-columns: 220px 180px 1fr;
  gap: 16px;
  align-items: center;
}
.status-legend {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
  font-size: 13px;
  color: var(--ka-text);
}
.dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-right: 8px;
}
.dot.normal {
  background: #22c55e;
}
.dot.banned {
  background: #f97316;
}
.dot.restricted {
  background: #8b5cf6;
}
.measure-box {
  background: #f8fafc;
  border-radius: 12px;
  padding: 16px 18px;
}
.measure-title {
  margin: 0 0 12px;
  color: var(--ka-muted);
  font-size: 12px;
}
.measure-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
  text-align: center;
}
.measure-grid strong {
  display: block;
  font-size: 20px;
  color: var(--ka-text);
}
.measure-grid span {
  color: var(--ka-muted);
  font-size: 12px;
}
.gender-row {
  display: grid;
  grid-template-columns: 220px 1fr;
  gap: 16px;
  align-items: start;
}
.gender-overall {
  position: relative;
  text-align: center;
  color: var(--ka-muted);
  font-size: 12px;
}
.pie-center {
  position: absolute;
  top: 70px;
  left: 0;
  right: 0;
  font-size: 20px;
  font-weight: 700;
  color: var(--ka-text);
  pointer-events: none;
}
.gender-groups {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
  gap: 8px;
}
.gender-item {
  text-align: center;
  font-size: 12px;
  color: var(--ka-muted);
}
.gender-item strong {
  display: block;
  color: var(--ka-text);
  margin-top: 2px;
}
.detail {
  min-height: 520px;
}
.user-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}
.user-cell span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.profile {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-bottom: 16px;
}
.avatar {
  background: #3b82f6;
  color: #fff;
}
.name {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 650;
}
.uid {
  margin-top: 4px;
  color: var(--ka-muted);
  font-size: 12px;
}
.acts {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 16px 0 8px;
}
.flags {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.flag-row {
  display: flex;
  align-items: center;
  gap: 10px;
}
.until {
  color: var(--ka-muted);
  font-size: 12px;
}
.hint {
  color: var(--ka-muted);
  font-size: 12px;
}
@media (max-width: 1280px) {
  .kpis {
    grid-template-columns: repeat(2, 1fr);
  }
  .status-row {
    grid-template-columns: 1fr;
  }
}
</style>
