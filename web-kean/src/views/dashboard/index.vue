<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from "vue";
import { useRouter } from "vue-router";
import type { EChartsCoreOption } from "echarts/core";
import { getDashboard, type DashboardData } from "@/api/dashboard";
import KpiCard from "@/components/KpiCard.vue";
import KaChart from "@/components/KaChart.vue";
import OnlineUsersDialog from "@/components/OnlineUsersDialog.vue";
import StatusTag from "@/components/StatusTag.vue";
import {
  formatNumber,
  formatTime,
  REPORT_STATUS,
  REPORT_STATUS_TONE,
  TASK_STATUS,
  TASK_STATUS_COLOR,
  TASK_STATUS_TONE
} from "@/utils/dicts";

const router = useRouter();
const loading = ref(false);
const data = ref<DashboardData | null>(null);
const onlineVisible = ref(false);

const lineOption = computed<EChartsCoreOption>(() => {
  const trend = data.value?.taskTrend || [];
  return {
    color: ["#3b82f6", "#14b8a6"],
    tooltip: { trigger: "axis" },
    legend: { data: ["发布数量", "完成数量"], right: 8, top: 0, icon: "circle", itemWidth: 8 },
    grid: { left: 36, right: 16, top: 36, bottom: 24 },
    xAxis: {
      type: "category",
      data: trend.map((item) => item.date.slice(5)),
      axisLine: { lineStyle: { color: "#e2e8f0" } },
      axisLabel: { color: "#94a3b8" }
    },
    yAxis: {
      type: "value",
      splitLine: { lineStyle: { color: "#f1f5f9" } },
      axisLabel: { color: "#94a3b8" }
    },
    series: [
      {
        name: "发布数量",
        type: "line",
        smooth: true,
        symbol: "circle",
        symbolSize: 6,
        areaStyle: { color: "rgba(59,130,246,.12)" },
        data: trend.map((item) => item.published)
      },
      {
        name: "完成数量",
        type: "line",
        smooth: true,
        symbol: "circle",
        symbolSize: 6,
        areaStyle: { color: "rgba(20,184,166,.12)" },
        data: trend.map((item) => item.completed)
      }
    ]
  };
});

const pieOption = computed<EChartsCoreOption>(() => {
  const items = data.value?.taskStatus || [];
  return {
    tooltip: { trigger: "item" },
    legend: { show: false },
    series: [
      {
        type: "pie",
        radius: ["58%", "78%"],
        center: ["50%", "50%"],
        label: { show: false },
        data: items.filter((item) => item.count > 0).map((item) => ({
          name: TASK_STATUS[item.status] || item.status,
          value: item.count,
          itemStyle: { color: TASK_STATUS_COLOR[item.status] || "#94a3b8" }
        }))
      }
    ]
  };
});

const pieTotal = computed(() => (data.value?.taskStatus || []).reduce((sum, item) => sum + item.count, 0));

const pieLegend = computed(() => {
  const items = (data.value?.taskStatus || []).filter((item) => item.count > 0);
  const total = pieTotal.value || 1;
  return items.map((item) => ({
    ...item,
    label: TASK_STATUS[item.status] || item.status,
    percent: ((item.count / total) * 100).toFixed(1)
  }));
});

async function load() {
  loading.value = true;
  try {
    data.value = await getDashboard();
  } finally {
    loading.value = false;
  }
}

let timer: number | undefined;
onMounted(() => {
  load();
  timer = window.setInterval(() => {
    getDashboard()
      .then((result) => {
        data.value = result;
      })
      .catch(() => undefined);
  }, 15000);
});
onUnmounted(() => {
  if (timer) {
    window.clearInterval(timer);
  }
});

function goTask(row: { id: number }) {
  router.push({ path: "/tasks", query: { id: String(row.id) } });
}

function goReport(row: { id: number }) {
  router.push({ path: "/reports", query: { id: String(row.id) } });
}
</script>

