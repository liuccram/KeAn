import { request } from "@/utils/request";
import type { PageResult } from "./catalog";
import type { TaskItem } from "./user";

export interface TaskDetail {
  task: TaskItem;
  reason?: string | null;
  requirement?: string | null;
  remark?: string | null;
  genderRequirement?: string | null;
  computerLab?: number;
  requirePhoto?: number;
  publisherConfirmed?: number;
  applicantConfirmed?: number;
  publisherCompleted?: number;
  applicantCompleted?: number;
  fulfillPhotoUrl?: string | null;
  cancelReason?: string | null;
  cancelledBy?: string | null;
  timeline: { at: string; event: string; label: string }[];
}

export interface ApplicationItem {
  id: number;
  taskId: number;
  applicantId: number;
  nickname?: string;
  schoolName?: string;
  message?: string | null;
  status: string;
  createdAt: string;
}

export function listTasks(params: Record<string, unknown>) {
  return request<PageResult<TaskItem>>("/api/admin/tasks", { method: "GET", data: params });
}

export function getTask(id: number) {
  return request<TaskDetail>(`/api/admin/tasks/${id}`);
}

export function listTaskApplications(id: number) {
  return request<ApplicationItem[]>(`/api/admin/tasks/${id}/applications`);
}

export function cancelTask(id: number, reason: string) {
  return request<TaskDetail>(`/api/admin/tasks/${id}/cancel`, { method: "POST", data: { reason } });
}
