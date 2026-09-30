import { request } from "@/utils/request";

export interface ActiveAnnouncement {
  id: number;
  title: string;
  content: string;
  scope: string;
  schoolName?: string | null;
  publishedAt?: string | null;
}

export function listActiveAnnouncements() {
  return request<ActiveAnnouncement[]>({
    url: "/api/announcements/active",
    method: "GET"
  });
}
