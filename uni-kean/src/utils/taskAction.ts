import type { NotificationItem } from "@/api/notification";
import type { PublisherBrief, TaskItem } from "@/api/task";
import { fulfillPhotoHint, restrictionLabels } from "@/utils/format";

export type TaskFocus = "applicants" | "photo" | "complete" | "review" | "chat" | "apply";

export type TaskStepKey = TaskFocus | "wait" | "none";

export interface TaskStep {
  key: TaskStepKey;
  label: string;
  hint?: string;
}

function pad(value: number) {
  return String(value).padStart(2, "0");
}

export function parseTaskMillis(raw?: string | null) {
  if (!raw) {
    return null;
  }
  const time = new Date(String(raw).replace(" ", "T")).getTime();
  return Number.isNaN(time) ? null : time;
}

function combineDateTime(date?: string | null, time?: string | null) {
  if (!date || !time) {
    return null;
  }
  return parseTaskMillis(`${date}T${time.length === 5 ? `${time}:00` : time}`);
}

export function taskStartAt(task: Pick<TaskItem, "startAt" | "taskDate" | "startTime">) {
  return parseTaskMillis(task.startAt) ?? combineDateTime(task.taskDate, task.startTime);
}

export function taskEndAt(task: Pick<TaskItem, "endAt" | "taskDate" | "endTime">) {
  return parseTaskMillis(task.endAt) ?? combineDateTime(task.taskDate, task.endTime);
}

export function classCountdown(task: TaskItem, now = Date.now()) {
  const start = taskStartAt(task);
  const end = taskEndAt(task);
  if (start == null) {
    return "";
  }
  if (now < start) {
    const remain = start - now;
    if (remain > 36 * 3600000) {
      return "";
    }
    const hours = Math.floor(remain / 3600000);
    const minutes = Math.floor((remain % 3600000) / 60000);
    if (hours >= 1) {
      return minutes > 0 ? `还有 ${hours} 小时 ${minutes} 分钟上课` : `还有 ${hours} 小时上课`;
    }
    if (minutes >= 1) {
      return `还有 ${minutes} 分钟上课`;
    }
    return "即将上课";
  }
  const live = task.status === "MATCHED" || task.status === "CONFIRMED" || task.status === "IN_PROGRESS";
  if (end != null && now < end) {
    const minutes = Math.max(1, Math.ceil((end - now) / 60000));
    return `上课中 · 还有 ${minutes} 分钟下课`;
  }
  if (live) {
    return "已下课";
  }
  return "";
}

export function isOngoingTask(task: TaskItem) {
  const status = task.status;
  if (status === "WAITING" || status === "APPLYING") {
    return Boolean(task.mine || task.myApplicationStatus === "PENDING");
  }
  if (status === "MATCHED" || status === "CONFIRMED" || status === "IN_PROGRESS") {
    return Boolean(task.mine || task.matchedApplicant);
  }
  return false;
}

export function nextTaskStep(task: TaskItem, now = Date.now()): TaskStep {
  const status = task.status;
  const party = Boolean(task.mine || task.matchedApplicant);
  const ended = (() => {
    const end = taskEndAt(task);
    return end != null && now >= end;
  })();
  const needPhoto = task.requirePhoto === 1;
  const uploaded = task.applicantConfirmed === 1;

  if (status === "COMPLETED" && party) {
    return { key: "review", label: "为对方打星", hint: "完成后互相打星" };
  }
  if (status === "IN_PROGRESS" && party && ended && (!needPhoto || uploaded)) {
    return { key: "complete", label: "下课确认完成" };
  }
  if (task.matchedApplicant && needPhoto && !uploaded && (status === "MATCHED" || status === "CONFIRMED" || status === "IN_PROGRESS")) {
    const windowHint = fulfillPhotoHint(task);
    if (!windowHint) {
      return { key: "photo", label: "上传现场照片" };
    }
    return { key: "chat", label: "私聊确认教室", hint: windowHint };
  }
  if ((status === "MATCHED" || status === "CONFIRMED") && party) {
    return { key: "chat", label: "私聊确认教室", hint: "先对好教室和见面点" };
  }
  if (status === "IN_PROGRESS" && party) {
    return { key: "chat", label: task.mine ? "私聊代课者" : "私聊发布者" };
  }
  if (task.mine && (status === "WAITING" || status === "APPLYING")) {
    if ((task.applyCount || 0) > 0) {
      return { key: "applicants", label: `处理申请（${task.applyCount}）` };
    }
    return { key: "wait", label: "等待申请" };
  }
  if (task.myApplicationStatus === "PENDING") {
    return { key: "wait", label: "等待对方接受" };
  }
  return { key: "none", label: "查看详情" };
}

