/**
 * 私信收发与已读回执的前端状态机。
 *
 * 发送（localId 幂等 + pending 队列）：
 *   乐观气泡（发送中，按下发送即出现，永不删除）
 *                  --成功--> sent（用服务端返回的消息按 localId 替换本地那条，pending 出队）
 *                  --失败/超时--> attempts+1，退避 400ms/800ms 重试，重试始终带同一个 localId
 *                  --重试仍失败/兜底超时--> failed（红色感叹号，长按/点击手动重发）
 *                  --不可重试错误--> 直接 failed（不做无谓重试）
 *
 * 已读：只有「当前已显示的最大 seqNo 变大」才触发一次 READ 上报，1 秒节流合并；
 * 收到 READ 事件时把「我发出的、seqNo ≤ maxSeq」的消息标为已读（气泡双勾）。
 *
 * 状态角标的口径（{@link outgoingStatus}）：
 *   发送中 / ! 失败 / ✓ 已发送（status 1 或缺省）/ ✓✓ 已读（status 3）/ 撤回不显示。
 *   只有「本地乐观且服务端还没给 seqNo」时才可能显示发送中或失败 ——
 *   服务端一旦回了 seqNo 就说明这条已经落库，绝不能再被本地标记压住对勾。
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
// 只借用既有的单请求超时值来推导兜底时限，不另造一套超时口径
import { REQUEST_TIMEOUT_MS } from "@/utils/request";

/** 首个请求之后再重试的次数：一次发送最多 3 次网络尝试 */
const MAX_RETRY_ATTEMPTS = 2;
const RETRY_BASE_DELAY_MS = 400;
/**
 * 一次发送的总兜底时限（仅安全网，不是业务逻辑）：
 * 单个请求的超时由 {@link REQUEST_TIMEOUT_MS}（request.ts，15s）负责；
 * 这里只防「平台既不回调 success 也不回调 fail」（H5 断网时的极端表现），
 * 那样 <b>发送按钮的 loading 会永远转下去</b>。到点后按普通发送失败处理
 * （去重仍靠 localId 幂等，不会因为超时判定产生重复消息）。
 */
const SEND_FALLBACK_DEADLINE_MS = REQUEST_TIMEOUT_MS * 2 + 2000;
/** 已读上报节流窗口：窗口内只发最后一次（最新的 maxSeq） */
const READ_THROTTLE_MS = 1000;
/** 已读上报失败后的补发延迟 */
const REPORT_RETRY_MS = 3000;

/** 我发出的消息的本地可读状态（"" = 不显示角标） */
export type ChatOutgoingStatus = ChatSendState | "sent" | "read" | "";

export interface ChatSendContext {
  sessionId: number;
  /**
   * 乐观插入本地「发送中」消息，返回它的临时 id。
   * 必须同步执行、必须幂等（同一个 localId 不重复插入）：
   * 这是「按下发送气泡立刻可见」的唯一入口。
   */
  insertLocal(input: {
    localId: string;
    content: string;
    msgType: string;
    sendState: ChatSendState;
    attempts: number;
  }): number;
  /**
   * 服务端确认落库、但没有回消息体（老后端）：把原始内容也写进本地方便重发。
   * 可选，页面用它补一份 outbox 内容。
   */
  onOutbox?: (entry: ChatOutboxEntry) => void;
}

export interface ChatSendPayload {
  localId?: string;
  content: string;
  msgType: string;
  attempts: number;
}

/**
 * 页面渲染用的消息视图：在 {@link ChatMessageItem} 上补两个纯展示字段。
 * （chat.vue 一直从这里引入这个类型，之前漏了导出，顺手补上，不影响运行时。）
 */
export type ChatDisplayMessage = ChatMessageItem & {
  /** 是否我发出的（后端 mine 缺失时按 senderId 兜底） */
  mine: boolean;
  /** 相邻消息之间是否要显示时间戳 */
  showTime?: boolean;
};

