/**
 * 私信收发与已读回执的前端状态机。
 *
 * 发送（localId 幂等 + pending 队列）：
 *   pending(发送中) --成功--> sent（用服务端返回的消息按 localId 替换本地那条，pending 出队）
 *                  --失败/超时--> attempts+1，退避 600ms/1200ms 重试，重试始终带同一个 localId
 *                  --重试仍失败--> failed（红色感叹号，长按/点击手动重发）
 *                  --不可重试错误--> 直接 failed（不做无谓重试）
 *
 * 已读：只有「当前已显示的最大 seqNo 变大」才触发一次 READ 上报，1 秒节流合并；
 * 收到 READ 事件时把「我发出的、seqNo ≤ maxSeq」的消息标为已读（气泡双勾）。
 */
import {
  markChatRead,
  sendChatMessage,
  type ChatMessageItem,
  type SendChatMessageOptions
} from "@/api/chat";
import {
  createLocalId,
  getOutboxEntry,
  nextLocalMessageId,
  removeOutbox,
  setReadAt,
  upsertOutbox,
  type ChatOutboxEntry,
  type ChatSendState
} from "@/utils/chatStore";

/** 首个请求之后再重试的次数：一次发送最多 3 次网络尝试 */
const MAX_RETRY_ATTEMPTS = 2;
const RETRY_BASE_DELAY_MS = 600;
/** 已读上报节流窗口：窗口内只发最后一次（最新的 maxSeq） */
const READ_THROTTLE_MS = 1000;
/** 已读上报失败后的补发延迟 */
const REPORT_RETRY_MS = 3000;

/** 我发出的消息的本地可读状态（"" = 不显示角标） */
export type ChatOutgoingStatus = ChatSendState | "sent" | "read" | "";

export interface ChatSendContext {
  sessionId: number;
  /** 乐观插入本地「发送中」消息，返回它的临时 id */
  insertLocal(input: {
    localId: string;
    content: string;
    msgType: string;
    sendState: ChatSendState;
    attempts: number;
  }): number;
}

export interface ChatSendPayload {
  localId?: string;
  content: string;
  msgType: string;
  attempts: number;
}

export interface ChatSendResult {
  ok: boolean;
  localId: string;
  message?: ChatMessageItem;
}

function nowMs(): number {
  return Date.now();
}

/**
 * 判断一次发送失败是否值得重试。
 * 以文案匹配为主：request.ts 对网络/超时/服务端异常固定给下面这几句话，
 * 业务类失败（未登录、无权限、内容不合规、被禁言）都是服务端原话，不会命中。
 */
function isRetryableSendError(error: Error): boolean {
  const message = error?.message || "";
  return (
    message.includes("请求超时") ||
    message.includes("网络连接失败") ||
    message.includes("稍后重试") ||
    message.includes("服务器") ||
    message.includes("服务异常")
  );
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => {
    setTimeout(resolve, ms);
  });
}

/** 生成本地消息 + 同一个 localId 的发送载荷 */
export function createOutboxPayload(content: string, msgType = "TEXT"): ChatSendPayload {
  return {
    localId: createLocalId(),
    content,
    msgType,
    attempts: 0
  };
}

/** 本地乐观消息：负 id，status 留空（前端只用 sendState 判断气泡角标） */
export function buildLocalMessage(
  payload: ChatSendPayload,
  options?: { url?: string; sendState?: ChatSendState }
): ChatMessageItem {
  return {
    id: nextLocalMessageId(),
    sessionId: 0,
    senderId: 0,
    msgType: payload.msgType,
    content: payload.content,
    url: options?.url || null,
    createdAt: new Date().toISOString(),
    mine: true,
    localId: payload.localId,
    sendState: options?.sendState || "sending"
  };
}

export async function sendWithOutbox(
  context: ChatSendContext,
  payload: ChatSendPayload
): Promise<ChatSendResult> {
  const localId = payload.localId || createLocalId();
  const sessionId = context.sessionId;
  context.insertLocal({
    localId,
    content: payload.content,
    msgType: payload.msgType,
    sendState: "sending",
    attempts: payload.attempts
  });

  let attempts = payload.attempts;
  while (attempts <= MAX_RETRY_ATTEMPTS) {
    try {
      const options: SendChatMessageOptions = { msgType: payload.msgType, localId };
      const message = await sendChatMessage(sessionId, payload.content, options);
      if (message && message.localId) {
        // 服务端已按 localId 幂等去重，这个返回就是权威版本
        removeOutbox(sessionId, message.localId);
      }
      removeOutbox(sessionId, localId);
      return { ok: true, localId, message };
    } catch (error) {
      const failure = error instanceof Error ? error : new Error(String(error));
      attempts += 1;
      if (!isRetryableSendError(failure) || attempts > MAX_RETRY_ATTEMPTS) {
        upsertOutbox({
          localId,
          sessionId,
          content: payload.content,
          msgType: payload.msgType,
          attempts,
          updatedAt: nowMs()
        });
        return { ok: false, localId };
      }
      // 重试必须复用同一个 localId，靠服务端幂等去重，不会产生重复消息
      await delay(RETRY_BASE_DELAY_MS * attempts);
    }
  }

  upsertOutbox({
    localId,
    sessionId,
    content: payload.content,
    msgType: payload.msgType,
    attempts,
    updatedAt: nowMs()
  });
  return { ok: false, localId };
}

