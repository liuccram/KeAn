import { request } from "@/utils/request";
import type { TaskItem } from "./user";
import type { AdminReportItem } from "./report";

export interface TrendPoint {
  date: string;
  published: number;
  completed: number;
}

export interface StatusCount {
  status: string;
  count: number;
}

export interface DashboardData {
  userTotal: number;
  substituteUserTotal: number;
  pendingReportTotal: number;
  activeTaskTotal: number;
  onlineUserTotal: number;
  taskTrend: TrendPoint[];
  taskStatus: StatusCount[];
  recentTasks: TaskItem[];
  pendingReports: AdminReportItem[];
}

export interface StatsData {
  userGrowth: { date: string; count: number }[];
  taskTrend: TrendPoint[];
  schoolRanking: { schoolId: number; schoolName: string; taskCount: number }[];
  taskStatus: StatusCount[];
  reportTypes: { type: string; label: string; count: number }[];
}

export function getDashboard() {
  return request<DashboardData>("/api/admin/dashboard");
}

export function getStats(from?: string, to?: string) {
  return request<StatsData>("/api/admin/stats", { method: "GET", data: { from, to } });
}

export function getOnlineCount() {
  return request<number>("/api/admin/online", { method: "GET" });
}

export interface OnlineUser {
  id: number;
  username: string;
  nickname: string;
  avatarUrl?: string | null;
  schoolName?: string | null;
  campusName?: string | null;
  lastLoginAt?: string | null;
  online?: boolean;
}

export function listOnlineUsers() {
  return request<OnlineUser[]>("/api/admin/online/users", { method: "GET" });
}
