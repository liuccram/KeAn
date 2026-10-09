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

const listeners = new Set<Listener>();
let socket: UniApp.SocketTask | null = null;
let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
let connecting = false;

export function onRealtime(listener: Listener) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function startRealtime() {
  if (!getToken()) {
    stopRealtime();
    return;
  }
  if (socket || connecting) {
    return;
  }
  connect();
}

export function stopRealtime() {
  connecting = false;
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

function connect() {
  if (!getToken() || socket || connecting) {
    return;
  }
  connecting = true;
  const task = uni.connectSocket(chatSocketConnectOptions());
  socket = task;
  task.onOpen(() => {
    connecting = false;
    task.send({ data: chatAuthPayload() });
  });
  task.onMessage((res) => {
    try {
      const event = JSON.parse(String(res.data || "{}")) as RealtimeEvent;
      dispatch(event);
    } catch {
      // ignore
    }
  });
  task.onClose(() => {
    connecting = false;
    if (socket === task) {
      socket = null;
    }
    scheduleReconnect();
  });
  task.onError(() => {
    connecting = false;
  });
}

function scheduleReconnect() {
  if (!getToken() || reconnectTimer) {
    return;
  }
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null;
    connect();
  }, 3000);
}

function dispatch(event: RealtimeEvent) {
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