<template>
  <div v-loading="loading" class="ka-page">
    <section class="welcome">
      <div>
        <h2>欢迎回来，超级管理员</h2>
        <p>这里是课安管理后台，为您提供师生代课的全周期服务与管理功能。</p>
      </div>
    </section>

    <div class="kpis five">
      <button class="kpi-hit" type="button" @click="onlineVisible = true">
        <KpiCard label="实时在线" :value="formatNumber(data?.onlineUserTotal)" accent="teal" hint="点击查看名单" />
      </button>
      <KpiCard label="用户总量" :value="formatNumber(data?.userTotal)" accent="blue" hint="全部注册用户" />
      <KpiCard label="代课用户" :value="formatNumber(data?.substituteUserTotal)" accent="teal" hint="发布或申请过代课" />
      <KpiCard label="待处理举报及反馈" :value="formatNumber(data?.pendingReportTotal)" accent="orange" hint="需尽快处理" />
      <KpiCard label="进行中任务" :value="formatNumber(data?.activeTaskTotal)" accent="purple" hint="已匹配至进行中" />
    </div>

    <div class="charts">
      <section class="ka-card">
        <div class="ka-card-head">
          <h3 class="ka-card-title">近 7 天代课任务趋势</h3>
        </div>
        <KaChart :option="lineOption" height="250px" />
      </section>
      <section class="ka-card pie-card">
        <div class="ka-card-head">
          <h3 class="ka-card-title">任务状态分布</h3>
        </div>
        <div class="pie-wrap">
          <div class="pie-chart">
            <KaChart :option="pieOption" height="220px" />
            <div class="pie-center">{{ pieTotal }}</div>
          </div>
          <ul class="legend">
            <li v-for="item in pieLegend" :key="item.status">
              <i :style="{ background: TASK_STATUS_COLOR[item.status] }" />
              <span>{{ item.label }}</span>
              <b>{{ item.count }}</b>
              <em>{{ item.percent }}%</em>
            </li>
          </ul>
        </div>
      </section>
    </div>

    <div class="tables">
      <section class="ka-card">
        <div class="ka-card-head">
          <h3 class="ka-card-title">近期代课任务</h3>
          <el-button link type="primary" @click="router.push('/tasks')">查看全部</el-button>
        </div>
        <el-table :data="data?.recentTasks || []" size="small" @row-click="goTask">
          <el-table-column prop="id" label="任务ID" width="80" />
          <el-table-column prop="courseName" label="课程名称" min-width="120" />
          <el-table-column prop="publisherNickname" label="发布者" width="90" />
          <el-table-column prop="schoolName" label="学校" min-width="120" />
          <el-table-column prop="campusName" label="校区" width="100" />
          <el-table-column label="上课时间" width="150">
            <template #default="{ row }">{{ row.taskDate }} {{ row.startTime }}</template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <StatusTag :text="TASK_STATUS[row.status] || row.status" :tone="TASK_STATUS_TONE[row.status]" />
            </template>
          </el-table-column>
        </el-table>
      </section>
      <section class="ka-card">
        <div class="ka-card-head">
          <h3 class="ka-card-title">待处理举报及反馈</h3>
          <el-button link type="primary" @click="router.push('/reports')">查看全部</el-button>
        </div>
        <el-table :data="data?.pendingReports || []" size="small" @row-click="goReport">
          <el-table-column prop="id" label="编号" width="80" />
          <el-table-column prop="typeLabel" label="类型" width="90" />
          <el-table-column prop="targetLabel" label="对象" min-width="110" />
          <el-table-column label="时间" width="130">
            <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="80">
            <template #default="{ row }">
              <StatusTag :text="REPORT_STATUS[row.status] || row.status" :tone="REPORT_STATUS_TONE[row.status]" />
            </template>
          </el-table-column>
        </el-table>
      </section>
    </div>
    <OnlineUsersDialog v-model="onlineVisible" />
  </div>
</template>

<style scoped>
.welcome {
  background: linear-gradient(90deg, #e8f1ff 0%, #f7fbff 55%, #fff 100%);
  border: 1px solid #dbeafe;
  border-radius: 12px;
  padding: 22px 24px;
}
.welcome h2 {
  margin: 0;
  font-size: 20px;
}
.welcome p {
  margin: 8px 0 0;
  color: var(--ka-muted);
  font-size: 13px;
}
.kpis,
.charts,
.tables {
  display: grid;
  gap: 16px;
}
.kpis {
  grid-template-columns: repeat(4, 1fr);
}
.kpis.five {
  grid-template-columns: repeat(5, 1fr);
}
.kpi-hit {
  display: block;
  width: 100%;
  padding: 0;
  border: 0;
  background: transparent;
  cursor: pointer;
  text-align: left;
}
.charts {
  grid-template-columns: 1.4fr 1fr;
}
.tables {
  grid-template-columns: 1.4fr 1fr;
}
.pie-wrap {
  display: grid;
  grid-template-columns: 1fr 1fr;
  align-items: center;
}
.pie-chart {
  position: relative;
}
.pie-center {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22px;
  font-weight: 700;
  pointer-events: none;
}
.legend {
  list-style: none;
  margin: 0;
  padding: 0;
  font-size: 12px;
}
.legend li {
  display: grid;
  grid-template-columns: 8px 1fr auto 42px;
  gap: 8px;
  align-items: center;
  padding: 4px 0;
  color: #475569;
}
.legend i {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}
.legend em {
  color: #94a3b8;
  font-style: normal;
  text-align: right;
}
@media (max-width: 1280px) {
  .kpis,
  .kpis.five,
  .charts,
  .tables {
    grid-template-columns: 1fr;
  }
}
</style>
