import { getImToken, type ImToken } from "@/api/im";
import { isImEnabled } from "@/utils/imFlag";
import { resolveApiBase } from "@/utils/apiBase";

/**
 * box-im 协议的 WebSocket 客户端（阶段 D2 新增，<b>默认不启用</b>）。
 *
 * <h2>这一层刻意不做的事</h2>
 * <p>只负责“连接 / 登录 / 心跳 / 重连 / 事件分发”，<b>不碰</b>任何业务状态：
 * 不写 store、不刷新消息角标、不弹窗、不导航。业务接入留给下一阶段，
 * 这样本轮新增代码即使被误开也不会改变现有页面行为。</p>
 *
 * <h2>与现有 realtime.ts 的关系</h2>
 * <p>完全独立、互不 import：{@code realtime.ts} 连的是课安自己的
 * {@code ws://<kean-host>/ws/chat}（协议是 {@code {type:"AUTH"}} + {@code {type:"PING"}}），
 * 本文件连的是 box-im im-server 的 {@code ws://<im-host>:8878/ws}
 * （协议是 {@code {cmd:0}} 登录 + {@code {cmd:1}} 心跳）。两者可以并存。</p>
 *
 * <h2>协议来源</h2>
 * <p>照抄 box-im {@code im-uniapp/common/wssocket.js} 与 {@code im-common} 的
 * {@code IMCmdType} / {@code IMLoginInfo} / {@code IMSendInfo}：</p>
 * <ul>
 *   <li>上行登录：{@code {cmd:0, data:{accessToken, devId}}}</li>
 *   <li>上行心跳：{@code {cmd:1, data:{}}}，间隔 20s（与 box-im 一致）</li>
 *   <li>下行：{@code {cmd, data}}，cmd 0..5</li>
 * </ul>
 */

/** box-im {@code IMCmdType}。 */
export const IMCmd = {
  LOGIN: 0,
  HEART_BEAT: 1,
  FORCE_LOGOUT: 2,
  PRIVATE_MESSAGE: 3,
  GROUP_MESSAGE: 4,
  SYSTEM_MESSAGE: 5
} as const;

/** 连接状态变化时回调。 */
export type ImState = "CONNECTING" | "ONLINE" | "OFFLINE" | "DISABLED";

/** 业务消息回调：cmd 与 data 原样来自 im-server 的 {@code IMSendInfo}。 */
export type ImMessageHandler = (cmd: number, data: unknown) => void;

export interface ImSocketEvent {
  onState?: (state: ImState) => void;
  onMessage?: ImMessageHandler;
  onError?: (error: unknown) => void;
}

/** 与 box-im 客户端一致的收包结构。 */
interface ImSendInfo {
  cmd?: number;
  data?: unknown;
}

/**
 * 心跳间隔。box-im 客户端同样是 20000ms；im-server 侧
 * {@code IMChannelHandler} 的 READER_IDLE 超时按这个量级设置，
 * 不要为了“更灵敏”而调小或调大。
 */
const HEARTBEAT_INTERVAL_MS = 20000;

/** 断线重连最小间隔，与 box-im 客户端的 10s 策略一致。 */
const RECONNECT_MIN_DELAY_MS = 10000;

/**
 * 每次发起连接前，两次尝试之间的最小间隔。box-im 客户端用
 * {@code lastConnectTime} 做同样的节流，避免服务端不可用时打出重连风暴。
 */
const CONNECT_THROTTLE_MS = 10000;

/** 本设备标识。box-im 客户端用随机数；这里优先用运行期生成的稳定随机串。 */
const DEV_ID = `kean-${Math.floor(Math.random() * 1000000)}`;

let socket: UniApp.SocketTask | null = null;
let state: ImState = "DISABLED";
let heartbeatTimer: ReturnType<typeof setTimeout> | null = null;
let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
let lastConnectAt = 0;
let stopped = true;
let accessToken = "";
/** accessToken 的绝对过期时间（epoch 毫秒），来自 {@code GET /api/im/token} 的 {@code expireAt}。 */
let accessTokenExpireAt = 0;
let events: ImSocketEvent = {};

