<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from "vue";
import * as echarts from "echarts/core";
import { BarChart, LineChart, PieChart } from "echarts/charts";
import {
  GridComponent,
  LegendComponent,
  TooltipComponent
} from "echarts/components";
import { CanvasRenderer } from "echarts/renderers";
import type { EChartsCoreOption } from "echarts/core";

echarts.use([LineChart, PieChart, BarChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer]);

const props = defineProps<{
  option: EChartsCoreOption;
  height?: string;
}>();

const el = ref<HTMLDivElement>();
let chart: echarts.ECharts | null = null;

function render() {
  if (!el.value) return;
  if (!chart) {
    chart = echarts.init(el.value);
  }
  chart.setOption(props.option, true);
}

onMounted(() => {
  render();
  window.addEventListener("resize", resize);
});

onBeforeUnmount(() => {
  window.removeEventListener("resize", resize);
  chart?.dispose();
  chart = null;
});

watch(() => props.option, render, { deep: true });

function resize() {
  chart?.resize();
}
</script>

<template>
  <div ref="el" class="chart" :style="{ height: height || '260px' }" />
</template>

<style scoped>
.chart {
  width: 100%;
}
</style>
