import { t, tf } from "@/utils/i18n";

export function pad(value: number) {
  return String(value).padStart(2, "0");
}

export function formatDate(ts: number) {
  const date = new Date(ts);
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

export function formatTime(value: number | string) {
  if (typeof value === "string") {
    return value.slice(0, 5);
  }
  const date = new Date(value);
  return `${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

export function parseDateTime(iso?: string | null) {
  if (!iso) {
    return "";
  }
  return iso.replace("T", " ").slice(0, 16);
}

/* ===========================================================================
 * 消息体系的时间文案（会话列表右端 / 通知分组标题 / 通知行内时间）
 * ---------------------------------------------------------------------------
 * 为什么要单独一套、而不复用上面的 formatChatTime / parseDateTime：
 *   · parseDateTime 是「原样截断」（2026-03-08 14:05），在会话行右端太宽，一屏挤掉昵称；
 *   · formatChatTime 是聊天页气泡之间的时间戳（今天也要带 HH:mm，昨天要带 HH:mm），
 *     而会话列表需要的正是"今天只给时间、昨天不给时间"的紧凑口径。
 * 铁律：**所有文案走 i18n**（t()），这里只负责"算"和"填占位符"，不出现中文字面量。
 * =========================================================================== */

/** 距今的"天差"：今天 0 / 昨天 1 / 前天 2 …（按自然日算，不按 24 小时） */
function dayDiffFromToday(date: Date, now: Date): number {
  const startToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
  const startThat = new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime();
  return Math.round((startToday - startThat) / 86400000);
}

/** Date.getDay()：0=周日 → 1..7（周一到周日），正好对上 i18n 的 msgWeekday1..7 */
function isoWeekday(date: Date): number {
  return date.getDay() === 0 ? 7 : date.getDay();
}

function monthDayText(date: Date, withYear: boolean): string {
  if (withYear) {
    return tf("msgYearMonthDay", { y: String(date.getFullYear()), mon: String(date.getMonth() + 1), d: String(date.getDate()) });
  }
  return tf("msgMonthDay", { mon: String(date.getMonth() + 1), d: String(date.getDate()) });
}

/**
 * 会话列表右端的时间（D 节规则）：
 *   今天 → `HH:mm`；昨天 → 昨天；本周（2~6 天）→ 周一…周日；今年更早 → 3月8日；跨年 → 2025年3月8日。
 * 解析不出来（老后端字段缺失 / 脏数据）返回空串 —— **绝不显示 "NaN"**。
 */
export function formatRelativeStamp(iso?: string | null): string {
  if (!iso) {
    return "";
  }
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return "";
  }
  const now = new Date();
  const diff = dayDiffFromToday(date, now);
  if (diff <= 0) {
    return `${pad(date.getHours())}:${pad(date.getMinutes())}`;
  }
  if (diff === 1) {
    return t("msgYesterday");
  }
  if (diff < 7) {
    // 动态 key：msgWeekday1..7。这里用模板字面量 + 一次收窄，
    // 保证与字典里的 key 集合对齐（少一个 key 就是运行时报错，不是静默空白）。
    const weekdayKey = `msgWeekday${isoWeekday(date)}` as "msgWeekday1";
    return t(weekdayKey);
  }
  return monthDayText(date, date.getFullYear() !== now.getFullYear());
}

/**
 * 通知列表的行内时间：**永远是 HH:mm**（跨天信息已经由日期分组标题表达了，
 * 行里再写一次日期是重复信息，只会把标题挤窄）。
 */
export function formatNoticeTime(iso?: string | null): string {
  if (!iso) {
    return "";
  }
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return "";
  }
  return `${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/**
 * 通知列表的日期分组桶（D 节）：只产**稳定的 bucket key**，不产文案 ——
 * 文案在页面里走 t("msgDayToday" / "msgDayYesterday" / "msgDayEarlier")。
 * 用 key 而不是文案的好处：语言切换不需要重算分组，而且 bucket 能当 v-for 的 :key。
 */
export function formatNoticeDay(iso?: string | null): "today" | "yesterday" | "earlier" {
  if (!iso) {
    return "earlier";
  }
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return "earlier";
  }
  const diff = dayDiffFromToday(date, new Date());
  if (diff <= 0) {
    return "today";
  }
  if (diff === 1) {
    return "yesterday";
  }
  return "earlier";
}

const CHAT_TIME_GAP_MS = 5 * 60 * 1000;

export function formatChatTime(iso?: string | null) {
  if (!iso) {
    return "";
  }
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return parseDateTime(iso);
  }
  const now = new Date();
  const hhmm = `${pad(date.getHours())}:${pad(date.getMinutes())}`;
  const startToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
  const startThat = new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime();
  const dayDiff = Math.round((startToday - startThat) / 86400000);
  if (dayDiff === 0) {
    return hhmm;
  }
  if (dayDiff === 1) {
    return `昨天 ${hhmm}`;
  }
  if (date.getFullYear() === now.getFullYear()) {
    return `${date.getMonth() + 1}月${date.getDate()}日 ${hhmm}`;
  }
  return `${date.getFullYear()}年${date.getMonth() + 1}月${date.getDate()}日 ${hhmm}`;
}

export function shouldShowChatTime(prevIso?: string | null, currIso?: string | null) {
  if (!currIso) {
    return false;
  }
  if (!prevIso) {
    return true;
  }
  const prev = new Date(prevIso).getTime();
  const curr = new Date(currIso).getTime();
  if (Number.isNaN(prev) || Number.isNaN(curr)) {
    return true;
  }
  return curr - prev >= CHAT_TIME_GAP_MS;
}

export function tomorrowAt(hour: number, minute: number) {
  const date = new Date();
  date.setDate(date.getDate() + 1);
  date.setHours(hour, minute, 0, 0);
  return date.getTime();
}

export function formatReward(value: number | string | null | undefined) {
  const amount = Number(value || 0);
  if (Number.isNaN(amount) || amount <= 0) {
    return "无偿";
  }
  return `¥${amount}`;
}

export function genderLabel(value?: string | null) {
  if (value === "MALE") {
    return "男";
  }
  if (value === "FEMALE") {
    return "女";
  }
  return "未设置";
}

export function genderRequirementLabel(value?: string | null) {
  if (value === "MALE") {
    return "仅限男生";
  }
  if (value === "FEMALE") {
    return "仅限女生";
  }
  return "不限";
}

export function starText(avg?: number | null) {
  if (avg == null || Number.isNaN(Number(avg))) {
    return "☆☆☆☆☆";
  }
  const n = Math.max(0, Math.min(5, Math.round(Number(avg))));
  return `${"★".repeat(n)}${"☆".repeat(5 - n)}`;
}

export function formatRating(avg?: number | null, count?: number | null) {
  if (avg == null || !count) {
    return "暂无评分";
  }
  return Number(avg).toFixed(1);
}

export function formatRoleRating(avg?: number | null, count?: number | null) {
  if (avg == null || !count) {
    return "☆ 暂无评分";
  }
  return `★ ${Number(avg).toFixed(1)} · ${count} 次`;
}

export function trustRoleLabel(role?: string | null) {
  if (role === "PUBLISHER") {
    return "发布可信度";
  }
  if (role === "APPLICANT") {
    return "代课可信度";
  }
  return "";
}

export function restrictionLabels(user?: {
  status?: string | null;
  forbidPublish?: number | null;
  forbidApply?: number | null;
  muted?: number | null;
} | null) {
  const tags: string[] = [];
  if (!user) {
    return tags;
  }
  if (user.status === "BANNED") {
    tags.push("已封禁");
  }
  if (user.forbidPublish === 1) {
    tags.push("禁止发布");
  }
  if (user.forbidApply === 1) {
    tags.push("禁止申请");
  }
  if (user.muted === 1) {
    tags.push("禁言");
  }
  return tags;
}

export function actionBlockReason(
  user: {
    status?: string | null;
    forbidPublish?: number | null;
    forbidApply?: number | null;
    muted?: number | null;
  } | null | undefined,
  action: "publish" | "apply" | "chat"
) {
  if (!user) {
    return "";
  }
  if (user.status === "BANNED") {
    return "账号已被封禁，无法使用该功能";
  }
  if (action === "publish" && user.forbidPublish === 1) {
    return "账号已被禁止发布";
  }
  if (action === "apply" && user.forbidApply === 1) {
    return "账号已被禁止申请";
  }
  if (action === "chat" && user.muted === 1) {
    return "账号已被禁言，无法发送私聊";
  }
  return "";
}

export function locationLockReason(task?: {
  status?: string | null;
  startAt?: string | null;
  acceptedApplicationId?: number | null;
} | null) {
  if (!task) {
    return "";
  }
  const matched = task.status === "MATCHED" || task.status === "CONFIRMED" || Boolean(task.acceptedApplicationId);
  if (!matched || !task.startAt) {
    return "";
  }
  const start = new Date(task.startAt.replace(" ", "T")).getTime();
  if (!Number.isNaN(start) && start - Date.now() <= 60 * 60 * 1000) {
    return "已有人接代课，开课前 1 小时内不能修改地点";
  }
  return "";
}

export function cancelLockReason(task?: {
  status?: string | null;
  startAt?: string | null;
  applicantConfirmed?: number | null;
} | null) {
  if (!task) {
    return "";
  }
  if (task.status !== "MATCHED" && task.status !== "CONFIRMED") {
    return "";
  }
  if (task.applicantConfirmed === 1) {
    return "已上传履约照片后不可取消，如未到场请私聊对方或举报";
  }
  if (task.startAt) {
    const start = new Date(task.startAt.replace(" ", "T")).getTime();
    if (!Number.isNaN(start) && start - Date.now() <= 3 * 60 * 60 * 1000) {
      return "开课前 3 小时内不可取消，请按时到场或私聊对方";
    }
  }
  return "";
}

export function fulfillPhotoHint(task?: {
  status?: string | null;
  startAt?: string | null;
  endAt?: string | null;
} | null) {
  if (!task?.startAt || !task.endAt) {
    return "";
  }
  const status = task.status || "";
  if (status !== "MATCHED" && status !== "CONFIRMED" && status !== "IN_PROGRESS") {
    return "";
  }
  const start = new Date(task.startAt.replace(" ", "T")).getTime();
  const end = new Date(task.endAt.replace(" ", "T")).getTime();
  if (Number.isNaN(start) || Number.isNaN(end)) {
    return "";
  }
  const openAt = start - 5 * 60 * 1000;
  const now = Date.now();
  if (now < openAt) {
    return "开课前 5 分钟至下课前才能上传照片";
  }
  if (now > end) {
    return "已下课，不能再上传照片";
  }
  return "";
}
