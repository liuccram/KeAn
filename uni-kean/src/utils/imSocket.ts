import { getImToken, type ImToken } from "@/api/im";
import { isImEnabled } from "@/utils/imFlag";
import { resolveApiBase } from "@/utils/apiBase";
import type { RealtimeEvent } from "@/utils/realtime";
import { handleSessionEnded } from "@/utils/request";

/**
 * box-im 协议的 WebSocket 客户端（阶段 D2 新增，<b>默认不启用</b>；阶段 3 补事件映射）。
 *
 * <h2>这一层刻意不做的事</h2>
 * <p>只负责“连接 / 登录 / 心跳 / 重连 / 事件分发 / <b>帧 → RealtimeEvent 映射</b>”，
 * <b>不碰</b>任何业务状态：不写 store、不刷新消息角标、不弹窗、不导航。
 * 唯一的例外是 {@code cmd 2}（强制下线）：它语义上就是「本端必须退出」，
 * 走的是课安既有的 {@code handleSessionEnded}（清登录态 + 弹窗 + 回登录页），
 * 不引入任何新的登出路径。业务接入（未读角标等）由
 * {@code composables/useLiveUpdates.ts} 负责，本文件保持可单独测试。</p>
 *
 * <h2>与现有 realtime.ts 的关系</h2>
 * <p>两条通道完全独立、{@code realtime.ts} 一行未改：它连的是课安自己的
 * {@code ws://<kean-host>/ws/chat}（协议是 {@code {type:"AUTH"}} + {@code {type:"PING"}}），
 * 本文件连的是 box-im im-server 的 {@code ws://<im-host>:8878/im}
 * （协议是 {@code {cmd:0}} 登录 + {@code {cmd:1}} 心跳）。两者可以并存，
 * 关掉 {@code VITE_IM_ENABLED} 即回到只有自研通道的现状。</p>
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
 * 两次发起连接之间的最小间隔，用于**失败重连**的节流（避免服务端不可用时打出重连风暴）。
 *
 * <p>box-im 客户端用的是 10s。这里改成 <b>1s</b>，因为它同时会作用于
 * 「{@code onHide} 断开 → {@code onShow} 重连」这条正常路径：
 * uni-app 前后台切换很频繁，10s 节流会让「切回来要等 10 秒才收到消息」。
 * 真正的重连退避仍由 {@link scheduleReconnect} 的 {@code RECONNECT_MIN_DELAY_MS} 负责。</p>
 */
const CONNECT_THROTTLE_MS = 1000;

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
  // 阶段 3：把 box 下行帧额外映射成课安已有的 RealtimeEvent 形状，供页面零改动接入。
  dispatchMapped(cmd, frame.data);
}

/**
 * 私聊消息的事件体（{@link normalizePrivateMessage} 的产出）。
 *
 * <p>刻意做成<b>全可选</b>：任何字段缺失（老后端、后端灰度中）都只是该字段为
 * {@code undefined}，消费方各自兜底，不会因为字段缺席而抛错。</p>
 *
 * <p>用 {@code type} 而不是 {@code interface}：只有类型别名才会隐式带上索引签名，
 * 从而能赋给 {@code realtime.ts} 里 {@code RealtimeEvent.data} 的
 * {@code Record<string, unknown>}（两条通道共用一套事件类型的前提）。</p>
 */
export type ImPrivateMessageData = {
  /** kean 的消息主键 —— box 镜像投递刻意不带 id（两套编号体系不同），所以这里通常缺席 */
  id?: number;
  /** kean 会话 id —— 后端在镜像 data 里额外补的字段；缺席时页面忽略气泡、只刷角标 */
  sessionId?: number;
  senderId?: number;
  recvId?: number;
  msgType?: string;
  content?: string;
  url?: string | null;
  /** 会话内单调递增序号：有它才能正确排序/去重，并推进增量游标 */
  seqNo?: number;
  /** 客户端幂等键：发送方本地的对账依据 */
  localId?: string;
  /** 0 未读 / 1 已发送 / 2 撤回 / 3 已读（box 与 kean 同义） */
  status?: number;
  createdAt?: string;
};