export interface ChatSendResult {
  ok: boolean;
  localId: string;
  message?: ChatMessageItem;
  /**
   * 本地乐观气泡是否真的插进列表了。
   * false = 页面没能显示这条消息，调用方不能再把它标成「失败可重发」（那会是一条隐形的失败）。
   */
  inserted: boolean;
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

/** 兜底超时的错误文案（也是识别标记，见 {@link isSendTimeout}） */
const SEND_TIMEOUT_CODE = "chat-send-fallback-timeout";

class SendTimeoutError extends Error {
  constructor() {
    super(SEND_TIMEOUT_CODE);
    this.name = "SendTimeoutError";
  }
}

/** 用标记位而不是只用 instanceof：uni-app 编译到 ES5 时继承内置 Error 会让 instanceof 失效 */
function isSendTimeout(error: unknown): boolean {
  return (
    error instanceof SendTimeoutError ||
    (error instanceof Error && error.message === SEND_TIMEOUT_CODE && error.name === "SendTimeoutError")
  );
}

/**
 * 让「一次发送尝试」在有限时间内必定结束。
 *
 * <p>包装而不是替换 request.ts 的超时：正常路径上 reject 先到，这里原样透传；
 * 只有平台既不回调 success 也不回调 fail（H5 断网时可能出现的极端表现）时，
 * 才由兜底时限结束等待 —— 否则发送按钮会一直转圈，后续点击还会被 busy 守卫挡掉
 * （「只转圈、不出气泡」的成因之一）。</p>
 */
async function sendWithDeadline<T>(promise: Promise<T>): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | null = null;
  const deadline = new Promise<never>((_, reject) => {
    timer = setTimeout(() => reject(new SendTimeoutError()), SEND_FALLBACK_DEADLINE_MS);
  });
  try {
    return await Promise.race([promise, deadline]);
  } finally {
    if (timer) {
      clearTimeout(timer);
    }
  }
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
  // ① 乐观插入永远发生在任何网络等待之前：气泡先出现（发送中），再谈成功/失败
  const inserted = Boolean(
    context.insertLocal({
      localId,
      content: payload.content,
      msgType: payload.msgType,
      sendState: "sending",
      attempts: payload.attempts
    })
  );

  let attempts = payload.attempts;
  let exhausted = false;
  console.log("[kean-send] sendWithOutbox.enter", { localId, sessionId, msgType: payload.msgType, inserted, attempts });
  while (attempts <= MAX_RETRY_ATTEMPTS && !exhausted) {
      console.log("[kean-send] sendWithOutbox.attempt", { localId, attempt: attempts + 1 });
      try {
        const options: SendChatMessageOptions = { msgType: payload.msgType, localId };
        const message = await sendWithDeadline(sendChatMessage(sessionId, payload.content, options));
        // 这条已经成功，重发队列里必须清干净（不管服务端回的是哪个 localId 口径）
        removeOutbox(sessionId, message?.localId);
        removeOutbox(sessionId, localId);
        console.log("[kean-send] sendWithOutbox.return", {
          localId,
          ok: true,
          hasMessage: Boolean(message),
          id: message?.id,
          seqNo: message?.seqNo,
          attempts: attempts + 1
        });
        return { ok: true, localId, message, inserted };
      } catch (error) {
        const failure = error instanceof Error ? error : new Error(String(error));
        attempts += 1;
        // 兜底超时按「可重试的网络失败」对待；
        // 其余按 request.ts 的既有文案判定：不可重试（未登录/无权限/内容不合规）直接进失败态
        const retryable = isSendTimeout(failure) || isRetryableSendError(failure);
        exhausted = !retryable || attempts > MAX_RETRY_ATTEMPTS;
        console.log("[kean-send] sendWithOutbox.catch", {
          localId,
          attempts,
          retryable,
          exhausted,
          error: failure.message
        });
      }
      if (!exhausted) {
        // 重试必须复用同一个 localId，靠服务端幂等去重，不会产生重复消息
        await delay(RETRY_BASE_DELAY_MS * attempts);
      }
    }

    // 重试用尽：落盘待重发内容 + 返回失败，由页面把那条本地消息标成红色感叹号（永不删除）
    const entry: ChatOutboxEntry = {
      localId,
      sessionId,
      content: payload.content,
      msgType: payload.msgType,
      attempts,
      updatedAt: nowMs()
    };
    upsertOutbox(entry);
    // 同步告诉页面原始内容：服务端若「落库了但没回消息体」，界面也能照常重发
    context.onOutbox?.(entry);
    console.log("[kean-send] sendWithOutbox.return", { localId, ok: false, inserted, attempts });
    return { ok: false, localId, inserted };
}

