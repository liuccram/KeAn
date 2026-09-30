import { request } from "@/utils/request";

export interface BlacklistItem {
  id: number;
  blockedUserId: number;
  nickname: string;
  avatarUrl?: string | null;
  campusName?: string | null;
  createdAt: string;
}

export function listBlacklist() {
  return request<BlacklistItem[]>({
    url: "/api/blacklist",
    method: "GET"
  });
}

export function blockUser(blockedUserId: number) {
  return request<void>({
    url: "/api/blacklist",
    method: "POST",
    data: { blockedUserId }
  });
}

export function unblockUser(userId: number) {
  return request<void>({
    url: `/api/blacklist/${userId}`,
    method: "DELETE"
  });
}
