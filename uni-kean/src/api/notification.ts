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
  /**
   * 收件角色（后端 V36 起落库的 notification.receiver_role）：
   * `"PUBLISHER"` = 这条是发给发布者的，`"APPLICANT"` = 发给代课者的。
   *
   * ⚠️ 可选 + 可空，且老后端/历史数据**不会返回这个字段**（后端 non_null 序列化，
   * 值为 null 时字段直接不出现）：拿不到时按原来的正文启发式判定，绝不因为缺字段而报错。
   */
  receiverRole?: string | null;
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