/**
 * im-server 的 WebSocket 地址。
 *
 * <p>优先读 {@code VITE_IM_WS_URL}；没配时退回 {@code VITE_IM_BASE_URL} + {@code /im}。
 * 两者都没有就返回空串，{@link connect} 会据此直接放弃并报一次错，而不是连到一个瞎猜的地址。</p>
 *
 * <p><b>路径是 {@code /im} 而不是 {@code /ws}</b>：box-im 的 im-server 用 Netty 起 WS，
 * {@code WebSocketServer} 里写死了 {@code new WebSocketServerProtocolHandler("/im")}，
 * 官方 {@code im-uniapp/.env.js} 也印证了这一点
 * （{@code UNI_APP.WS_URL = "ws://127.0.0.1:8878/im"}）。写成 {@code /ws} 会握手 404。</p>
 *
 * <p><b>注意</b>：这里刻意<b>不</b>从 {@code resolveApiBase()} 派生 —— im-server 与 kean 后端
 * 是不同端口（8878 vs 8080）的两个进程，按 API 地址推导必然连错。{@code resolveApiBase}
 * 只用于第 1 步取 token 的 HTTP 调用。</p>
 */
export function resolveImWsUrl(): string {
  const explicit = String(import.meta.env.VITE_IM_WS_URL || "").trim();
  if (explicit) {
    return explicit;
  }
  const base = String(import.meta.env.VITE_IM_BASE_URL || "").trim().replace(/\/$/, "");
  if (!base) {
    return "";
  }
  // 允许把 http(s):// 或 ws(s):// 写进 VITE_IM_BASE_URL，统一归一化成 ws(s)。
  // 路径必须是 /im（im-server 的 WebSocketServerProtocolHandler("/im")），不是 /ws。
  const ws = base.replace(/^http/i, "ws");
  return `${ws}/im`;
}

/** 当前是否已登录到 im-server。 */
export function isConnected(): boolean {
  return state === "ONLINE";
}

/** 当前状态。 */
export function getImState(): ImState {
  return state;
}

/** 注册事件回调（整体替换，多次调用以后一次为准）。 */
export function on(handlers: ImSocketEvent): void {
  events = handlers || {};
}

/** 清空事件回调。 */
export function off(): void {
  events = {};
}

/**
 * 建立连接。开关关闭时<b>立即返回</b>，不做任何事。
 *
 * <p>这是“默认关时现有功能零变化”的关键：未开启 IM 的构建产物里，
 * 本函数只是一个 {@code if (!enabled) return;}。</p>
 */
export async function connect(handlers?: ImSocketEvent): Promise<void> {
  if (handlers) {
    on(handlers);
  }
  if (!isImEnabled()) {
    emitState("DISABLED");
    return;
  }
  stopped = false;
  if (socket || state === "CONNECTING" || state === "ONLINE") {
    return;
  }
  const url = resolveImWsUrl();
  if (!url) {
    emitError(new Error("未配置 VITE_IM_WS_URL / VITE_IM_BASE_URL，IM 通道无法连接"));
    return;
  }
  if (!(await ensureToken())) {
    return;
  }
  open(url);
}

/**
 * 确保手上有一个仍在有效期内的 IM accessToken。
 *
 * <p>token 有效期只有 1800s（box-im 的 {@code accessToken.expireIn}），而 uni-app 的前后台
 * 切换会让连接跨越很长时间，因此在<b>每次发起连接（含重连）</b>前检查一次过期时间，
 * 过期就重新取票 —— 否则表现是“重连成功但立刻被 im-server 关闭”，很难排查。</p>
 *
 * @return 是否有可用的 token
 */
async function ensureToken(): Promise<boolean> {
  // 留 60s 余量，避免刚连上就过期。
  if (accessToken && Date.now() + 60000 < accessTokenExpireAt) {
    return true;
  }
  accessToken = "";
  accessTokenExpireAt = 0;
  try {
    const token: ImToken = await getImToken();
    if (!token?.enabled || !token.accessToken) {
      // 后端没配 IM_JWT_SECRET：静默保持关闭，不打扰用户（与后端“只 WARN 不报错”一致）。
      emitState("DISABLED");
      return false;
    }
    accessToken = token.accessToken;
    accessTokenExpireAt = typeof token.expireAt === "number" ? token.expireAt : Date.now() + 1800_000;
    return true;
  } catch (error) {
    emitError(error);
    return false;
  }
}

/** 主动断开并停止重连。 */
export function disconnect(): void {
  stopped = true;
  clearHeartbeat();
  clearReconnect();
  const task = socket;
  socket = null;
  if (task) {
    try {
      task.close({ code: 1000 });
    } catch {
      // ignore
    }
  }
  emitState(isImEnabled() ? "OFFLINE" : "DISABLED");
}

