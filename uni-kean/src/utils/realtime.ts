import { chatAuthPayload, chatSocketConnectOptions } from "@/utils/ws";
import { handleAccountBanned } from "@/utils/request";
import { refreshMessageBadge } from "@/utils/messageBadge";
import { getToken } from "@/utils/storage";

export interface RealtimeEvent {
  type?: string;
  noticeType?: string;
  bizType?: string;
  bizId?: number;
  title?: string;
  content?: string;
  /** READ 事件（与 box-im 对齐后新增）：{ type:"READ", sessionId, maxSeq, readerId } */
  sessionId?: number;
  /** READ 事件的已读位点：对方已读到该会话的哪一条 seqNo */
  maxSeq?: number;
  /** READ 事件的读者；等于自己说明是别端上报，忽略即可 */
  readerId?: number;
  data?: {
    id?: number;
    sessionId?: number;
    readerId?: number;
    maxSeq?: number;
    [key: string]: unknown;
  };
}

type Listener = (event: RealtimeEvent) => void;

/** 连接看门狗：进入 connecting 后这么久还没 open，就认定这条连接已经死了。 */
const CONNECT_TIMEOUT_MS = 15000;
/** 退避重连间隔（依次取用，到底后停在最后一档 = 15s 一次，绝不疯狂重连）。 */
const RECONNECT_BACKOFF_MS = [1000, 2000, 4000, 8000, 15000];
/** 连续失败多久之后，即使 navigator.onLine 还报 false 也强行试一次（"飞行模式 / 代理判错"的兜底）。 */
const OFFLINE_PROBE_MS = 30000;

const listeners = new Set<Listener>();
let socket: UniApp.SocketTask | null = null;
let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
let connectTimer: ReturnType<typeof setTimeout> | null = null;
let connecting = false;
/** 当前这条连接是在什么时候开始握手的（看门狗判活 + connecting 状态打点用）。 */
let connectingSince = 0;
let reconnectAttempt = 0;
/** 网络层明确告诉我们离线了（H5 offline 事件）：此时不要盲目重连，等 online 事件。 */
let knownOffline = false;
/** 上一次"确实发起了连接尝试"的时间戳（用 Date.now()，不用定时器对象，免得踩到 Node Timeout 类型）。 */
let lastConnectAt = 0;
/** 已经被 onHide / 登出显式停过：online 事件不要把它"复活"。 */
let stopped = false;

/**
 * 当前 socket 是否"已握手成功"。
 *
 * <p>刻意不去读 {@code socket.readyState}：{@code @dcloudio/types} 的
 * {@code UniApp.SocketTask} 接口<b>没有声明</b> readyState（H5 运行时其实有，
 * 但类型里没有 —— 读了就是 ``vue-tsc`` 报错）。所以这里以"有没有 socket + 在不在握手"
 * 这个状态机为准，readyState 只在日志里出现，且用 in 收窄后再读。</p>
 */
function isOpen(task: UniApp.SocketTask | null = socket): boolean {
  return Boolean(task) && !connecting;
}

/** 只在日志里读 readyState（类型里没有这个字段，先收窄再用）。 */
function socketReadyState(task: UniApp.SocketTask | null = socket): number | string {
  if (!task) {
    return "none";
  }
  if (!("readyState" in task)) {
    return "unknown";
  }
  const state = (task as UniApp.SocketTask & { readyState?: number }).readyState;
  return typeof state === "number" ? state : "unknown";
}

export function onRealtime(listener: Listener) {
  listeners.add(listener);
  // [kean-rt] 订阅数：0 说明页面压根没注册（实时通道断线的第一现场）
  console.log("[kean-rt] subscribe", { listeners: listeners.size });
  return () => {
    listeners.delete(listener);
    console.log("[kean-rt] unsubscribe", { listeners: listeners.size });
  };
}

