<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from "vue";
import { ElMessage } from "element-plus";
import {
  cleanImQueues,
  getImAlerts,
  getImStats,
  type ImAlerts,
  type ImCleanResult,
  type ImCounters,
  type ImQueueItem,
  type ImStats
} from "@/api/im";
import StatusTag from "@/components/StatusTag.vue";
import { formatNumber } from "@/utils/dicts";

/** 队列长度的告警阈值：与后端 `kean.im.queue-warn-threshold` / `queue-error-threshold` 默认值一致。 */
const WARN_LENGTH = 1000;
const ERROR_LENGTH = 5000;
/** 周期内投递失败阈值：与后端 `kean.im.delivery-failure-threshold` 默认值一致。 */
const FAILURE_THRESHOLD = 20;
/** 自动刷新周期（毫秒）。 */
const REFRESH_INTERVAL_MS = 30000;
/** 危险操作确认串：必须与后端 `AdminImCleanRequest.CONFIRM_PHRASE` 逐字符一致。 */
const CONFIRM_PHRASE = "CLEAN_RESIDUE";

type StatusTone = "success" | "warning" | "muted";

const STATUS_META: Record<string, { label: string; tone: StatusTone }> = {
  UP: { label: "UP · 正常", tone: "success" },
  DEGRADED: { label: "DEGRADED · 降级", tone: "warning" },
  DISABLED: { label: "IM 未启用", tone: "muted" }
};

const KIND_LABEL: Record<string, string> = {
  "queue-backlog": "队列堆积",
  "delivery-failure": "投递失败",
  "queue-residue": "残留队列"
};

const COUNTER_META: { key: "attempts" | "pushed" | "failed" | "skipped"; label: string; hint: string }[] = [
  { key: "attempts", label: "尝试投递", hint: "调用镜像投递的消息条数（按消息计）" },
  { key: "pushed", label: "投递成功", hint: "成功写入队列的次数（按队列写入计，多端在线会重复计数）" },
  { key: "failed", label: "投递失败", hint: "写入队列抛异常的次数（投递失败的队列写入次数）" },
  { key: "skipped", label: "未启用跳过", hint: "IM 未启用导致的空操作（不包含接收方离线）" }
];

const statsLoading = ref(false);
const alertsLoading = ref(false);
const cleaning = ref(false);
const statsError = ref("");
const alertsError = ref("");
const stats = ref<ImStats | null>(null);
const alerts = ref<ImAlerts | null>(null);
const autoRefresh = ref(false);
const cleanVisible = ref(false);
const confirmText = ref("");
const cleanResult = ref<ImCleanResult | null>(null);
const now = ref(Date.now());
let timer: number | undefined;

const status = computed(() => stats.value?.status || "");
const statusMeta = computed(() => STATUS_META[status.value] || { label: status.value || "未知", tone: "muted" as StatusTone });
const redisOk = computed(() => stats.value?.redisAvailable === true);
const statusOnly = computed(() => status.value === "DISABLED" || stats.value?.enabled === false);
/** 计数器 / 队列表格是否可信：IM 启用且本轮读到了 Redis。 */
const queuesUsable = computed(() => stats.value !== null && redisOk.value && !statusOnly.value);
const queues = computed<ImQueueItem[]>(() => stats.value?.queues || []);
const alertList = computed(() => alerts.value?.list || []);
const alertsNote = computed(
  () => alerts.value?.note || "告警历史保存在 kean 进程内存中，进程重启或重新部署即清空；需要长期留存请查运维邮箱或 kean 日志。"
);
const residueKeys = computed(() => stats.value?.residueKeys || []);
/** 将被清理的键：以 `residueKeys` 为准，缺长度时从队列里补。 */
const residueRows = computed(() =>
  residueKeys.value.map((key) => {
    const matched = queues.value.find((item) => item.key === key);
    return { key, length: matched ? matched.length : null };
  })
);
const residueTotal = computed(() => residueRows.value.reduce((sum, row) => sum + (row.length || 0), 0));
const confirmOk = computed(() => confirmText.value.trim() === CONFIRM_PHRASE);
const canRefresh = computed(() => !statsLoading.value && !alertsLoading.value);
const dataState = computed(() => {
  if (statsError.value) {
    return { tone: "error" as const, title: "数据获取失败", detail: statsError.value };
  }
  if (!stats.value) {
    return { tone: "info" as const, title: "正在加载 IM 监控数据…", detail: "" };
  }
  if (statusOnly.value) {
    return {
      tone: "info" as const,
      title: "IM 镜像投递未启用",
      detail: stats.value.message || "无需关注队列堆积与残留队列；IM_JWT_SECRET 配置完成并重启后此处会显示巡检结果。"
    };
  }
  if (!redisOk.value) {
    return {
      tone: "warning" as const,
      title: "Redis 数据暂不可用",
      detail: stats.value.message || "队列长度与残留队列判定暂不可信；计数器与周期内失败数来自进程内存，仍然准确。"
    };
  }
  return null;
});