/**
 * 业务事件回调：形状与 {@code utils/realtime.ts} 的 {@code RealtimeEvent} 一致。
 *
 * <p>用交叉类型而不是另写一份结构：这样两条通道的事件在类型上就是同一套
 * （页面 handler 只认 {@code RealtimeEvent}），以后 {@code realtime.ts} 加字段
 * 也不会悄悄漏掉 IM 通道。</p>
 */
export type ImRealtimeEvent = RealtimeEvent & {
  /** 比 RealtimeEvent 更窄：本通道只会产出这几种 */
  type?: "MESSAGE" | "NOTICE" | "READ" | "BANNED";
  noticeType?: string;
  bizType?: string;
  bizId?: number;
  title?: string;
  content?: string;
  sessionId?: number;
  maxSeq?: number;
  readerId?: number;
  /**
   * PRIVATE_MESSAGE 的事件体，形状对齐 {@code api/chat.ts} 的 {@code ChatMessageItem}
   * （字段名小驼峰：{@code sessionId}/{@code seqNo}/{@code localId}/{@code status}…）。
   * 后端未提供某个字段时该字段是 {@code undefined}，不做补默认值。
   */
  data?: ImPrivateMessageData;
};

type ImEventListener = (event: ImRealtimeEvent) => void;

const mappedListeners = new Set<ImEventListener>();

/**
 * 订阅「已映射成 RealtimeEvent 形状」的下行事件。
 *
 * <p>与 {@link on} 的区别：{@code on} 交付的是 box 原生的 {@code (cmd, data)}，
 * 本函数交付的是课安页面已经在消费的那套事件对象，因此页面/组合式函数
 * （例如 {@code composables/useLiveUpdates.ts}）不需要为第二条通道再写一遍解析。</p>
 *
 * <p>返回取消订阅的函数，方便 {@code onShow}/{@code onHide} 成对绑定。</p>
 */
export function onMapped(listener: ImEventListener): () => void {
  mappedListeners.add(listener);
  return () => {
    mappedListeners.delete(listener);
  };
}

/** 清空全部映射事件订阅。 */
export function offMapped(): void {
  mappedListeners.clear();
}

/**
 * box 下行帧 → 课安 RealtimeEvent。
 *
 * <table border="1">
 *   <tr><th>cmd</th><th>含义</th><th>映射结果</th></tr>
 *   <tr><td>2</td><td>强制下线 FORCE_LOGOUT</td><td>直接调用 {@link handleSessionEnded} 清登录态并回登录页（不产生事件）</td></tr>
 *   <tr><td>3</td><td>私聊消息 PRIVATE_MESSAGE</td>
 *       <td>{@code {type:"MESSAGE", sessionId, data: <PrivateMessageVO 同构 + sessionId>}}</td></tr>
 *   <tr><td>4</td><td>群聊消息 GROUP_MESSAGE</td><td><b>不映射</b>：课安没有群聊，忽略</td></tr>
 *   <tr><td>5</td><td>系统消息 SYSTEM_MESSAGE</td><td>{@code {type:"NOTICE", noticeType, bizType, bizId}}</td></tr>
 * </table>
 *
 * <p><b>sessionId 的来源与兼容</b>：box 的 {@code PrivateMessageVO} 本身用
 * {@code sendId}/{@code recvId} 表达双方、<b>没有</b> {@code sessionId}，而 {@code chat.vue}
 * 的 {@code applyIncoming} 要求 {@code sessionId === 当前会话}。kean 后端在镜像投递时
 * 会额外把 {@code sessionId} 写进 {@code data}，本函数把它同时映射到事件顶层与
 * {@code data} 里（与自研通道 MESSAGE 的形状一致），页面侧因此不需要分支。</p>
 *
 * <p><b>后端尚未上线该字段时</b>：{@code sessionId} 映射结果为 {@code undefined}，
 * 事件里就不带该字段（<b>绝不伪造、绝不用 peerId 猜</b>），页面按「不属于当前会话」静默忽略 ——
 * 即回到本轮之前的现状（只刷未读角标、气泡等 HTTP 轮询/增量拉取补齐），不报错、不白屏。</p>
 */