/** 手动重发：把 pending 队列里的原始内容取出来，重新走一遍带重试的发送 */
export async function retryOutboxEntry(
  context: ChatSendContext,
  localId: string
): Promise<ChatSendResult> {
  const entry: ChatOutboxEntry | null = getOutboxEntry(context.sessionId, localId);
  if (!entry) {
    return { ok: false, localId };
  }
  // attempts 归零：手动重发算新的一轮
  return sendWithOutbox(context, {
    localId: entry.localId,
    content: entry.content,
    msgType: entry.msgType,
    attempts: 0
  });
}

/**
 * 单个会话的已读上报计划器：
 * - 只有 maxSeq 比"已上报 + 待上报"更大时才需要发；
 * - 1 秒节流：窗口内连续出现的更大值合并成一次（发最后一次的 maxSeq）；
 * - hide/unload 立刻冲刷；
 * - 失败只重试一次（3 秒后），避免网络抖动丢掉已读位点，又不会无限打接口。
 */
export function createReadReporter(sessionId: number) {
  let target = 0;
  let reported = 0;
  let timer: ReturnType<typeof setTimeout> | null = null;
  let retryTimer: ReturnType<typeof setTimeout> | null = null;
  let running = false;

  function flush(): void {
    if (timer) {
      clearTimeout(timer);
      timer = null;
    }
    if (running || target <= reported || !sessionId) {
      return;
    }
    const seq = target;
    running = true;
    const done = () => {
      if (seq > reported) {
        reported = seq;
        setReadAt(sessionId, seq);
      }
    };
    const failed = () => {
      // 失败不回退游标：3 秒后补一次；仍失败就等下一个更大的 maxSeq
      if (retryTimer) {
        clearTimeout(retryTimer);
      }
      retryTimer = setTimeout(() => {
        retryTimer = null;
        flush();
      }, REPORT_RETRY_MS);
    };
    markChatRead(sessionId, seq)
      .then(done, failed)
      .then(() => {
        // 无论成败都先释放：否则补发的 flush 会被 running 挡住
        running = false;
      });
  }

  return {
    /** 返回 true 表示出现了新的最大 seqNo（调用方据此决定要不要刷未读角标） */
    note(maxSeq: number, options?: { immediate?: boolean }): boolean {
      const next = Number(maxSeq);
      if (!Number.isFinite(next) || next <= 0 || next <= Math.max(reported, target)) {
        return false;
      }
      target = Math.floor(next);
      if (options?.immediate) {
        flush();
        return true;
      }
      if (!timer && !running) {
        timer = setTimeout(() => {
          timer = null;
          flush();
        }, READ_THROTTLE_MS);
      }
      return true;
    },
    flush,
    reset(): void {
      target = 0;
      reported = 0;
      if (timer) {
        clearTimeout(timer);
        timer = null;
      }
      if (retryTimer) {
        clearTimeout(retryTimer);
        retryTimer = null;
      }
    }
  };
}

/** 兼容旧字段：没有 status/sendState 时退化成「已发送单勾」，不误报已读 */
export function outgoingStatus(message: ChatMessageItem): ChatOutgoingStatus {
  if (!message.mine) {
    return "";
  }
  if (message.sendState === "sending") {
    return "sending";
  }
  if (message.sendState === "failed") {
    return "failed";
  }
  if (message.msgType === "RECALL" || message.status === 2) {
    return "";
  }
  if (message.status === 3) {
    return "read";
  }
  return "sent";
}

/** 显示用短标签；样式由 chat.vue 的 class 控制 */
export function outgoingStatusLabel(status: ChatOutgoingStatus): string {
  if (status === "sending") {
    return "发送中";
  }
  if (status === "failed") {
    return "!";
  }
  if (status === "read") {
    return "✓✓";
  }
  return "✓";
}