/** 时间字段兼容 ISO-8601 字符串与秒/毫秒时间戳。 */
function formatImTime(value: string | number | null | undefined): string {
  if (value === null || value === undefined || value === "") {
    return "—";
  }
  let timestamp: number;
  if (typeof value === "number") {
    timestamp = value;
  } else {
    const raw = value.trim();
    if (/^\d+$/.test(raw)) {
      timestamp = Number(raw);
    } else {
      const parsed = new Date(raw).getTime();
      return Number.isNaN(parsed) ? "—" : stamp(parsed);
    }
  }
  if (!Number.isFinite(timestamp) || timestamp <= 0) {
    return "—";
  }
  // 小于 1e12 视为秒级时间戳（毫秒时间戳在 2001 年后就已经超过这个量级）
  if (timestamp < 1e12) {
    timestamp *= 1000;
  }
  return stamp(timestamp);
}

function stamp(timestamp: number): string {
  const date = new Date(timestamp);
  const pad = (input: number) => String(input).padStart(2, "0");
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ` +
    `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
  );
}

/** 活跃时长文案。 */
function agoText(value: string | null): string {
  if (!value) {
    return "";
  }
  const seconds = Math.max(0, Math.floor((now.value - new Date(value).getTime()) / 1000));
  if (Number.isNaN(seconds)) {
    return "";
  }
  if (seconds < 60) {
    return `${seconds} 秒前`;
  }
  if (seconds < 3600) {
    return `${Math.floor(seconds / 60)} 分钟前`;
  }
  return `${Math.floor(seconds / 3600)} 小时前`;
}

function counterSnapshot(): ImCounters | null {
  const current = stats.value;
  return current ? current.counters : null;
}

function counterRaw(key: "attempts" | "pushed" | "failed" | "skipped"): number {
  const snapshot = counterSnapshot();
  return snapshot ? Number(snapshot[key] || 0) : 0;
}

function counterHintClass(key: "attempts" | "pushed" | "failed" | "skipped"): string {
  return key === "failed" && counterRaw(key) > 0 ? "danger" : "";
}

function lengthTagType(length: number): "danger" | "warning" | "info" {
  if (length > ERROR_LENGTH) {
    return "danger";
  }
  if (length > WARN_LENGTH) {
    return "warning";
  }
  return "info";
}

function queueRowClass({ row }: { row: ImQueueItem }): string {
  return row.residue ? "residue-row" : "";
}

/** 统一把 `unknown` 收窄成可展示的错误文案（错误提示本身不抛出，避免影响其它区块）。 */
function readError(error: unknown, fallback: string): string {
  if (error instanceof Error && error.message) {
    return error.message;
  }
  return fallback;
}

async function loadStats() {
  statsLoading.value = true;
  try {
    stats.value = await getImStats();
    statsError.value = "";
  } catch (error) {
    statsError.value = readError(error, "IM 看板加载失败");
  } finally {
    statsLoading.value = false;
    now.value = Date.now();
  }
}

async function loadAlerts() {
  alertsLoading.value = true;
  try {
    alerts.value = await getImAlerts(50);
    alertsError.value = "";
  } catch (error) {
    alertsError.value = readError(error, "告警历史加载失败");
  } finally {
    alertsLoading.value = false;
  }
}

/**
 * 两个只读请求彼此独立、各自 catch：任何一个失败都只影响自己的区块，
 * 不会打断另一个区块的渲染，也不会让整页空白。
 */