export function toRealtimeEvent(cmd: number, data: unknown): ImRealtimeEvent | null {
  if (cmd === IMCmd.FORCE_LOGOUT) {
    // 与「被其他设备顶下线」复用同一条既有出口（清登录态 + 弹窗 + reLaunch 到登录页）。
    // box 的 FORCE_LOGOUT 是「同一账号在别处登录，本连接被顶掉」的语义，
    // 不是封禁；封禁走的是课安自己的 BANNED 事件（handleAccountBanned）。
    // im-server 的 data 是自由文本字符串，这里只在它确实是字符串时透传。
    handleSessionEnded(typeof data === "string" && data.trim() ? data : undefined);
    return null;
  }
  if (cmd === IMCmd.PRIVATE_MESSAGE) {
    const message = normalizePrivateMessage(data);
    // sessionId 同时放在事件顶层：chat.vue 的 READ 分支读的是顶层，MESSAGE 分支读 data，
    // 两处口径统一，页面侧不需要知道消息来自哪条通道。
    return { type: "MESSAGE", sessionId: message?.sessionId, data: message };
  }
  if (cmd === IMCmd.SYSTEM_MESSAGE) {
    const payload = asRecord(data);
    // 镜像投递（RealtimePublisher）写的是 {type:"NOTICE",noticeType,bizType,bizId}，
    // 与课安自研通道的 NOTICE 完全同形；box 原生的系统消息则可能只带 content。
    const noticeType = asString(payload.noticeType) || asString(payload.type) || "SYSTEM";
    return {
      type: "NOTICE",
      noticeType,
      bizType: asString(payload.bizType),
      bizId: asNumber(payload.bizId),
      title: asString(payload.title),
      content: asString(payload.content)
    };
  }
  // cmd 4（群聊）以及未知 cmd：不解释，返回 null。
  return null;
}

/** 依次交给所有订阅者；调用方保证不因监听器异常而中断收包循环。 */
function dispatchMapped(cmd: number, data: unknown): void {
  if (!mappedListeners.size) {
    return;
  }
  let event: ImRealtimeEvent | null;
  try {
    event = toRealtimeEvent(cmd, data);
  } catch (error) {
    emitError(error);
    return;
  }
  if (!event) {
    return;
  }
  mappedListeners.forEach((listener) => {
    try {
      listener(event);
    } catch (error) {
      emitError(error);
    }
  });
}

/**
 * box {@code PrivateMessageVO} → 课安 {@code ChatMessageItem} 同构对象。
 *
 * <p>字段名对齐 {@code api/chat.ts} 的 {@code ChatMessageItem}：box 的
 * {@code sendId}/{@code recvId} → 课安的 {@code senderId}，{@code type}（数字码）→
 * {@code msgType}（{@code TEXT}/{@code IMAGE}），{@code sendTime}（epoch 毫秒）→
 * {@code createdAt}（ISO 字符串，页面直接做时间显示与排序）。</p>
 *
 * <p><b>本轮补上的字段</b>：{@code sessionId}（后端在镜像 data 里额外补的字段，缺席就别带，
 * 不用 peerId 猜）、{@code seqNo}/{@code localId}/{@code status}（这三个 box 的
 * {@code privateRecvInfoJson} 本来就会带，之前被丢掉了）。有 {@code seqNo} 才能让
 * {@code chatMerge} 正确排序去重、并推进增量游标。</p>
 *
 * <p><b>仍然刻意不映射的字段</b>：{@code mine}（需要当前用户 id，本文件不持有登录态，
 * 由页面按 senderId 兜底）。{@code id} 也通常缺席 —— box 镜像投递不带 id，
 * 用 {@code localId}/{@code seqNo} 做去重键即可。</p>
 */
