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

/**
 * 时间线排序（**升序**）。
 *
 * 排序规则（重要 ✗ 这里踩过两次坑）：
 *   ① **待确认的本地乐观消息永远排最后** ✗ —— 它是最新发出的，理应在底部。
 *      这条 rule 必须独立成立 ✓ 不能靠"有没有 seqNo"来判断 ✓
 *      （曾经用「缺 seqNo 就排最前」当历史数据处理 ✗ 结果乐观消息被顶到列表最顶部 ✗
 *        线上表现为「断网发送看不到气泡」✗ 而联网时消息带着服务端 seqNo 进来 ✗
 *        所以从线上看不出来 ✓✓）
 *   ② 其余消息**一律按时间排** ✓（`createdAt` 服务端与本地都有且单调 ✓）
 *      —— 不要用 seqNo 当主键 ✗：一旦某条已发出的消息在客户端没拿到 seqNo ✗
 *      （回执字段没映射上 / 历史数据缺列 ✗）它就会被误当成"最早的消息"顶到最上面 ✗✓
 *   ③ 时间相同时，有 seqNo 的按 seqNo ✓ 再兜底按 id ✓
 */
function timelineRank(message: ChatMessageItem): number {
  // 0 = 普通消息（按时间排）· 1 = 待确认的乐观消息（永远最后）
  return isOptimistic(message) ? 1 : 0;
}

function seqOf(message: ChatMessageItem): number {
  const seq = positive(message.seqNo);
  return seq > 0 ? seq : 0;
}

export function sortTimeline(list: ChatMessageItem[]): ChatMessageItem[] {
  return [...list].sort((a, b) => {
    const rankA = timelineRank(a);
    const rankB = timelineRank(b);
    if (rankA !== rankB) {
      return rankA - rankB;
    }
    const timeA = timeOf(a);
    const timeB = timeOf(b);
    if (timeA !== timeB) {
      return timeA - timeB;
    }
    const seqA = seqOf(a);
    const seqB = seqOf(b);
    if (seqA !== seqB) {
      return seqA - seqB;
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

function isMessageItem(value: unknown): value is ChatMessageItem {
  return Boolean(value) && typeof value === "object";
}

/**
 * 发送回执合并：把 POST/重发响应里的服务端消息按 localId 替换掉本地乐观的那条。
 *
 * <p>与 {@link mergeIncoming} 的区别只有一个，但很关键：<b>这里不做会话过滤</b>。
 * 调用方拿到的是「刚刚 POST 的那个会话」的权威回执，按定义就属于当前会话；
 * 而回执里 {@code sessionId} 缺失（老后端）或类型/命名与本地不一致时，
 * 走列表入口（chat.vue 的 upsertMessages）会被整行丢弃 —— 本地那条就永远停在
 * "sending"，既盖住角标也盖住对勾。</p>
 *
 * <p>命中 localId 后服务端版本整体覆盖本地字段（含 status/seqNo/id），
 * {@code sendState} 随之消失；另有一条兜底：同一会话里那条「同一内容 + 同一类型」
 * 且仍是负 id / 仍是发送中的本地条目一并清掉，避免老后端不回 localId 时留下幽灵气泡。</p>
 */
export function mergeConfirm(
  existing: ChatMessageItem[],
  incoming: ChatMessageItem[]
): ChatMessageItem[] {
  const rows = incoming.filter(isMessageItem);
  if (!rows.length) {
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

  rows.forEach((item) => {
    const key = dedupeKey(item);
    const found = indexOf.get(key);
    if (found === undefined) {
      indexOf.set(key, merged.length);
      merged.push(item);
      return;
    }
    // 服务端版本优先，sendState 等前端字段随覆盖一起消失
    merged[found] = { ...merged[found], ...item, sendState: undefined };
  });

  // 兜底：本条已确认，同一内容/类型的本地乐观条目不应再留着（否则它会一直显示"发送中"）。
  // 只在 incoming 确实带着服务端 id 时才做这层清理（{@link isOptimistic} 对只有 sendState
  // 的条目也返回 true，那种"回执本身没落库"的情况不能当成确认）。
  const confirmed = rows.filter((item) => Number(item.id || 0) > 0);
  const guarded =
    confirmed.length === 0
      ? merged
      : merged.filter((item) => {
          if (!isOptimistic(item)) {
            return true;
          }
          return !confirmed.some((row) => {
            if (row.msgType !== item.msgType) {
              return false;
            }
            const rowText = String(row.content ?? "");
            // TEXT：乐观条目的 content 与回执一致
            if (rowText && rowText === String(item.content ?? "")) {
              return true;
            }
            // IMAGE：本地乐观条目可能还拿着 blob/临时路径，而回执里是 objectKey —— 比 url 兜一层
            const rowUrl = String(row.url ?? "");
            return Boolean(rowUrl) && rowUrl === String(item.url ?? item.content ?? "");
          });
        });
  return sortTimeline(guarded);
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
    // 服务端版本（有 id + seqNo）说明这条已经落库：本地那个"发送中/失败"标记必须让位，
    // 否则它会把状态角标压成"发送中"，连对勾都盖住（这一层是三条写入口的共同兜底）。
    if (Number(item.id || 0) > 0 && positive(item.seqNo) > 0) {
      next.sendState = undefined;
    }
    merged[found] = next;
  });

  return sortTimeline(merged);
}

/**
 * ⚠️ 本轮已删除 `markMineRead(list, maxSeq)`：
 * 它唯一的用途是「收到 READ 事件 → 把我发出的、seqNo ≤ maxSeq 的消息标成 status=3」，
 * 也就是气泡上的**双勾**。客户端本轮已移除双勾（气泡只剩 发送中 / 单勾 / 失败），
 * 调用方（`chat.vue` 的 `applyReadReceipt`）也已一并删除 ⇒ 它是彻头彻尾的死代码。
 *
 * ⚠️ 与它无关、**必须保留**的是 `status` 字段本身（撤回判据 status===2 仍在用，
 * 且服务端仍在写 status=3；客户端只是不再把它渲染成「对方已读」）。
 */
