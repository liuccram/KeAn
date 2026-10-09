import { request } from "@/utils/request";

/** IM 总体状态：与 `/health/ready` 的 `im` 字段完全同源。 */
export type ImStatus = "UP" | "DEGRADED" | "DISABLED";

/** 投递计数累计快照（进程重启归零；`attempts` 按消息计，`pushed` / `failed` 按队列写入计）。 */
export interface ImCounters {
  attempts: number;
  pushed: number;
  failed: number;
  skipped: number;
}

/** 单个镜像投递队列。 */
export interface ImQueueItem {
  key: string;
  /** `LLEN`。 */
  length: number;
  /** 键尾的 serverId（解析不出为 `null`）。 */
  serverId: number | null;
  /** 是否被巡检判定为残留（旧 serverId，无人消费）。 */
  residue: boolean;
  /** 残留原因；未残留为 `null`。 */
  residueReason: string | null;
}

/** `GET /api/admin/im/stats` 的 `data`（最近一次巡检的只读快照）。 */
export interface ImStats {
  status: ImStatus;
  enabled: boolean;
  /** `false` 时队列与残留数据不可信，`message` 给出说明；接口仍是 200。 */
  redisAvailable: boolean;
  message: string;
  counters: ImCounters;
  queues: ImQueueItem[];
  queueTotal: number;
  queueCount: number;
  residueFound: boolean;
  residueKeys: string[];
  maxServerId: number | null;
  failedInLastCycle: number;
  /** ISO-8601 字符串；未巡检 / IM 未启用为 `null`。 */
  lastInspectionAt: string | null;
  /** ISO-8601 字符串。 */
  queriedAt: string | null;
}

/** 一条告警记录（内存态）。 */
export interface ImAlertItem {
  time: string | null;
  /** `queue-backlog` / `delivery-failure` / `queue-residue`。 */
  kind: string;
  /** `WARN` / `ERROR`。 */
  level: string;
  summary: string;
  currentValue: string;
  threshold: string;
  keys: string[];
  mailSent: boolean;
  /** 未发邮件的原因；已发出为 `null`。 */
  mailNote: string | null;
}

/** `GET /api/admin/im/alerts` 的 `data`（⚠️ 进程重启即清空）。 */
export interface ImAlerts {
  /** 新的在前。 */
  list: ImAlertItem[];
  size: number;
  limit: number;
  capacity: number;
  /** 内存态限制说明，直接展示给管理员。 */
  note: string;
}

/** 单键清理结果（`deleted=false` 是**正常结果**：键没通过安全校验，不是异常）。 */
export interface ImCleanResultItem {
  key: string;
  serverId: number | null;
  /** 删除前重新读取的长度。 */
  length: number;
  deleted: boolean;
  reason: string;
}

/** `POST /api/admin/im/queues/clean` 的 `data`。 */
export interface ImCleanResult {
  requested: number;
  deleted: number;
  skipped: number;
  results: ImCleanResultItem[];
  cleanedAt: string | null;
  note: string;
}

/** 看板（只读）：状态 / 计数器 / 队列长度 / 残留队列。 */
export function getImStats() {
  return request<ImStats>("/api/admin/im/stats");
}

/** 告警历史（只读，内存态，默认最多 50 条）。 */
export function getImAlerts(limit = 50) {
  return request<ImAlerts>("/api/admin/im/alerts", { method: "GET", data: { limit } });
}

/**
 * 清理残留队列（**写操作，不可逆**）。
 *
 * 必须带确认串 `CLEAN_RESIDUE`；`serverIds` 可选（一次最多 64 个），不传则清理全部
 * 巡检判定出的残留队列。返回逐键结果，被拒绝也是正常结果。
 */
export function cleanImQueues(payload: { confirm: string; serverIds?: number[] }) {
  return request<ImCleanResult>("/api/admin/im/queues/clean", { method: "POST", data: payload });
}