/** 手动重发：把 pending 队列里的原始内容取出来，重新走一遍带重试的发送 */
export async function retryOutboxEntry(
  context: ChatSendContext,
  localId: string
): Promise<ChatSendResult> {
  const entry: ChatOutboxEntry | null = getOutboxEntry(context.sessionId, localId);
  if (!entry) {
    return { ok: false, localId, inserted: false };
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
 *
 * 注意：{@code sessionId} 支持传一个取值函数。页面里 reporter 通常在 setup 阶段就建好了，
 * 那时会话 id 还是 0，直到 {@code onLoad} 才从 query 里解析出来 —— 用函数延迟取值，
 * 否则 {@code !sessionId} 会把每一次 flush 都挡在发请求之前。
 */
export function createReadReporter(
  sessionId: number | (() => number),
  options?: { onRead?: (maxSeq: number) => void }
) {
  /** 每次都重新取值：会话 id 在 setup 之后才确定 */
  const currentSessionId = () => (typeof sessionId === "function" ? sessionId() : sessionId);
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
    const id = currentSessionId();
    if (running) {
      // 有请求在飞：等它落地再发，避免这次更大的 target 一直卡到下一个事件
      if (target > reported && !timer) {
        timer = setTimeout(() => {
          timer = null;
          flush();
        }, READ_THROTTLE_MS);
      }
      return;
    }
    if (target <= reported || !id) {
      return;
    }
    const seq = target;
    running = true;
    const done = () => {
      if (seq > reported) {
        reported = seq;
        setReadAt(id, seq);
      }
      // 已读位点确实推上去了：调用方拿它决定要不要重算未读角标
      options?.onRead?.(seq);
    };
    const failed = (error?: unknown) => {
      // 上报失败必须留痕：否则「未读数不清零 / 没有双勾」在 H5 上完全不可见
      console.warn("[chatRead] 已读上报失败", { sessionId: id, maxSeq: seq, error });
      // 失败不回退游标：3 秒后补一次；仍失败就等下一个更大的 maxSeq
      if (retryTimer) {
        clearTimeout(retryTimer);
      }
      retryTimer = setTimeout(() => {
        retryTimer = null;
        flush();
      }, REPORT_RETRY_MS);
    };
    markChatRead(id, seq)
      .then(done, failed)
      .then(() => {
        // 无论成败都先释放：否则补发的 flush 会被 running 挡住
        running = false;
      });
  }

  /**
   * 老口径兜底：一个 seqNo 都拿不到时（老后端 / 序号字段没解析出来）不带 maxSeq 调用。
   * 服务端这条分支只清自己那侧未读数（不推 READ 事件），语义上不会把「读到第 0 条」写进位点。
   */
  function flushUnscoped(): void {
    const id = currentSessionId();
    if (running || reported > 0 || !id) {
      return;
    }
    running = true;
    console.warn("[chatRead] 无可用 seqNo，退化为不带 maxSeq 的已读上报", { sessionId: id });
    markChatRead(id)
      .then(
        () => {
          options?.onRead?.(0);
        },
        (error?: unknown) => {
          console.warn("[chatRead] 已读上报失败（无 maxSeq 兜底）", { sessionId: id, error });
        }
      )
      .then(() => {
        running = false;
      });
  }

  return {
    /** 返回 true 表示出现了新的最大 seqNo（进队；真正是否发出由节流/flush 决定） */
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
      // 有请求在飞时也要排一个定时器：否则这次更大的 target 会一直卡到下一个事件才发出去
      if (!timer) {
        timer = setTimeout(() => {
          timer = null;
          flush();
        }, READ_THROTTLE_MS);
      }
      return true;
    },
    flush,
    /** 进会话时调用一次：拿不到任何 seqNo 就走老口径，至少让未读数能清零 */
    flushUnscoped,
    reset(): void {
      target = 0;
      // 内存游标按页面实例重新开始：同一个页面生命周期内不会重复上报同一个 maxSeq；
      // 不复用 chatStore 的 readAt —— 那个键在 chat.vue 里还会被写入 createdAt 毫秒值，
      // 拿它当"已上报 seqNo"会把真实的小 seqNo 全部判成"已上报"而永不发送。
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
  // 服务端已经给了 seqNo 说明这条消息确实落库并被确认：本地那个"发送中"标记
  // （乐观条目没被回执替换掉、或替换时机晚了）不该再压住状态角标 —— 否则
  // 失败/成功都算不出来，还会盖住后面的单/双勾。
  const confirmed = Number(message.seqNo || 0) > 0;
  if (message.sendState === "sending" && !confirmed) {
    return "sending";
  }
  if (message.sendState === "failed" && !confirmed) {
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
