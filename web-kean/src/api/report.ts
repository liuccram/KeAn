import { request } from "@/utils/request";
import type { PageResult } from "./catalog";

export interface ReportAppealItem {
  id: number;
  reportId: number;
  userId: number;
  nickname?: string;
  content: string;
  images?: string[];
  status: string;
  handleRemark?: string | null;
  handlerNickname?: string | null;
  createdAt: string;
  handledAt?: string | null;
}

export interface AdminReportItem {
  id: number;
  reporterId: number;
  reporterNickname: string;
  targetType: string;
  targetId: number;
  targetLabel: string;
  targetUserId?: number | null;
  targetUserNickname?: string | null;
  targetUserStatus?: string | null;
  forbidPublish?: number | null;
  forbidApply?: number | null;
  muted?: number | null;
  type: string;
  typeLabel: string;
  description?: string | null;
  images?: string[];
  status: string;
  handleResult?: string | null;
  handleRemark?: string | null;
  handlerId?: number | null;
  handlerNickname?: string | null;
  createdAt: string;
  handledAt?: string | null;
  appeals?: ReportAppealItem[];
}

export function listAdminReports(params?: Record<string, unknown>) {
  return request<PageResult<AdminReportItem>>("/api/admin/reports", { method: "GET", data: params });
}

export function getAdminReport(id: number) {
  return request<AdminReportItem>(`/api/admin/reports/${id}`);
}

export function handleAdminReport(id: number, result: string, remark?: string) {
  return request<AdminReportItem>(`/api/admin/reports/${id}`, { method: "PUT", data: { result, remark } });
}

export function handleAdminAppeal(reportId: number, appealId: number, result: string, remark?: string) {
  return request<ReportAppealItem>(`/api/admin/reports/${reportId}/appeals/${appealId}`, {
    method: "PUT",
    data: { result, remark }
  });
}

export function pendingAppealCount() {
  return request<number>("/api/admin/reports/appeals/pending-count");
}

export function hasPendingAppeal(item?: { appeals?: ReportAppealItem[] | null }) {
  return Boolean(item?.appeals?.some((row) => row.status === "PENDING"));
}