export function startRealtime() {
  stopped = false;
  if (!getToken()) {
    console.log("[kean-rt] start skipped:no-token");
    stopRealtime();
    return;
  }
  // 明确离线：不发起握手（握手必然超时、还白等 15s 看门狗）。online 事件 / 兜底探测会把它拉回来。
  if (knownOffline && Date.now() - lastConnectAt < OFFLINE_PROBE_MS) {
    console.log("[kean-rt] start skipped:offline", { sinceLastTryMs: Date.now() - lastConnectAt });
    return;
  }
  // 只有"确实已经握手成功"的连接才值得跳过。
  // 旧实现是 `if (socket || connecting) return;` —— socket 只要非空就永久跳过，
  // 于是"socket 在、但没连上"的残骸会把后续每一次 startRealtime 都吃掉（线上 skip:already 的来源）。
  if (isOpen()) {
    console.log("[kean-rt] start skipped:already", { socket: true, connecting: false, readyState: socketReadyState() });
    return;
  }
  if (connecting) {
    if (Date.now() - connectingSince < CONNECT_TIMEOUT_MS) {
      console.log("[kean-rt] start skipped:already", {
        socket: Boolean(socket),
        connecting: true,
        heldMs: Date.now() - connectingSince
      });
      return;
    }
    // 卡过看门狗阈值了：这里不自己重连，立刻掐掉死连接，让看门狗那条路统一收口（close → 复位 → 重连）。
    console.warn("[kean-rt] connect timeout, retry", { source: "start", heldMs: Date.now() - connectingSince });
    hardReset();
    return;
  }
  // socket 残骸：既没连上、又没在连 —— 直接丢弃它，重新发起。
  if (socket) {
    console.warn("[kean-rt] stale socket dropped", { readyState: socketReadyState() });
    hardReset();
  }
  console.log("[kean-rt] start");
  connect();
}

export function stopRealtime() {
  stopped = true;
  clearConnectTimer();
  connecting = false;
  connectingSince = 0;
  resetBackoff();
  if (reconnectTimer) {
    clearTimeout(reconnectTimer);
    reconnectTimer = null;
  }
  if (socket) {
    try {
      socket.close({});
    } catch {
      // ignore
    }
    socket = null;
  }
}

/** 释放当前连接与所有定时器，但保留 stopped 语义（由调用方决定是否再连）。 */
function hardReset() {
  clearConnectTimer();
  connecting = false;
  connectingSince = 0;
  if (socket) {
    try {
      socket.close({});
    } catch {
      // ignore
    }
    socket = null;
  }
}

function clearConnectTimer() {
  if (connectTimer) {
    clearTimeout(connectTimer);
    connectTimer = null;
  }
}

function resetBackoff() {
  reconnectAttempt = 0;
}

/** 连接成功：退避计数清零，下次断线从 1s 重新起步。 */
function noteOpened() {
  resetBackoff();
}

function connect() {
  if (!getToken() || connecting || isOpen()) {
    return;
  }
  // 这里必须丢弃残骸再连：绝对不能让两条 socket 同时存在（旧 socket 的回调会把新连接的状态改坏）。
  if (socket) {
    hardReset();
  }
  connecting = true;
  connectingSince = Date.now();
  lastConnectAt = connectingSince;

  const task = uni.connectSocket(chatSocketConnectOptions());
  socket = task;

  // 看门狗：socket 进 connecting 后既不开、也不关、也不报错是 H5 断网时的常态
  // （onError/onClose 都可能不触发），没有它 connecting 会一直是 true —— 这正是线上那个 bug。
  connectTimer = setTimeout(() => {
    connectTimer = null;
    // 这条连接已经被换掉 / 已经不在握手了 → 不是我的活，直接退出。
    if (socket !== task || !connecting) {
      return;
    }
    const heldMs = Date.now() - connectingSince;
    console.warn("[kean-rt] connect timeout, retry", { heldMs, readyState: socketReadyState(task) });
    hardReset();
    scheduleReconnect();
  }, CONNECT_TIMEOUT_MS);

  task.onOpen(() => {
    clearConnectTimer();
    const afterMs = Date.now() - connectingSince;
    connecting = false;
    connectingSince = 0;
    noteOpened();
    console.log("[kean-rt] open", { afterMs });
    task.send({ data: chatAuthPayload() });
  });
  task.onMessage((res) => {
    try {
      const event = JSON.parse(String(res.data || "{}")) as RealtimeEvent;
      console.log("[kean-rt] recv", { type: event.type, sessionId: event.sessionId, maxSeq: event.maxSeq });
      dispatch(event);
    } catch {
      // ignore
    }
  });
  task.onClose(() => {
    clearConnectTimer();
    const heldMs = Date.now() - connectingSince;
    connecting = false;
    connectingSince = 0;
    console.log("[kean-rt] close", { heldMs });
    if (socket === task) {
      socket = null;
    }
    scheduleReconnect();
  });
  task.onError((error) => {
    clearConnectTimer();
    const heldMs = Date.now() - connectingSince;
    connecting = false;
    connectingSince = 0;
    console.log("[kean-rt] error", { error: String((error as { errMsg?: string })?.errMsg || error), heldMs });
    // 关键：onError 之后 socket 已经不可能再 open 了，必须连同 socket 一起丢掉。
    // 旧实现只复位 connecting、留着一个死 socket —— 下一轮 startRealtime 又命中 `if (socket) return`，
    // 而且 onError 不排重连，于是这条连接再也没有人管（"永久放弃"）。
    if (socket === task) {
      socket = null;
      scheduleReconnect();
    }
  });
}

