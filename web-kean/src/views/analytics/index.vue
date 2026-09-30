<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import type { EChartsCoreOption } from "echarts/core";
import { getStats, type StatsData } from "@/api/dashboard";
import KaChart from "@/components/KaChart.vue";
import { TASK_STATUS, TASK_STATUS_COLOR } from "@/utils/dicts";

const loading = ref(false);
const data = ref<StatsData | null>(null);
const range = ref<[string, string] | null>(null);

const userOption = computed<EChartsCoreOption>(() => ({
  color: ["#3b82f6"],
  tooltip: { trigger: "axis" },
  grid: { left: 36, right: 12, top: 24, bottom: 24 },
  xAxis: {
    type: "category",
    data: (data.value?.userGrowth || []).map((item) => item.date.slice(5)),
    axisLabel: { color: "#94a3b8" },
    axisLine: { lineStyle: { color: "#e2e8f0" } }
  },
  yAxis: { type: "value", splitLine: { lineStyle: { color: "#f1f5f9" } }, axisLabel: { color: "#94a3b8" } },
  series: [
    {
      type: "line",
      smooth: true,
      areaStyle: { color: "rgba(59,130,246,.12)" },
      data: (data.value?.userGrowth || []).map((item) => item.count)
    }
  ]
}));

const taskOption = computed<EChartsCoreOption>(() => ({
  color: ["#3b82f6", "#14b8a6"],
  tooltip: { trigger: "axis" },
  legend: { data: ["发布", "完成"], right: 0, top: 0, icon: "circle", itemWidth: 8 },
  grid: { left: 36, right: 12, top: 32, bottom: 24 },
  xAxis: {
    type: "category",
    data: (data.value?.taskTrend || []).map((item) => item.date.slice(5)),
    axisLabel: { color: "#94a3b8" },
    axisLine: { lineStyle: { color: "#e2e8f0" } }
  },
  yAxis: { type: "value", splitLine: { lineStyle: { color: "#f1f5f9" } }, axisLabel: { color: "#94a3b8" } },
  series: [
    { name: "发布", type: "line", smooth: true, data: (data.value?.taskTrend || []).map((item) => item.published) },
    { name: "完成", type: "line", smooth: true, data: (data.value?.taskTrend || []).map((item) => item.completed) }
  ]
}));

const statusOption = computed<EChartsCoreOption>(() => ({
  tooltip: { trigger: "item" },
  series: [
    {
      type: "pie",
      radius: ["58%", "78%"],
      label: { show: false },
      data: visibleStatus.value.map((item) => ({
        name: TASK_STATUS[item.status] || item.status,
        value: item.count,
        itemStyle: { color: TASK_STATUS_COLOR[item.status] || "#94a3b8" }
      }))
    }
  ]
}));

const reportOption = computed<EChartsCoreOption>(() => ({
  tooltip: { trigger: "item" },
  color: ["#f59e0b", "#3b82f6", "#22c55e", "#ef4444", "#8b5cf6", "#14b8a6"],
  series: [
    {
      type: "pie",
      radius: ["58%", "78%"],
      label: { show: false },
      data: visibleReports.value.map((item) => ({ name: item.label, value: item.count }))
    }
  ]
}));

const statusTotal = computed(() => (data.value?.taskStatus || []).reduce((sum, item) => sum + item.count, 0));
const reportTotal = computed(() => (data.value?.reportTypes || []).reduce((sum, item) => sum + item.count, 0));
const visibleStatus = computed(() => (data.value?.taskStatus || []).filter((item) => item.count > 0));
const visibleReports = computed(() => (data.value?.reportTypes || []).filter((item) => item.count > 0));

async function load() {
  loading.value = true;
  try {
    data.value = await getStats(range.value?.[0], range.value?.[1]);
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>

<template>
  <div v-loading="loading" class="ka-page">
    <div class="ka-toolbar" style="justify-content: flex-end">
      <el-date-picker
        v-model="range"
        type="daterange"
        value-format="YYYY-MM-DD"
        start-placeholder="开始日期"
        end-placeholder="结束日期"
        style="width: 280px"
        @change="load"
      />
    </div>
    <div class="grid">
      <section class="ka-card">
        <div class="ka-card-head"><h3 class="ka-card-title">用户注册</h3></div>
        <KaChart :option="userOption" height="220px" />
      </section>
      <section class="ka-card">
        <div class="ka-card-head"><h3 class="ka-card-title">发布任务</h3></div>
        <KaChart :option="taskOption" height="220px" />
      </section>
      <section class="ka-card">
        <div class="ka-card-head"><h3 class="ka-card-title">学校任务排行</h3></div>
        <el-table :data="data?.schoolRanking || []" size="small">
          <el-table-column prop="schoolName" label="学校" />
          <el-table-column prop="taskCount" label="发布数" width="90" />
        </el-table>
      </section>
      <section class="ka-card pie">
        <div class="ka-card-head"><h3 class="ka-card-title">任务状态分布</h3></div>
        <div class="pie-body">
          <KaChart :option="statusOption" height="180px" />
          <ul>
            <li v-for="item in visibleStatus" :key="item.status">
              <span>{{ TASK_STATUS[item.status] || item.status }}</span>
              <b>{{ item.count }}</b>
              <em>{{ statusTotal ? ((item.count / statusTotal) * 100).toFixed(1) : 0 }}%</em>
            </li>
          </ul>
        </div>
      </section>
      <section class="ka-card pie">
        <div class="ka-card-head"><h3 class="ka-card-title">举报类型分布</h3></div>
        <div class="pie-body">
          <KaChart :option="reportOption" height="180px" />
          <ul>
            <li v-for="item in visibleReports" :key="item.type">
              <span>{{ item.label }}</span>
              <b>{{ item.count }}</b>
              <em>{{ reportTotal ? ((item.count / reportTotal) * 100).toFixed(1) : 0 }}%</em>
            </li>
          </ul>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.grid {
  display: grid;
  grid-template-columns: 1.2fr 1.2fr 1fr;
  gap: 16px;
}
.pie {
  grid-column: span 1;
}
.pie-body {
  display: grid;
  grid-template-columns: 140px 1fr;
  align-items: center;
}
ul {
  list-style: none;
  margin: 0;
  padding: 0;
  font-size: 12px;
}
li {
  display: grid;
  grid-template-columns: 1fr auto 42px;
  gap: 8px;
  padding: 3px 0;
  color: #475569;
}
em {
  font-style: normal;
  color: #94a3b8;
  text-align: right;
}
@media (max-width: 1280px) {
  .grid {
    grid-template-columns: 1fr;
  }
}
</style>