function refreshAll() {
  loadStats().catch(() => undefined);
  loadAlerts().catch(() => undefined);
}

function resetTimer() {
  if (timer !== undefined) {
    window.clearInterval(timer);
    timer = undefined;
  }
  if (autoRefresh.value) {
    timer = window.setInterval(refreshAll, REFRESH_INTERVAL_MS);
  }
}

function onAutoRefreshChange() {
  resetTimer();
  if (autoRefresh.value) {
    ElMessage.success(`已开启自动刷新（每 ${REFRESH_INTERVAL_MS / 1000} 秒）`);
    refreshAll();
  } else {
    ElMessage.info("已关闭自动刷新");
  }
}

function openCleanDialog() {
  cleanResult.value = null;
  confirmText.value = "";
  cleanVisible.value = true;
}

async function submitClean() {
  if (!confirmOk.value || cleaning.value) {
    return;
  }
  cleaning.value = true;
  cleanResult.value = null;
  try {
    cleanResult.value = await cleanImQueues({ confirm: CONFIRM_PHRASE });
    const result = cleanResult.value;
    if (result.deleted > 0) {
      ElMessage.warning(`已删除 ${result.deleted} 个残留队列（被拒绝 ${result.skipped} 个）`);
    } else {
      ElMessage.info(`未删除任何队列（被拒绝 ${result.skipped} 个，原因见逐键结果）`);
    }
    confirmText.value = "";
    await loadStats();
  } catch {
    // 错误提示已由 request.ts 的统一拦截器给出；清理结果保持为空，弹窗内的「确认清理」可重试
  } finally {
    cleaning.value = false;
  }
}

onMounted(() => {
  refreshAll();
});
onUnmounted(() => {
  if (timer !== undefined) {
    window.clearInterval(timer);
    timer = undefined;
  }
});
</script>