/** 清空缓存的 token（例如登出、或课安 token 轮换后需要重新取）。 */
export function resetImToken(): void {
  accessToken = "";
  accessTokenExpireAt = 0;
}

function open(url: string): void {
  const elapsed = Date.now() - lastConnectAt;
  if (lastConnectAt > 0 && elapsed < CONNECT_THROTTLE_MS) {
    // 与 box-im 客户端一致：不足节流窗口就推迟，而不是立刻再连。
    scheduleReconnect(CONNECT_THROTTLE_MS - elapsed);
    return;
  }
  lastConnectAt = Date.now();
  emitState("CONNECTING");

  const task = uni.connectSocket({ url, complete: () => undefined });
  socket = task;

  task.onOpen(() => {
    // 与 box-im 一致：连上后先发 {cmd:0} 登录帧，等服务端回 cmd:0 才算真正在线。
    sendFrame({ cmd: IMCmd.LOGIN, data: { accessToken, devId: DEV_ID } });
  });

  task.onMessage((res) => handleMessage(res?.data));

  task.onClose(() => {
    if (socket === task) {
      socket = null;
    }
    clearHeartbeat();
    emitState("OFFLINE");
    scheduleReconnect();
  });

  task.onError((error) => {
    socket = null;
    clearHeartbeat();
    emitState("OFFLINE");
    emitError(error);
    scheduleReconnect();
  });
}

function handleMessage(raw: unknown): void {
  let frame: ImSendInfo;
  try {
    frame = JSON.parse(String(raw ?? "{}")) as ImSendInfo;
  } catch {
    return;
  }
  const cmd = typeof frame.cmd === "number" ? frame.cmd : -1;
  if (cmd === IMCmd.LOGIN) {
    // 登录成功：开心跳、置为在线（顺序与 box-im 的 wssocket.js 一致）。
    emitState("ONLINE");
    heartbeatTimer = setTimeout(heartbeat, HEARTBEAT_INTERVAL_MS);
    return;
  }
  if (cmd === IMCmd.HEART_BEAT) {
    // 收到心跳回包才安排下一次；服务端不回就不再发，可自然暴露半开连接。
    clearHeartbeat();
    heartbeatTimer = setTimeout(heartbeat, HEARTBEAT_INTERVAL_MS);
    return;
  }
  // cmd 2..5（强制下线 / 私聊 / 群聊 / 系统）原样交给上层，本文件不解释业务含义。
  if (events.onMessage) {
    events.onMessage(cmd, frame.data);
  }
}

function heartbeat(): void {
  if (state !== "ONLINE") {
    return;
  }
  sendFrame({ cmd: IMCmd.HEART_BEAT, data: {} });
}

function sendFrame(frame: { cmd: number; data: unknown }): void {
  const task = socket;
  if (!task) {
    return;
  }
  try {
    task.send({ data: JSON.stringify(frame) });
  } catch (error) {
    emitError(error);
  }
}

function scheduleReconnect(delay = RECONNECT_MIN_DELAY_MS): void {
  if (stopped || !isImEnabled() || reconnectTimer) {
    return;
  }
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null;
    if (stopped || !isImEnabled()) {
      return;
    }
    connect().catch(() => undefined);
  }, Math.max(0, delay));
}

function clearHeartbeat(): void {
  if (heartbeatTimer) {
    clearTimeout(heartbeatTimer);
    heartbeatTimer = null;
  }
}

function clearReconnect(): void {
  if (reconnectTimer) {
    clearTimeout(reconnectTimer);
    reconnectTimer = null;
  }
}

function emitState(next: ImState): void {
  state = next;
  events.onState?.(next);
}

function emitError(error: unknown): void {
  events.onError?.(error);
}

/**
 * 供调试/自检：当前解析出的 ws 地址与 HTTP 取票地址。
 *
 * <p>{@code resolveApiBase()} 只在这里被读到，用于确认“取 token 走 kean、连 ws 走 im-server”
 * 两个地址没有搞混。</p>
 */
export function describeImEndpoints(): { wsUrl: string; tokenUrl: string } {
  const apiBase = resolveApiBase();
  return {
    wsUrl: resolveImWsUrl(),
    tokenUrl: `${apiBase}/api/im/token`
  };
}
