/**
 * 私信本地状态：增量拉取位点（lastSeq）+ 本地已读位点 + 前端 pending 队列（outbox）。
 *
 * 与 box-im 对齐后，服务端给每条消息分配单调递增的 seqNo：
 * - lastSeq 是「我已经拉到/看到哪一条」的本地游标，用于 afterSeq=<lastSeq> 增量拉取，
 *   也顺带给会话列表做「有新消息」的辅助判断；
 * - readAt 键里记的是本地已读位点（number），只用于「这次进会话要不要发已读回执」；
 *   消息对象上的 readAt 是服务端给的 ISO 字符串，两者不是一回事；
 * - outbox 存放发送失败、等待重发的消息（带 localId），按会话分开落 storage，
 *   这样退出会话页再进来仍能看到「失败可重发」的气泡。
 *
 * 老后端（没有 seqNo/localId/status 字段）下这些游标恒为 0/空，所有分支都退化成旧行为，
 * 不会抛错也不会白屏。
 */

/** 前端本地发送态，故意不复用后端的 0/1/2/3，避免混用 */
export type ChatSendState = "sending" | "failed";

export interface ChatOutboxEntry {
  localId: string;
  sessionId: number;
  /** TEXT 时是文本内容；IMAGE 时是已经上传好的 objectKey */
  content: string;
  msgType: string;
  /** 已尝试次数，用于「重试 3 次后置为失败」的提示 */
  attempts: number;
  /** 落盘时间，用于清理过老的记录 */
  updatedAt: number;
}

const LAST_SEQ_KEY_PREFIX = "kean_chat_last_seq_";
const READ_AT_KEY_PREFIX = "kean_chat_read_at_";
const OUTBOX_KEY_PREFIX = "kean_chat_outbox_";
/** 老版本（V1）pending 键：读取时兼容，写入后清除 */
const OUTBOX_KEY_PREFIX_V1 = "kean_chat_pending_";

/** 服务端未分配 seqNo 时的占位值：全是 0 会让增量拉取变成全量重拉 */
export const NO_SEQ = 0;

let localIdCounter = Math.floor(Math.random() * 1296);
let localMessageIdCounter = -1;

function toCount(value: unknown): number {
  const num = Number(value);
  return Number.isFinite(num) && num > 0 ? Math.floor(num) : 0;
}

function readNumber(key: string): number {
  try {
    return toCount(uni.getStorageSync(key));
  } catch {
    return 0;
  }
}

function writeNumber(key: string, value: number): void {
  try {
    if (value > 0) {
      uni.setStorageSync(key, value);
      return;
    }
    uni.removeStorageSync(key);
  } catch {
    // storage 不可用时降级为「不记忆」，不影响功能
  }
}

/** 生成 ≤32 字符的 localId（时间戳 36 进制 + 会话内自增 + 随机数），不引入新依赖 */
export function createLocalId(): string {
  localIdCounter = (localIdCounter + 1) % 1296;
  const stamp = Date.now().toString(36);
  const counter = localIdCounter.toString(36);
  const random = Math.floor(Math.random() * 1679616).toString(36);
  return `${stamp}-${counter}${random}`;
}

/** 本地乐观消息的临时 id：负数，不会和任何服务端 id 相撞 */
export function nextLocalMessageId(): number {
  localMessageIdCounter -= 1;
  return localMessageIdCounter;
}

export function getLastSeq(sessionId: number): number {
  if (!sessionId) {
    return 0;
  }
  return readNumber(`${LAST_SEQ_KEY_PREFIX}${sessionId}`);
}

/** 只前进不后退：小于当前位点的写入会被忽略，避免乱序响应把游标拉回去 */
export function setLastSeq(sessionId: number, seqNo: number): void {
  if (!sessionId) {
    return;
  }
  const next = toCount(seqNo);
  if (next <= 0 || next <= getLastSeq(sessionId)) {
    return;
  }
  writeNumber(`${LAST_SEQ_KEY_PREFIX}${sessionId}`, next);
}

export function getReadAt(sessionId: number): number {
  if (!sessionId) {
    return 0;
  }
  return readNumber(`${READ_AT_KEY_PREFIX}${sessionId}`);
}

/**
 * 本地已读位点的内部记录：只用来判断「本次进会话有没有比上次多读到东西」。
 * 注意这里是 number（可以是 seqNo，也可以是 createdAt 的毫秒值），
 * 与消息对象上的 readAt（服务端给的 ISO 字符串）是两回事，别混用。
 */
