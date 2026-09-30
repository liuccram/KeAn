import { request } from "@/utils/request";
import type { PageResult } from "./catalog";

export interface AdminAccount {
  id: number;
  username: string;
  nickname: string;
  status: string;
  lastLoginAt?: string | null;
  createdAt: string;
}

export interface OperationLogItem {
  id: number;
  adminId: number;
  adminName: string;
  operationType: string;
  targetType: string;
  targetId?: string | null;
  result: string;
  ip?: string | null;
  description?: string | null;
  createdAt: string;
}

export interface ConfigItem {
  key: string;
  value: string;
  remark?: string | null;
}

export function listAdmins() {
  return request<AdminAccount[]>("/api/admin/admins");
}

export function createAdmin(payload: { username: string; password: string; nickname: string }) {
  return request<AdminAccount>("/api/admin/admins", { method: "POST", data: payload });
}

export function updateAdminStatus(id: number, status: string, remark?: string) {
  return request<AdminAccount>(`/api/admin/admins/${id}/status`, { method: "PUT", data: { status, remark } });
}

export function changeOwnPassword(oldPassword: string, newPassword: string) {
  return request<null>("/api/admin/me/password", { method: "PUT", data: { oldPassword, newPassword } });
}

export function listLogs(params?: Record<string, unknown>) {
  return request<PageResult<OperationLogItem>>("/api/admin/logs", { method: "GET", data: params });
}

export function listConfig() {
  return request<ConfigItem[]>("/api/admin/config");
}

export function updateConfig(items: { key: string; value: string }[]) {
  return request<ConfigItem[]>("/api/admin/config", { method: "PUT", data: { items } });
}
