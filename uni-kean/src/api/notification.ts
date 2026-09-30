import { request } from "@/utils/request";

export interface NotificationItem {
  id: number;
  type: string;
  title: string;
  content: string;
  bizType?: string | null;
  bizId?: number | null;
  readFlag: number;
  createdAt: string;
}

export interface PageResult<T> {
  list: T[];
  total: number;
  page: number;
  size: number;
}

export function listNotifications(page = 1, size = 20, scope?: "SYSTEM" | "TASK") {
  return request<PageResult<NotificationItem>>({
    url: "/api/notifications",
    method: "GET",
    data: { page, size, scope }
  });
}

export function unreadNotificationCount(scope?: "SYSTEM" | "TASK") {
  return request<number>({
    url: "/api/notifications/unread-count",
    method: "GET",
    data: scope ? { scope } : undefined
  });
}

export function markNotificationRead(id: number) {
  return request<null>({
    url: `/api/notifications/${id}/read`,
    method: "POST"
  });
}

export function markAllNotificationsRead(scope?: "SYSTEM" | "TASK") {
  return request<null>({
    url: scope ? `/api/notifications/read-all?scope=${encodeURIComponent(scope)}` : "/api/notifications/read-all",
    method: "POST"
  });
}
