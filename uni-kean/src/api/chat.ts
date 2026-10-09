import { request } from "@/utils/request";
import type { PageResult } from "@/api/task";
import type { ChatSendState } from "@/utils/chatStore";

/** 消息状态（服务端口径）：0 未读 / 1 已发送 / 2 撤回 / 3 已读 */
export type ChatMessageStatus = 0 | 1 | 2 | 3;

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
  /** 与 box-im 对齐后追加：该会话最新一条消息的 seqNo（老后端可能没有） */
  lastSeqNo?: number | null;
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
  /** 服务端单调递增序号：增量拉取与已读回执的基准（老后端可能没有） */
  seqNo?: number | null;
  /** 客户端幂等键：同一次发送重试时保持不变（老后端可能没有） */
  localId?: string | null;
  status?: ChatMessageStatus | null;
  readAt?: string | null;
  /**
   * 仅前端的本地发送态：与后端的 status（0/1/2/3）分开，避免语义混用。
   * sending = 发送中/重试中；failed = 重试用尽，可手动重发。
   */
  sendState?: ChatSendState;
  /** 与 timeGroup 无关的展示字段由页面自己算，这里只声明可选扩展位 */
  showTime?: boolean;
}

export interface ChatMessagePage {
  list: ChatMessageItem[];
  total: number;
  page: number;
  size: number;
}

/** 增量拉取的返回：兼容 PageResult 与「纯数组」两种后端返回形态 */
export interface ChatMessageIncrement {
  /** 本次真正返回的新消息（已按 seqNo 升序） */
  list: ChatMessageItem[];
  total: number;
  serverMaxSeq: number;
  /** 返回条数达到 batchSize，可能还有更多 */
  hasMore: boolean;
}

export interface SendChatMessageOptions {
  msgType?: string;
  /** 传同一个 localId 时服务端幂等去重，不会产生重复消息 */
  localId?: string;
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

function toSeq(value: unknown): number {
  const num = Number(value);
  return Number.isFinite(num) && num > 0 ? Math.floor(num) : 0;
}

/** 从一条消息里取 seqNo，兼容 seqNo / seq 两种命名 */
function messageSeq(message: ChatMessageItem): number {
  const raw = message as unknown as { seqNo?: unknown; seq?: unknown };
  return Math.max(toSeq(raw.seqNo), toSeq(raw.seq));
}

/** 增量追进度：服务端 afterSeq 模式单次最多返回 200 条（忽略 page/size） */
export const CHAT_CATCH_UP_LIMIT = 200;

/**
 * 兼容后端两种返回形态：
 * 1) { code:0, data:{ list:[...], total, page, size } }（分页对象）
 * 2) { code:0, data:[ ... ] }（纯数组）
 * 缺失字段一律兜底，老后端下退化成「本次没有增量」，不会抛错。
 */
export function normalizeMessageIncrement(
  raw: unknown,
  options?: { afterSeq?: number; limit?: number }
): ChatMessageIncrement {
  const afterSeq = toSeq(options?.afterSeq);
  const rows: ChatMessageItem[] = Array.isArray(raw)
    ? (raw as ChatMessageItem[])
    : Array.isArray((raw as { list?: unknown } | null)?.list)
      ? ((raw as { list: ChatMessageItem[] }).list)
      : [];

  const totalRaw = Number((raw as { total?: unknown } | null)?.total);
  const total = Number.isFinite(totalRaw) && totalRaw > 0 ? Math.floor(totalRaw) : rows.length;

  let serverMaxSeq = 0;
  const fresh: ChatMessageItem[] = [];
  const seen = new Set<number>();
  rows.forEach((row) => {
    if (!row) {
      return;
    }
    const seq = messageSeq(row);
    if (seq > serverMaxSeq) {
      serverMaxSeq = seq;
    }
    if (afterSeq > 0 && seq > 0 && seq <= afterSeq) {
      // 后端理论上只返回 seq_no > afterSeq；这里再兜一层，防止重复合并
      return;
    }
    const key = seq > 0 ? seq : -Number(row.id || 0);
    if (seen.has(key)) {
      return;
    }
    seen.add(key);
    fresh.push(row);
  });

  const limit = toSeq(options?.limit) || CHAT_CATCH_UP_LIMIT;
  return {
    list: fresh,
    total,
    serverMaxSeq,
    hasMore: rows.length >= limit
  };
}

/** afterSeq 增量拉取：只返回 seq_no > afterSeq 的消息（升序） */
export async function listChatMessagesAfter(
  id: number,
  afterSeq: number,
  size = 50
): Promise<ChatMessageIncrement> {
  // size 只对老后端的分页分支有意义；afterSeq 分支由服务端固定上限 200 条
  const raw = await request<unknown>({
    url: `/api/chats/${id}/messages`,
    method: "GET",
    data: { afterSeq, page: 1, size }
  });
  return normalizeMessageIncrement(raw, { afterSeq, limit: CHAT_CATCH_UP_LIMIT });
}

export function sendChatMessage(id: number, content: string, options: SendChatMessageOptions | string = {}) {
  // 兼容旧调用：第三个参数传字符串时视为 msgType
  const msgType = typeof options === "string" ? options : options.msgType || "TEXT";
  const data: Record<string, unknown> = { type: msgType, msgType, content };
  const localId = typeof options === "string" ? "" : options.localId || "";
  if (localId) {
    data.localId = localId;
  }
  return request<ChatMessageItem>({
    url: `/api/chats/${id}/messages`,
    method: "POST",
    data
  });
}

/**
 * 已读回执：maxSeq 为目前已经显示的最大 seqNo。
 * 传 0/缺省时按旧口径（不带 body）调用，老后端仍能处理。
 */
export function markChatRead(id: number, maxSeq = 0) {
  const seq = toSeq(maxSeq);
  return request<null>({
    url: `/api/chats/${id}/read`,
    method: "POST",
    data: seq > 0 ? { maxSeq: seq } : undefined
  });
}
