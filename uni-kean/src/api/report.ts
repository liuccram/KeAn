import { request } from "@/utils/request";

export interface ReportTypeItem {
  value: string;
  label: string;
}

export interface ReportAppealItem {
  id: number;
  reportId: number;
  content: string;
  images?: string[];
  status: string;
  handleRemark?: string | null;
  createdAt: string;
  handledAt?: string | null;
}

export interface ReportItem {
  id: number;
  reporterId: number;
  reporterNickname: string;
  targetType: string;
  targetId: number;
  targetLabel: string;
  type: string;
  typeLabel: string;
  description?: string | null;
  images?: string[];
  status: string;
  handleResult?: string | null;
  handleRemark?: string | null;
  createdAt: string;
  handledAt?: string | null;
  appeals?: ReportAppealItem[];
}

export function listReportTypes(scope?: string) {
  return request<ReportTypeItem[]>({
    url: "/api/reports/types",
    method: "GET",
    data: scope ? { scope } : undefined
  });
}

export function createReport(payload: {
  targetType: string;
  targetId?: number;
  type: string;
  description?: string;
  images?: string[];
}) {
  return request<ReportItem>({
    url: "/api/reports",
    method: "POST",
    data: payload
  });
}

export function listMyReports() {
  return request<ReportItem[]>({
    url: "/api/reports/me",
    method: "GET"
  });
}

export function listReportsAgainstMe() {
  return request<ReportItem[]>({
    url: "/api/reports/against-me",
    method: "GET"
  });
}

export function createAppeal(reportId: number, content: string, images?: string[]) {
  return request<ReportAppealItem>({
    url: `/api/reports/${reportId}/appeals`,
    method: "POST",
    data: { content, images }
  });
}