export function setReadAt(sessionId: number, value: number): void {
  if (!sessionId) {
    return;
  }
  const next = toCount(value);
  if (next <= 0 || next <= getReadAt(sessionId)) {
    return;
  }
  writeNumber(`${READ_AT_KEY_PREFIX}${sessionId}`, next);
}

/** 会话列表用：进入会话前看到的最后一条 seqNo，只作「有新消息」的辅助提示 */
export function getViewedSeq(sessionId: number): number {
  return getLastSeq(sessionId);
}

export function setViewedSeq(sessionId: number, seqNo: number): void {
  setLastSeq(sessionId, seqNo);
}

function outboxKey(sessionId: number, legacy = false): string {
  return `${legacy ? OUTBOX_KEY_PREFIX_V1 : OUTBOX_KEY_PREFIX}${sessionId}`;
}

function normalizeEntry(raw: unknown, sessionId: number): ChatOutboxEntry | null {
  if (!raw || typeof raw !== "object") {
    return null;
  }
  const item = raw as Record<string, unknown>;
  const localId = typeof item.localId === "string" ? item.localId : "";
  if (!localId || typeof item.content !== "string" || !item.content) {
    return null;
  }
  return {
    localId,
    sessionId,
    content: item.content,
    msgType: typeof item.msgType === "string" && item.msgType ? item.msgType : "TEXT",
    attempts: toCount(item.attempts),
    updatedAt: toCount(item.updatedAt) || Date.now()
  };
}

/** 读取某会话的待重发队列；同时兼容旧版 pending 键（内容结构一致） */
export function readOutbox(sessionId: number): ChatOutboxEntry[] {
  if (!sessionId) {
    return [];
  }
  const keys = [outboxKey(sessionId), outboxKey(sessionId, true)];
  for (const key of keys) {
    try {
      const raw = uni.getStorageSync(key);
      if (!raw) {
        continue;
      }
      const parsed = typeof raw === "string" ? JSON.parse(raw) : raw;
      if (!Array.isArray(parsed)) {
        continue;
      }
      const list: ChatOutboxEntry[] = [];
      parsed.forEach((item) => {
        const entry = normalizeEntry(item, sessionId);
        if (entry) {
          list.push(entry);
        }
      });
      return list;
    } catch {
      // 解析失败按空队列处理，不影响页面
    }
  }
  return [];
}

function writeOutbox(sessionId: number, list: ChatOutboxEntry[]): void {
  if (!sessionId) {
    return;
  }
  try {
    if (!list.length) {
      uni.removeStorageSync(outboxKey(sessionId));
      uni.removeStorageSync(outboxKey(sessionId, true));
      return;
    }
    uni.setStorageSync(outboxKey(sessionId), JSON.stringify(list));
    uni.removeStorageSync(outboxKey(sessionId, true));
  } catch {
    // 落盘失败时本轮重发仍可用，只是退出页面后不再恢复
  }
}

export function listOutbox(sessionId: number): ChatOutboxEntry[] {
  return readOutbox(sessionId);
}

export function getOutboxEntry(sessionId: number, localId?: string | null): ChatOutboxEntry | null {
  if (!localId) {
    return null;
  }
  return readOutbox(sessionId).find((item) => item.localId === localId) || null;
}

export function upsertOutbox(entry: ChatOutboxEntry): void {
  const list = readOutbox(entry.sessionId);
  const index = list.findIndex((item) => item.localId === entry.localId);
  if (index >= 0) {
    list[index] = entry;
  } else {
    list.push(entry);
  }
  writeOutbox(entry.sessionId, list);
}

export function removeOutbox(sessionId: number, localId?: string | null): void {
  if (!localId) {
    return;
  }
  const list = readOutbox(sessionId).filter((item) => item.localId !== localId);
  writeOutbox(sessionId, list);
}

/** 会话列表带的 lastSeqNo 辅助判断：本地记录 < 服务端最新 即认为有新消息 */
export function hasNewerSeq(sessionId: number, lastSeqNo?: number | null): boolean {
  const remote = toCount(lastSeqNo);
  if (!sessionId || remote <= 0) {
    return false;
  }
  return remote > getViewedSeq(sessionId);
}

/** 本地是否已经为这个会话记过位点（区分「第一次见到」和「看到过旧位点」） */
export function hasViewedSeq(sessionId: number): boolean {
  if (!sessionId) {
    return false;
  }
  return readNumber(`${LAST_SEQ_KEY_PREFIX}${sessionId}`) > 0;
}

export function syncViewedSeq(sessionId: number, lastSeqNo?: number | null): void {
  setViewedSeq(sessionId, toCount(lastSeqNo));
}
