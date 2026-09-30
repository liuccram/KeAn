import { request } from "@/utils/request";
import type { PageResult } from "./catalog";

export interface AnnouncementItem {
  id: number;
  title: string;
  content: string;
  status: string;
  scope: string;
  schoolId?: number | null;
  schoolName?: string | null;
  publisherName?: string | null;
  publishedAt?: string | null;
  createdAt: string;
}

export function listAnnouncements(params?: Record<string, unknown>) {
  return request<PageResult<AnnouncementItem>>("/api/admin/announcements", { method: "GET", data: params });
}

export function createAnnouncement(payload: {
  title: string;
  content: string;
  scope: string;
  schoolId?: number | null;
  publish?: boolean;
}) {
  return request<AnnouncementItem>("/api/admin/announcements", { method: "POST", data: payload });
}

export function updateAnnouncement(id: number, payload: {
  title: string;
  content: string;
  scope: string;
  schoolId?: number | null;
  publish?: boolean;
}) {
  return request<AnnouncementItem>(`/api/admin/announcements/${id}`, { method: "PUT", data: payload });
}

export function publishAnnouncement(id: number) {
  return request<AnnouncementItem>(`/api/admin/announcements/${id}/publish`, { method: "POST" });
}

export function offlineAnnouncement(id: number) {
  return request<AnnouncementItem>(`/api/admin/announcements/${id}/offline`, { method: "POST" });
}
