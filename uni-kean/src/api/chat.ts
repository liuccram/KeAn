import { request } from "@/utils/request";
import type { PageResult } from "@/api/task";

export interface ChatSessionItem {
  id: number;
  peerUserId: number;
  peerNickname: string;
  peerAvatarUrl?: string | null;
  lastContent?: string | null;
  lastMessageAt?: string | null;
  unreadCount: number;
  peerMuted?: boolean;
  peerBanned?: boolean;
}

export interface ChatMessageItem {
  id: number;
  sessionId: number;
  senderId: number;
  msgType: string;
  content: string;
  url?: string | null;
  createdAt: string;
  mine: boolean;
}

export function listChats() {
  return request<ChatSessionItem[]>({
    url: "/api/chats",
    method: "GET"
  });
}

export function openChat(peerUserId: number) {
  return request<ChatSessionItem>({
    url: "/api/chats",
    method: "POST",
    data: { peerUserId }
  });
}

export function unreadChatCount() {
  return request<number>({
    url: "/api/chats/unread-count",
    method: "GET"
  });
}

export function getChat(id: number) {
  return request<ChatSessionItem>({
    url: `/api/chats/${id}`,
    method: "GET"
  });
}

export function listChatMessages(id: number, page = 1, size = 30) {
  return request<PageResult<ChatMessageItem>>({
    url: `/api/chats/${id}/messages`,
    method: "GET",
    data: { page, size }
  });
}

export function sendChatMessage(id: number, content: string, msgType = "TEXT") {
  return request<ChatMessageItem>({
    url: `/api/chats/${id}/messages`,
    method: "POST",
    data: { content, msgType }
  });
}

export function markChatRead(id: number) {
  return request<null>({
    url: `/api/chats/${id}/read`,
    method: "POST"
  });
}