export function normalizePrivateMessage(data: unknown): ImPrivateMessageData | undefined {
  const payload = asRecord(data);
  if (!Object.keys(payload).length) {
    // 非对象（或空对象）：不猜结构，交给上层忽略。
    return undefined;
  }
  // msgType：优先用后端给的字符串（灰度中可能补上）；给的是 box 数字码（0 文本 / 1 图片）
  // 或压根没给时，都按同样的口径推断。两种形态都认，避免后端换表达方式就显示错。
  const msgType = toMsgType(payload.msgType) || toMsgType(payload.type) || "TEXT";
  const content = asString(payload.content) || "";
  const sendTime = payload.sendTime;
  const createdAt =
    typeof sendTime === "number"
      ? new Date(sendTime).toISOString()
      : typeof sendTime === "string" && sendTime
        ? sendTime
        : new Date().toISOString();
  return {
    id: asNumber(payload.id),
    // 后端未上线该字段时是 undefined：页面据此忽略气泡（只刷角标），而不是拿 sendId/recvId 去猜。
    sessionId: pickNumber(payload, ["sessionId", "session_id"]),
    senderId: asNumber(payload.sendId),
    recvId: asNumber(payload.recvId),
    msgType,
    content,
    // 与后端 toMessageVo 的口径一致：只有图片才给 url，文本消息 url 为 null。
    url: msgType === "IMAGE" ? content : null,
    seqNo: pickNumber(payload, ["seqNo", "seq_no"]),
    localId: pickString(payload, ["localId", "local_id"]),
    status: asNumber(payload.status),
    createdAt
  };
}

/**
 * 按候选字段名取数字：契约是小驼峰，同时容忍 snake_case 别名。
 *
 * <p>后端这些字段是并行补的、以 JSON 形式投递，命名分歧是这类联调最常见的坑；
 * 多认一个别名几乎没有成本，却能省掉「字段其实是 {@code session_id}，气泡一直不出现」
 * 这种要抓包才能定位的一轮排查。</p>
 */
function pickNumber(payload: Record<string, unknown>, names: string[]): number | undefined {
  for (const name of names) {
    const value = asNumber(payload[name]);
    if (value !== undefined) {
      return value;
    }
  }
  return undefined;
}

/** 同上，取字符串（空串按「没有」处理，与 {@link asString} 口径一致） */
function pickString(payload: Record<string, unknown>, names: string[]): string | undefined {
  for (const name of names) {
    const value = asString(payload[name]);
    if (value !== undefined) {
      return value;
    }
  }
  return undefined;
}

function asRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === "object" && !Array.isArray(value) ? (value as Record<string, unknown>) : {};
}

/**
 * 消息类型归一化：box 的数字码与 kean 的字符串两种表达都认。
 * 识别不了就返回空串，交给调用方选默认值（不会把「0」当成类型名）。
 */
function toMsgType(value: unknown): string {
  if (typeof value === "number" && Number.isFinite(value)) {
    return value === 1 ? "IMAGE" : "TEXT";
  }
  const text = asString(value)?.toUpperCase();
  if (text === "IMAGE" || text === "TEXT" || text === "RECALL") {
    return text;
  }
  // box 的数字码以字符串形式传过来（"0"/"1"）时同样按数字口径解释。
  if (text === "0") {
    return "TEXT";
  }
  if (text === "1") {
    return "IMAGE";
  }
  return "";
}

function asString(value: unknown): string | undefined {
  if (value === null || value === undefined) {
    return undefined;
  }
  const text = String(value).trim();
  return text === "" ? undefined : text;
}

function asNumber(value: unknown): number | undefined {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : undefined;
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
