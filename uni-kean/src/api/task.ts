import { request } from "@/utils/request";

export interface PublisherBrief {
  id: number;
  nickname: string;
  avatarUrl?: string | null;
  gender?: string | null;
  schoolName?: string | null;
  campusName?: string | null;
  completedCount: number;
  ratingAvg?: number | null;
  ratingCount?: number | null;
  cancelledCount?: number | null;
  reportedCount?: number | null;
  status?: string | null;
  forbidPublish?: number | null;
  forbidApply?: number | null;
  muted?: number | null;
}

export interface TaskItem {
  id: number;
  publisherId: number;
  courseId?: number | null;
  courseName: string;
  taskDate: string;
  startTime: string;
  endTime: string;
  startAt?: string;
  endAt?: string;
  schoolId: number;
  campusId: number;
  campusName?: string | null;
  building: string;
  classroom: string;
  computerLab?: number | null;
  requirePhoto?: number | null;
  genderRequirement?: string | null;
  reward: number;
  reason?: string | null;
  requirement?: string | null;
  remark?: string | null;
  status: string;
  applyCount: number;
  createdAt: string;
  publisher?: PublisherBrief | null;
  applicant?: PublisherBrief | null;
  mine?: boolean;
  publisherConfirmed?: number;
  applicantConfirmed?: number;
  publisherCompleted?: number;
  applicantCompleted?: number;
  acceptedApplicationId?: number | null;
  myApplicationStatus?: string | null;
  myApplicationId?: number | null;
  matchedApplicant?: boolean;
  matchedApplicantNickname?: string | null;
  matchedApplicantId?: number | null;
  canReview?: boolean;
  myReviewRating?: number | null;
  favorited?: boolean;
  fulfillPhotoUrl?: string | null;
}

export interface PageResult<T> {
  list: T[];
  total: number;
  page: number;
  size: number;
}

export interface TaskQuery {
  keyword?: string;
  taskDate?: string;
  courseId?: number;
  campusId?: number;
  timeSlot?: string;
  schoolId?: number;
  status?: string;
  page?: number;
  size?: number;
}

export interface TaskPayload {
  courseName: string;
  taskDate: string;
  startTime: string;
  endTime: string;
  campusId: number;
  building: string;
  classroom: string;
  computerLab: boolean;
  requirePhoto?: boolean;
  genderRequirement: string;
  reward: number;
  reason?: string;
  requirement?: string;
  remark?: string;
}

function compact(params: TaskQuery) {
  const data: Record<string, string | number> = {};
  Object.entries(params).forEach(([key, value]) => {
    if (value === undefined || value === null || value === "") {
      return;
    }
    data[key] = value as string | number;
  });
  return data;
}

export function listTasks(params: TaskQuery) {
  return request<PageResult<TaskItem>>({
    url: "/api/tasks",
    method: "GET",
    data: compact(params)
  });
}

export function getTask(id: number) {
  return request<TaskItem>({
    url: `/api/tasks/${id}`,
    method: "GET"
  });
}

export function createTask(payload: TaskPayload) {
  return request<TaskItem>({
    url: "/api/tasks",
    method: "POST",
    data: payload
  });
}

export function updateTask(id: number, payload: TaskPayload) {
  return request<TaskItem>({
    url: `/api/tasks/${id}`,
    method: "PUT",
    data: payload
  });
}

export function deleteTask(id: number) {
  return request<null>({
    url: `/api/tasks/${id}`,
    method: "DELETE"
  });
}

export function confirmTask(id: number, objectKey: string) {
  return request<TaskItem>({
    url: `/api/tasks/${id}/confirm`,
    method: "POST",
    data: { objectKey }
  });
}

export function completeTask(id: number) {
  return request<TaskItem>({
    url: `/api/tasks/${id}/complete`,
    method: "POST"
  });
}

export function cancelTask(id: number, reason?: string) {
  return request<TaskItem>({
    url: `/api/tasks/${id}/cancel`,
    method: "POST",
    data: { reason }
  });
}

export function listMyPublished(page = 1, size = 20, excludeCancelled = false) {
  return request<PageResult<TaskItem>>({
    url: "/api/me/published",
    method: "GET",
    data: { page, size, ...(excludeCancelled ? { excludeCancelled: true } : {}) }
  });
}

export function listMyApplied(page = 1, size = 20, excludeCancelled = false) {
  return request<PageResult<TaskItem>>({
    url: "/api/me/applied",
    method: "GET",
    data: { page, size, ...(excludeCancelled ? { excludeCancelled: true } : {}) }
  });
}

export const TASK_STATUS_TEXT: Record<string, string> = {
  WAITING: "待申请",
  APPLYING: "申请中",
  MATCHED: "待上课",
  CONFIRMED: "待上课",
  IN_PROGRESS: "上课中",
  COMPLETED: "已完成",
  CANCELLED: "已取消",
  EXPIRED: "已过期"
};
