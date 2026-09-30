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

export function listNotifications(page = 1, size = 8) {
  return request<PageResult<NotificationItem>>("/api/notifications", {
    method: "GET",
    data: { page, size, scope: "SYSTEM" }
  });
}

export function unreadNotificationCount() {
  return request<number>("/api/notifications/unread-count", {
    method: "GET",
    data: { scope: "SYSTEM" }
  });
}

export function markNotificationRead(id: number) {
  return request<null>(`/api/notifications/${id}/read`, { method: "POST" });
}