function scheduleReconnect() {
  if (!getToken() || reconnectTimer) {
    return;
  }
  if (isOpen()) {
    return;
  }
  if (connecting) {
    // 正在连接中，看门狗会负责超时；这里不叠一个定时器。
    return;
  }
  if (knownOffline) {
    // 离线：不空转。等 H5 的 online 事件；如果连 online 事件都没等到，
    // startRealtime 里的 OFFLINE_PROBE_MS 兜底探测会在 30s 后强行试一次。
    console.log("[kean-rt] reconnect paused:offline");
    return;
  }
  const delay = RECONNECT_BACKOFF_MS[Math.min(reconnectAttempt, RECONNECT_BACKOFF_MS.length - 1)];
  reconnectAttempt += 1;
  console.log("[kean-rt] reconnect scheduled", { delayMs: delay, attempt: reconnectAttempt });
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null;
    connect();
  }, delay);
}

/**
 * H5 网络自愈：offline 时停下空转重连，online 时立刻恢复。
 *
 * <p>没有这层的话，"断网期间 socket 静默卡死"只能靠 15s 看门狗 + 10s 心跳慢慢爬回来。</p>
 */
function watchOnline() {
  // #ifdef H5
  if (typeof window === "undefined" || typeof window.addEventListener !== "function") {
    return;
  }
  window.addEventListener("offline", () => {
    knownOffline = true;
    console.log("[kean-rt] network offline", { socket: Boolean(socket), connecting, readyState: socketReadyState() });
    // 已断网：正在握手的这条基本没救了，立刻掐掉并进入"暂停重连"状态（硬复位会复位 connecting）。
    if (connecting && socket) {
      hardReset();
    }
    if (reconnectTimer) {
      clearTimeout(reconnectTimer);
      reconnectTimer = null;
    }
  });
  window.addEventListener("online", () => {
    knownOffline = false;
    const heldMs = connecting ? Date.now() - connectingSince : 0;
    const readyState = socketReadyState();
    console.log("[kean-rt] network online", { holding: connecting, readyState, heldMs });
    if (isOpen()) {
      // 已经连着：什么都不做，别把好连接踢掉。
      resetBackoff();
      return;
    }
    if (stopped || !getToken()) {
      return;
    }
    // 无论它是"还在握手"还是"残骸"，一律丢掉重来：这条连接已经跨过一次断网，不能信。
    hardReset();
    if (reconnectTimer) {
      clearTimeout(reconnectTimer);
      reconnectTimer = null;
    }
    resetBackoff();
    connect();
  });
  // #endif
}

watchOnline();

function dispatch(event: RealtimeEvent) {
  // [kean-rt] 分发：看「收包有、但页面没反应」是不是发生在订阅数=0
  console.log("[kean-rt] dispatch", { type: event.type, listeners: listeners.size });
  if (event.type === "BANNED") {
    handleAccountBanned(event.content);
    return;
  }
  // READ 只影响「我发出的消息是否已读」，不改变我的未读数：
  // 这里不刷新角标，避免每次对方读消息都多打一轮未读统计。
  if (event.type === "NOTICE" || event.type === "MESSAGE") {
    refreshMessageBadge();
  }
  listeners.forEach((listener) => listener(event));
}