<template>
  <div class="ka-page">
    <section class="ka-card">
      <div class="status-head">
        <div class="status-left">
          <StatusTag :text="statusMeta.label" :tone="statusMeta.tone" />
          <span class="status-flag">
            <el-tag :type="redisOk ? 'success' : 'info'" size="small" effect="plain">Redis {{ redisOk ? "可用" : "不可用" }}</el-tag>
            <el-tag :type="stats?.enabled ? 'success' : 'info'" size="small" effect="plain">镜像投递 {{ stats?.enabled ? "已启用" : "已关闭" }}</el-tag>
          </span>
        </div>
        <div class="status-actions">
          <el-button :loading="statsLoading || alertsLoading" :disabled="!canRefresh" @click="refreshAll">手动刷新</el-button>
          <el-switch v-model="autoRefresh" active-text="自动刷新（30 秒）" @change="onAutoRefreshChange" />
        </div>
      </div>

      <p class="status-message">{{ stats?.message || "加载中…" }}</p>

      <div class="time-row">
        <span>
          最近巡检：{{ formatImTime(stats?.lastInspectionAt) }}
          <em v-if="agoText(stats?.lastInspectionAt ?? null)">（{{ agoText(stats?.lastInspectionAt ?? null) }}）</em>
          <b v-if="!stats?.lastInspectionAt">IM 未启用或尚未完成第一次巡检</b>
        </span>
        <span>查询时间：{{ formatImTime(stats?.queriedAt) }}</span>
      </div>
      <p class="tip">巡检默认每 60 秒一轮；「最近巡检」落后「查询时间」最多约 1 分钟属于正常现象。</p>
    </section>

    <el-alert
      v-if="dataState"
      :type="dataState.tone"
      :title="dataState.title"
      :description="dataState.detail"
      :closable="false"
      show-icon
    />

    <section class="ka-card">
      <div class="ka-card-head">
        <h3 class="ka-card-title">投递计数器</h3>
        <span class="head-note">累计值（进程重启归零）</span>
      </div>
      <div class="counters">
        <div v-for="item in COUNTER_META" :key="item.key" class="counter" :class="{ danger: counterHintClass(item.key) }">
          <div class="counter-label">{{ item.label }}</div>
          <el-statistic :value="counterRaw(item.key)" />
          <p class="counter-hint">{{ item.hint }}</p>
        </div>
      </div>
      <p class="tip">
        口径不同别混着看：<code>attempts</code> / <code>skipped</code> 按消息计，<code>pushed</code> / <code>failed</code>
        按队列写入计（多端在线会写多个 serverId 队列），因此 <code>attempts ≠ pushed + failed</code>；接收方离线不计入任何一项。
      </p>
    </section>

    <section class="ka-card">
      <div class="ka-card-head">
        <h3 class="ka-card-title">镜像投递队列</h3>
        <span class="head-note">当前 {{ stats?.queueCount ?? "—" }} 个队列，积压 {{ formatNumber(stats?.queueTotal) }} 条</span>
      </div>

      <div class="queue-meta">
        <span>残留队列：<b :class="{ danger: stats?.residueFound }">{{ stats?.residueFound ? "发现" : "未发现" }}</b></span>
        <span><code>im:max_server_id</code>：{{ stats?.maxServerId ?? "—" }}</span>
        <span>
          最近一个周期失败：
          <b :class="{ danger: Number(stats?.failedInLastCycle || 0) > FAILURE_THRESHOLD }">{{ formatNumber(stats?.failedInLastCycle) }}</b>
          <em>（阈值 &gt; {{ FAILURE_THRESHOLD }} 次触发告警）</em>
        </span>
      </div>

      <el-alert
        v-if="stats?.residueFound"
        type="warning"
        :closable="false"
        show-icon
        title="发现疑似残留队列（旧 serverId，无人消费）"
        :description="`将于清理操作中处理的键：${residueKeys.join('、') || '—'}`"
      />

      <p v-if="stats && !queuesUsable" class="notice-text">
        队列数据当前不可用，下表为空是正常现象：
        {{ statusOnly ? "IM 镜像投递未启用" : "本轮巡检未读到 Redis" }}；下方「清理残留队列」按钮已按要求禁用。
      </p>

      <el-table
        :data="queues"
        v-loading="statsLoading"
        size="small"
        :row-class-name="queueRowClass"
        empty-text="无队列积压"
      >
        <el-table-column prop="key" label="队列键名" min-width="220">
          <template #default="{ row }"><code>{{ row.key }}</code></template>
        </el-table-column>
        <el-table-column label="长度" width="130">
          <template #default="{ row }">
            <el-tag :type="lengthTagType(row.length)" size="small" effect="light">{{ formatNumber(row.length) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="serverId" width="110">
          <template #default="{ row }">{{ row.serverId ?? "—" }}</template>
        </el-table-column>
        <el-table-column label="残留" width="90">
          <template #default="{ row }">
            <el-tag :type="row.residue ? 'danger' : 'success'" size="small" effect="plain">{{ row.residue ? "是" : "否" }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="残留原因" min-width="240">
          <template #default="{ row }">
            <span v-if="row.residue" class="reason">{{ row.residueReason || "巡检判定为残留" }}</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
      </el-table>

      <p class="tip">
        长度上色阈值：&gt; {{ WARN_LENGTH }} 橙色（WARN，<code>kean.im.queue-warn-threshold</code>）、&gt;
        {{ ERROR_LENGTH }} 红色（ERROR + 邮件告警，<code>kean.im.queue-error-threshold</code>）；残留行整行高亮。
        此表数据来自最近一次巡检快照，不是实时 SCAN。
      </p>

      <div class="clean-bar">
        <el-tooltip
          :disabled="Boolean(stats?.residueFound)"
          content="无残留队列，无需清理"
          placement="top"
        >
          <span class="clean-hit">
            <el-button type="danger" plain :disabled="!stats?.residueFound" @click="openCleanDialog">
              清理残留队列（不可逆）
            </el-button>
          </span>
        </el-tooltip>
        <span class="tip inline">
          {{ stats?.residueFound ? `将处理 ${residueKeys.length} 个键，需手动输入确认串` : "无残留队列" }}
        </span>
      </div>
    </section>

    <section class="ka-card">
      <div class="ka-card-head">
        <h3 class="ka-card-title">IM 告警历史</h3>
        <span class="head-note">
          本轮返回 {{ alerts?.size ?? "—" }} / {{ alerts?.capacity ?? "—" }} 条（请求上限 {{ alerts?.limit ?? 50 }}）
        </span>
      </div>

      <el-alert type="info" :closable="false" show-icon title="告警历史的留存限制" :description="alertsNote" />
      <p v-if="alertsError" class="error-text">告警历史加载失败：{{ alertsError }}（其它区块不受影响）</p>

      <el-table
        :data="alertList"
        v-loading="alertsLoading"
        size="small"
        empty-text="暂无告警（或 kean 进程刚重启，内存历史已清空）"
      >
        <el-table-column label="时间" width="160">
          <template #default="{ row }">{{ formatImTime(row.time) }}</template>
        </el-table-column>
        <el-table-column label="类别" width="100">
          <template #default="{ row }">{{ KIND_LABEL[row.kind] || row.kind }}</template>
        </el-table-column>
        <el-table-column label="级别" width="90">
          <template #default="{ row }">
            <el-tag :type="row.level === 'ERROR' ? 'danger' : 'warning'" size="small">{{ row.level }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="summary" label="问题" min-width="200" show-overflow-tooltip />
        <el-table-column prop="currentValue" label="当前值" min-width="200" show-overflow-tooltip />
        <el-table-column prop="threshold" label="阈值" min-width="180" show-overflow-tooltip />
        <el-table-column label="相关键" min-width="180">
          <template #default="{ row }">
            <div v-for="key in row.keys" :key="key" class="key-line"><code>{{ key }}</code></div>
            <span v-if="!row.keys || !row.keys.length" class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="邮件" width="220">
          <template #default="{ row }">
            <el-tag :type="row.mailSent ? 'success' : 'info'" size="small" effect="plain">
              {{ row.mailSent ? "已发邮件" : "未发（冷却中）" }}
            </el-tag>
            <div v-if="!row.mailSent" class="mail-note">{{ row.mailNote || "未发送（原因未提供）" }}</div>
          </template>
        </el-table-column>
      </el-table>
      <p class="tip">同一类告警 30 分钟内只发一封邮件（冷却期内的记录仍会出现在上表，只是未发信）；本表不做任何删除操作，清理请走上方队列区。</p>
    </section>

    <el-dialog v-model="cleanVisible" title="清理残留队列（不可逆）" width="720px">
      <el-alert
        type="error"
        :closable="false"
        show-icon
        title="此操作会永久丢弃队列里尚未推送的消息"
        description="后端只删除「巡检判定为残留」且「serverId 不在活跃集合中」的键；被拒绝也是正常结果，会逐键说明原因。"
      />

      <h4 class="dialog-title">将被处理的键（{{ residueRows.length }} 个，合计 {{ formatNumber(residueTotal) }} 条消息）</h4>
      <el-table :data="residueRows" size="small" empty-text="没有可清理的残留队列">
        <el-table-column label="键名" min-width="240">
          <template #default="{ row }"><code>{{ row.key }}</code></template>
        </el-table-column>
        <el-table-column label="当前长度" width="140">
          <template #default="{ row }">{{ row.length === null ? "未知（非本轮巡检队列）" : formatNumber(row.length) }}</template>
        </el-table-column>
      </el-table>
      <p class="tip">候选键取自辅助接口返回的 <code>residueKeys</code>；后端删除时会重新读取长度并二次校验，实际结果以下方逐键结果为准。</p>

      <h4 class="dialog-title">请输入确认串 <code>{{ CONFIRM_PHRASE }}</code> 以启用删除</h4>
      <el-input v-model="confirmText" :placeholder="CONFIRM_PHRASE" clearable />

      <template v-if="cleanResult">
        <h4 class="dialog-title">
          清理结果：请求 {{ cleanResult.requested }} 个 · 已删除 {{ cleanResult.deleted }} 个 · 未删除 {{ cleanResult.skipped }} 个
        </h4>
        <p class="tip">{{ cleanResult.note }}（操作时间：{{ formatImTime(cleanResult.cleanedAt) }}）</p>
        <el-table :data="cleanResult.results" size="small" empty-text="没有可处理的键（可能尚无巡检结果）">
          <el-table-column label="键名" min-width="220">
            <template #default="{ row }"><code>{{ row.key }}</code></template>
          </el-table-column>
          <el-table-column label="删除前长度" width="120">
            <template #default="{ row }">{{ formatNumber(row.length) }}</template>
          </el-table-column>
          <el-table-column label="结果" width="110">
            <template #default="{ row }">
              <el-tag :type="row.deleted ? 'danger' : 'warning'" size="small">{{ row.deleted ? "已删除" : "已拒绝" }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="reason" label="原因" min-width="260" />
        </el-table>
      </template>

      <template #footer>
        <el-button @click="cleanVisible = false">{{ cleanResult ? "关闭" : "取消" }}</el-button>
        <el-button type="danger" :loading="cleaning" :disabled="!confirmOk" @click="submitClean">
          {{ cleanResult ? "再次清理" : "确认清理" }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.status-head {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
}
.status-left {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
}
.status-flag {
  display: inline-flex;
  gap: 6px;
  align-items: center;
}
.status-actions {
  display: flex;
  gap: 12px;
  align-items: center;
}
.status-message {
  margin: 12px 0 6px;
  font-size: 13px;
  color: var(--ka-text);
  line-height: 1.6;
}
.time-row {
  display: flex;
  flex-wrap: wrap;
  gap: 18px;
  font-size: 12px;
  color: var(--ka-muted);
}
.time-row em {
  font-style: normal;
}
.time-row b {
  color: var(--ka-warning);
  font-weight: 500;
}
.tip {
  margin: 10px 0 0;
  font-size: 12px;
  color: var(--ka-muted);
  line-height: 1.7;
}
.tip.inline {
  margin: 0;
}
.tip code,
.reason code,
.key-line code,
.el-table code {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  background: #f1f5f9;
  border-radius: 4px;
  padding: 1px 4px;
}
.head-note {
  font-size: 12px;
  color: var(--ka-muted);
}
.counters {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
}
.counter {
  background: #f8fafc;
  border-radius: 12px;
  padding: 14px 16px;
}
.counter.danger {
  background: #fef2f2;
}
.counter-label {
  font-size: 13px;
  color: var(--ka-muted);
}
.counter :deep(.el-statistic__content) {
  font-size: 26px;
  font-weight: 700;
  line-height: 1.15;
  color: var(--ka-text);
}
.counter.danger :deep(.el-statistic__content) {
  color: var(--ka-danger);
}
.counter-hint {
  display: block;
  margin: 8px 0 0;
  font-size: 12px;
  font-weight: 400;
  line-height: 1.6;
  color: var(--ka-muted);
  white-space: normal;
}
.counter.danger .counter-hint {
  color: #b91c1c;
}
.queue-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 18px;
  margin-bottom: 12px;
  font-size: 13px;
  color: var(--ka-muted);
}
.queue-meta b {
  color: var(--ka-text);
}
.queue-meta b.danger {
  color: var(--ka-danger);
}
.queue-meta em {
  font-style: normal;
}
.reason {
  color: var(--ka-danger);
  font-size: 12px;
}
.muted {
  color: var(--ka-muted);
}
.error-text {
  margin: 10px 0 0;
  font-size: 12px;
  color: var(--ka-danger);
}
.notice-text {
  margin: 10px 0 0;
  font-size: 12px;
  color: var(--ka-warning);
  line-height: 1.7;
}
.mail-note {
  margin-top: 4px;
  font-size: 12px;
  color: var(--ka-muted);
  line-height: 1.5;
}
.key-line + .key-line {
  margin-top: 2px;
}
.clean-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
  margin-top: 14px;
}
.clean-hit {
  display: inline-flex;
}
.dialog-title {
  margin: 18px 0 8px;
  font-size: 13px;
  font-weight: 600;
}
.dialog-title code {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  background: #fef2f2;
  color: var(--ka-danger);
  border-radius: 4px;
  padding: 1px 5px;
}
.el-alert + .el-table,
.el-alert + .error-text {
  margin-top: 12px;
}
:deep(.el-table .residue-row) {
  --el-table-tr-bg-color: #fef2f2;
}
:deep(.el-table .residue-row td) {
  background: #fef2f2 !important;
}
@media (max-width: 1280px) {
  .counters {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
