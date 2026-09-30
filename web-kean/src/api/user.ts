import { request } from "@/utils/request";
import type { PageResult } from "./catalog";

export interface AdminUser {
  id: number;
  role: string;
  username: string;
  phone?: string | null;
  email?: string | null;
  nickname: string;
  avatarUrl?: string | null;
  gender?: string | null;
  schoolId?: number | null;
  campusId?: number | null;
  schoolName?: string | null;
  campusName?: string | null;
  completedCount?: number;
  ratingAvg?: number | null;
  ratingCount?: number;
  publishCompletedCount?: number;
  publishRatingAvg?: number | null;
  publishRatingCount?: number;
  applyRatingAvg?: number | null;
  applyRatingCount?: number;
  cancelledCount?: number;
  reportedCount?: number;
  status: string;
  forbidPublish?: number;
  forbidApply?: number;
  muted?: number;
  forbidPublishUntil?: string | null;
  forbidApplyUntil?: string | null;
  mutedUntil?: string | null;
  lastLoginAt?: string | null;
  createdAt?: string;
  online?: boolean;
}

export interface AdminUserPage {
  list: AdminUser[];
  total: number;
  page: number;
  size: number;
  summary: { total: number; male: number; female: number; banned: number };
  genderGroups?: GenderGroup[];
  statusStats?: StatusStats;
}

export interface StatusStats {
  normal: number;
  banned: number;
  restricted: number;
  forbidPublish: number;
  forbidApply: number;
  muted: number;
}

export interface GenderGroup {
  schoolId?: number | null;
  schoolName?: string | null;
  campusId?: number | null;
  campusName?: string | null;
  male: number;
  female: number;
  unknown: number;
  total: number;
}

export interface TaskItem {
  id: number;
  courseName: string;
  publisherId?: number;
  publisherNickname?: string | null;
  applicantId?: number | null;
  applicantNickname?: string | null;
  taskDate?: string;
  startTime?: string;
  endTime?: string;
  schoolName?: string | null;
  campusName?: string | null;
  building?: string | null;
  classroom?: string | null;
  status: string;
  applyCount?: number;
  reward?: number;
  createdAt?: string;
}

export interface ReviewItem {
  id: number;
  taskId: number;
  courseName?: string;
  fromNickname?: string;
  rating: number;
  tags?: string[];
  content?: string | null;
  targetRole?: string | null;
  createdAt?: string;
}

export interface UserDetail {
  user: AdminUser;
  published: PageResult<TaskItem>;
  applied: PageResult<TaskItem>;
  given: ReviewItem[];
  received: ReviewItem[];
}

export function listUsers(params: Record<string, unknown>) {
  return request<AdminUserPage>("/api/admin/users", { method: "GET", data: params });
}

export function getUser(id: number) {
  return request<UserDetail>(`/api/admin/users/${id}`);
}

export function updateUserStatus(id: number, status: string, remark?: string) {
  return request<AdminUser>(`/api/admin/users/${id}/status`, { method: "PUT", data: { status, remark } });
}

export function updateUserRestrictions(
  id: number,
  payload: { forbidPublish?: number; forbidApply?: number; muted?: number; days?: number; remark?: string }
) {
  return request<AdminUser>(`/api/admin/users/${id}/restrictions`, { method: "PUT", data: payload });
}

export function resetUserPassword(id: number, password: string) {
  return request<null>(`/api/admin/users/${id}/password`, { method: "PUT", data: { password } });
}
