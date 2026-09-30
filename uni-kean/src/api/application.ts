import { request } from "@/utils/request";
import type { TaskItem } from "@/api/task";

export interface ApplicationItem {
  id: number;
  taskId: number;
  applicantId: number;
  nickname?: string | null;
  message?: string | null;
  status: string;
  createdAt: string;
  completedCount?: number | null;
  ratingAvg?: number | null;
  ratingCount?: number | null;
  cancelledCount?: number | null;
  reportedCount?: number | null;
  userStatus?: string | null;
  forbidPublish?: number | null;
  forbidApply?: number | null;
  muted?: number | null;
}

export function applyTask(taskId: number, message?: string) {
  return request<TaskItem>({
    url: `/api/tasks/${taskId}/applications`,
    method: "POST",
    data: { message }
  });
}

export function listApplications(taskId: number) {
  return request<ApplicationItem[]>({
    url: `/api/tasks/${taskId}/applications`,
    method: "GET"
  });
}

export function acceptApplication(id: number) {
  return request<TaskItem>({
    url: `/api/applications/${id}/accept`,
    method: "POST"
  });
}

export function rejectApplication(id: number) {
  return request<TaskItem>({
    url: `/api/applications/${id}/reject`,
    method: "POST"
  });
}

export function withdrawApplication(id: number) {
  return request<TaskItem>({
    url: `/api/applications/${id}/withdraw`,
    method: "POST"
  });
}

export const APPLICATION_STATUS_TEXT: Record<string, string> = {
  PENDING: "待处理",
  ACCEPTED: "已接受",
  REJECTED: "已拒绝",
  CANCELLED: "已撤回"
};
