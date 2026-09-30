export const TASK_STATUS: Record<string, string> = {
  WAITING: "等待接单",
  APPLYING: "申请中",
  MATCHED: "已匹配",
  CONFIRMED: "已确认",
  IN_PROGRESS: "进行中",
  COMPLETED: "已完成",
  CANCELLED: "已取消",
  EXPIRED: "已过期"
};

export const TASK_STATUS_TYPE: Record<string, "info" | "success" | "warning" | "danger" | ""> = {
  WAITING: "info",
  APPLYING: "",
  MATCHED: "warning",
  CONFIRMED: "warning",
  IN_PROGRESS: "success",
  COMPLETED: "success",
  CANCELLED: "danger",
  EXPIRED: "info"
};

export const TASK_STATUS_TONE: Record<string, "success" | "warning" | "danger" | "info" | "teal" | "purple" | "muted"> = {
  WAITING: "muted",
  APPLYING: "warning",
  MATCHED: "info",
  CONFIRMED: "purple",
  IN_PROGRESS: "teal",
  COMPLETED: "success",
  CANCELLED: "danger",
  EXPIRED: "muted"
};

export const TASK_STATUS_COLOR: Record<string, string> = {
  WAITING: "#94a3b8",
  APPLYING: "#f59e0b",
  MATCHED: "#3b82f6",
  CONFIRMED: "#8b5cf6",
  IN_PROGRESS: "#14b8a6",
  COMPLETED: "#22c55e",
  CANCELLED: "#ef4444",
  EXPIRED: "#cbd5e1"
};

export const USER_STATUS: Record<string, string> = {
  NORMAL: "正常",
  BANNED: "封禁"
};

export const REPORT_STATUS: Record<string, string> = {
  PENDING: "待处理",
  PROCESSING: "处理中",
  RESOLVED: "已处理",
  REJECTED: "已驳回"
};

export const REPORT_STATUS_TONE: Record<string, "success" | "warning" | "danger" | "info" | "muted"> = {
  PENDING: "warning",
  PROCESSING: "info",
  RESOLVED: "success",
  REJECTED: "muted"
};

export const REPORT_TARGET: Record<string, string> = {
  USER: "用户",
  TASK: "代课",
  MESSAGE: "消息",
  FEEDBACK: "反馈"
};

export const GENDER: Record<string, string> = {
  MALE: "男",
  FEMALE: "女"
};

export const ANNOUNCEMENT_STATUS: Record<string, string> = {
  DRAFT: "草稿",
  PUBLISHED: "已发布",
  OFFLINE: "已下线"
};

export const ANNOUNCEMENT_STATUS_TONE: Record<string, "success" | "warning" | "muted"> = {
  DRAFT: "warning",
  PUBLISHED: "success",
  OFFLINE: "muted"
};

export const APP_STATUS: Record<string, string> = {
  PENDING: "待处理",
  ACCEPTED: "已接受",
  REJECTED: "已拒绝",
  CANCELLED: "已撤回"
};

export const HANDLE_RESULT: Record<string, string> = {
  WARN: "警告",
  RESTRICT: "限制功能",
  BAN: "封禁账号",
  DELETE: "删除内容",
  REJECT: "驳回",
  REPLY: "已回复"
};

export const APPEAL_STATUS: Record<string, string> = {
  PENDING: "待复核",
  ACCEPTED: "已受理",
  REJECTED: "未获支持"
};

export function formatTime(value?: string | null) {
  if (!value) {
    return "—";
  }
  return value.replace("T", " ").slice(0, 16);
}

export function formatDate(value?: string | null) {
  if (!value) {
    return "—";
  }
  return value.slice(0, 10);
}

export function formatNumber(value?: number | null) {
  return (value ?? 0).toLocaleString("zh-CN");
}
