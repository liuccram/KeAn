/**
 * 私信时间线的本地合并：去重、排序、按 localId 用服务端消息替换本地乐观消息。
 *
 * 去重键：优先 localId（幂等重发后同一条消息只有一个 localId），否则用服务端 id。
 * 排序：有 seqNo 的按 seqNo 升序；没有 seqNo 的老数据按 createdAt 升序。
 * 老后端（字段缺失）下每个分支都能退化：缺 seqNo 就按时间排，缺 status 就按「已发送单勾」显示。
 */
import type { ChatMessageItem } from "@/api/chat";

function positive(value: unknown): number {
  const num = Number(value);
  return Number.isFinite(num) && num > 0 ? Math.floor(num) : 0;
}

function dedupeKey(message: ChatMessageItem): string {
  if (message.localId) {
    return `l:${message.localId}`;
  }
  return `i:${message.id}`;
}

function timeOf(message: ChatMessageItem): number {
  const time = Date.parse(String(message.createdAt || ""));
  return Number.isFinite(time) ? time : 0;
}

/** 缺 seqNo 的消息排在前面：它们通常是最早的历史分页数据 */
function seqOf(message: ChatMessageItem): number {
  const seq = positive(message.seqNo);
  return seq > 0 ? seq : -1;
}

export function sortTimeline(list: ChatMessageItem[]): ChatMessageItem[] {
  return [...list].sort((a, b) => {
    const seqA = seqOf(a);
    const seqB = seqOf(b);
    if (seqA !== seqB) {
      return seqA - seqB;
    }
    const timeA = timeOf(a);
    const timeB = timeOf(b);
    if (timeA !== timeB) {
      return timeA - timeB;
    }
    return Number(a.id || 0) - Number(b.id || 0);
  });
}

export function maxSeqOf(list: ChatMessageItem[]): number {
  let max = 0;
  list.forEach((item) => {
    const seq = positive(item.seqNo);
    if (seq > max) {
      max = seq;
    }
  });
  return max;
}

export function maxCreatedAtOf(list: ChatMessageItem[]): number {
  let max = 0;
  list.forEach((item) => {
    const time = timeOf(item);
    if (time > max) {
      max = time;
    }
  });
  return max;
}

/** 本地乐观消息：负 id，还没有服务端回执 */
export function isOptimistic(message: ChatMessageItem): boolean {
  return Number(message.id || 0) < 0 || Boolean(message.sendState);
}

/**
 * 合并新到达的消息：
 * - 按 localId 用服务端版本替换本地「发送中」的那条；
 * - 其余按 dedupeKey 去重，保留已有条目的前端字段（sendState 等），避免被推送覆盖掉。
 */
export function mergeIncoming(
  existing: ChatMessageItem[],
  incoming: ChatMessageItem[]
): ChatMessageItem[] {
  if (!incoming.length) {
    return existing;
  }
  const merged: ChatMessageItem[] = [...existing];
  const indexOf = new Map<string, number>();
  merged.forEach((item, index) => {
    const key = dedupeKey(item);
    if (!indexOf.has(key)) {
      indexOf.set(key, index);
    }
  });

  incoming.forEach((item) => {
    if (!item) {
      return;
    }
    const key = dedupeKey(item);
    const found = indexOf.get(key);
    if (found === undefined) {
      indexOf.set(key, merged.length);
      merged.push(item);
      return;
    }
    const previous = merged[found];
    // 服务端版本优先；sendState 等纯前端标记只在服务端没给时会保留
    const next: ChatMessageItem = { ...previous, ...item };
    if (item.sendState === undefined && previous.sendState) {
      next.sendState = previous.sendState;
    }
    merged[found] = next;
  });

  return sortTimeline(merged);
}

/** 把「我发出的、seqNo ≤ maxSeq」的消息标为已读（READ 事件到达时用） */
export function markMineRead(list: ChatMessageItem[], maxSeq: number): ChatMessageItem[] {
  const limit = positive(maxSeq);
  if (!limit || !list.length) {
    return list;
  }
  let touched = false;
  const next = list.map((item) => {
    if (!item.mine) {
      return item;
    }
    const seq = positive(item.seqNo);
    if (seq <= 0 || seq > limit || item.status === 3) {
      return item;
    }
    touched = true;
    return { ...item, status: 3 };
  });
  return touched ? next : list;
}