export function compareOngoing(a: TaskItem, b: TaskItem) {
  return urgency(a) - urgency(b);
}

function urgency(task: TaskItem) {
  const status = task.status;
  const start = taskStartAt(task) ?? Number.MAX_SAFE_INTEGER;
  const hours = (start - Date.now()) / 3600000;
  if (status === "IN_PROGRESS") {
    return 0;
  }
  if (status === "MATCHED" || status === "CONFIRMED") {
    if (task.requirePhoto === 1 && task.matchedApplicant && task.applicantConfirmed !== 1 && !fulfillPhotoHint(task)) {
      return 4;
    }
    return 10 + Math.max(0, hours);
  }
  if (task.mine && status === "APPLYING" && (task.applyCount || 0) > 0) {
    return 30;
  }
  if (task.myApplicationStatus === "PENDING") {
    return 40;
  }
  return 50 + Math.max(0, hours);
}

export function taskDetailUrl(id: number, focus?: string | null) {
  if (focus === "review") {
    return `/pages/task/rate?id=${id}`;
  }
  if (focus && focus !== "wait" && focus !== "none") {
    return `/pages/task/detail?id=${id}&focus=${focus}`;
  }
  return `/pages/task/detail?id=${id}`;
}

export function noticeFocus(item: NotificationItem): TaskFocus | "" {
  const title = item.title || "";
  if (item.bizType === "REVIEW" || title.includes("已完成") || title.includes("自动完成")) {
    return "review";
  }
  if (title === "有人申请了你的代课") {
    return "applicants";
  }
  if (title.includes("上传") && title.includes("照片")) {
    return "photo";
  }
  if (title.includes("确认完成")) {
    return "complete";
  }
  if (title === "申请已被接受" || title.includes("即将上课") || title.includes("将于")) {
    return "chat";
  }
  if (item.type === "APPLICATION" || title.includes("申请")) {
    return "applicants";
  }
  return "";
}

export function userTrustLine(
  user?: {
    ratingAvg?: number | null;
    ratingCount?: number | null;
    completedCount?: number | null;
    cancelledCount?: number | null;
    reportedCount?: number | null;
    status?: string | null;
    userStatus?: string | null;
    forbidPublish?: number | null;
    forbidApply?: number | null;
    muted?: number | null;
  } | null,
  completedLabel = "完成"
) {
  if (!user) {
    return "";
  }
  const rating = user.ratingCount ? `${Number(user.ratingAvg).toFixed(1)} 分 · ${user.ratingCount} 次` : "暂无评分";
  const limits = restrictionLabels({
    status: user.userStatus || user.status,
    forbidPublish: user.forbidPublish,
    forbidApply: user.forbidApply,
    muted: user.muted
  });
  const base = `★ ${rating} · ${completedLabel} ${user.completedCount ?? 0} 次 · 取消 ${user.cancelledCount ?? 0} · 被举报 ${user.reportedCount ?? 0}`;
  return limits.length ? `${base} · ${limits.join(" / ")}` : base;
}

export function publisherTrustLine(user?: PublisherBrief | null) {
  return userTrustLine(user, "发布完成");
}

export function applicantTrustLine(user?: Parameters<typeof userTrustLine>[0]) {
  return userTrustLine(user, "代课完成");
}

export function formatClockLabel(task: Pick<TaskItem, "taskDate" | "startTime" | "endTime">) {
  const start = (task.startTime || "").slice(0, 5);
  const end = (task.endTime || "").slice(0, 5);
  return `${task.taskDate || ""} ${start}${start && end ? "-" : ""}${end}`.trim();
}

export function todayText(now = Date.now()) {
  const date = new Date(now);
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}
