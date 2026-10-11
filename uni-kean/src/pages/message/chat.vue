<script setup lang="ts">
import { fetchMe } from "@/api/auth";
import {
  getChat,
  listChatMessages,
  listChatMessagesAfter,
  type ChatMessageItem,
  type ChatSessionItem
} from "@/api/chat";
import { goReport } from "@/utils/report";
import { actionBlockReason, formatChatTime, shouldShowChatTime } from "@/utils/format";
import { blockUser } from "@/api/blacklist";
import { refreshMessageBadge } from "@/utils/messageBadge";
import { resolveMediaUrl, uploadFile } from "@/utils/request";
import {
  isOptimistic,
  maxCreatedAtOf,
  maxSeqOf,
  mergeConfirm,
  mergeIncoming,
  sortTimeline
} from "@/utils/chatMerge";
import {
  createLocalId,
  getLastSeq,
  getOutboxEntry,
  listOutbox,
  removeOutbox,
  setLastSeq,
  setReadAt,
  upsertOutbox,
  type ChatSendState
} from "@/utils/chatStore";
import {
  buildLocalMessage,
  createOutboxPayload,
  createReadReporter,
  outgoingStatus,
  outgoingStatusLabel,
  retryOutboxEntry,
  sendWithOutbox,
  type ChatDisplayMessage,
  type ChatOutgoingStatus,
  type ChatSendContext,
  type ChatSendPayload
} from "@/utils/chatSync";
import FallbackImage from "@/components/FallbackImage.vue";
import { useLiveUpdates } from "@/composables/useLiveUpdates";
import { useUploadProgress } from "@/composables/useUploadProgress";
import { useUserStore } from "@/store/user";
import {
  EMOJI_NAME_LIST,
  emojiPathOf,
  formatEmoji,
  listRecentEmoji,
  rememberRecentEmoji,
  splitEmoji,
  type EmojiSegment
} from "@/utils/emoji";
import { t } from "@/utils/i18n";
import { onHide, onLoad, onPageScroll, onShow, onUnload } from "@dcloudio/uni-app";
import { useToast } from "wot-design-uni";
import { computed, nextTick, onMounted, ref } from "vue";

const toast = useToast();
const userStore = useUserStore();
const sessionId = ref(0);
const session = ref<ChatSessionItem | null>(null);
const messages = ref<ChatMessageItem[]>([]);
const content = ref("");
const sending = ref(false);
/** 正在发送（含自动重试中）的 localId：hydrate 出来的失败消息重发时也能显示「发送中」 */
const sendingIds = ref<string[]>([]);
/** 已在飞行的发送请求条数：只用来显示「发送中」提示，绝不参与「能不能发」的判断 */
const inflight = ref(0);
/**
 * 是否还有发送请求在飞 —— <b>只用来显示「发送中」提示，绝不参与「能不能发」的判断</b>。
 *
 * <p>这里曾经被当成守卫（`busy` 为真就提前 return），那正是「只转圈、不出气泡」的成因之一：
 * 上一条还在重试时新的点击被丢弃，乐观气泡根本没机会插入。</p>
 *
 * <p><b>2024 补记（实测产物证据）</b>：{@code wot-design-uni} 的 {@code wd-button}
 * 在 {@code loading=true} 时<b>根本不派发 click</b>（产物 {@code wd-button.Cb9JFRLM.js}：
 * {@code f.disabled || f.loading || h("click", e)}；源码 {@code wd-button.vue} 同）。
 * 也就是说 {@code :loading="busy"} 等于在重试期间把发送按钮变成不可点，
 * 注释里过去写的「按钮仍是可点的」是<b>错的</b>。所以按钮上不再绑定 loading，
 * 改用旁边的 {@code sendingHint} 文案提示发送中。</p>
 */
const busy = computed(() => inflight.value > 0 || sending.value || sendingIds.value.length > 0);
/** 发送中的可见提示：不吞点击，只做展示 */
const sendingHint = computed(() => (busy.value ? "发送中" : ""));
// 传取值函数而不是当前值：这一行在 setup 里执行时 sessionId 还是 0（真实 id 要到 onLoad 才有），
// 值捕获会让 readReporter 永远被 !sessionId 挡在发请求之前。
// onRead 只在服务端确认已读后才重算未读角标 —— 之前是"入队即刷"，markRead 失败时角标会假清零。
const readReporter = createReadReporter(() => sessionId.value, {
  onRead: () => {
    void refreshMessageBadge();
  }
});
// 解构到顶层，模板才会自动解包 ref
const { active: uploading, label: uploadLabel, onProgress, reset: resetUpload } = useUploadProgress();
const chatBlock = computed(() => actionBlockReason(userStore.state.user, "chat"));
const peerBanned = computed(() => Boolean(session.value?.peerBanned));
const peerNotice = computed(() => {
  if (peerBanned.value) {
    return "对方已被封禁";
  }
  if (session.value?.peerMuted) {
    return "对方已被禁言";
  }
  return "";
});
const sendBlocked = computed(() => chatBlock.value || (peerBanned.value ? "对方已被封禁" : ""));
const myAvatar = computed(() => resolveMediaUrl(userStore.state.user?.avatarUrl));
const peerAvatar = computed(() => resolveMediaUrl(session.value?.peerAvatarUrl));
const myUserId = computed(() => Number(userStore.state.user?.id || 0));

/**
 * ⚠️ 临时诊断埋点（本轮只加日志、不改逻辑）。
 *
 * 统一前缀 `[kean-send]` / `[kean-rt]`，只打长度与 ID，绝不打消息正文（隐私）。
 * 目的：让线上复现时自己说话 —— 区分「插入没执行」「插入后又被整页刷新覆盖」
 * 「插进去了但渲染侧过滤掉」这三种此前无法区分的可能。
 */
function logSend(step: string, detail: Record<string, unknown> = {}) {
  console.log(`[kean-send] ${step}`, detail);
}

/* ===========================================================================
 * 内置表情（纯前端；消息体里存的仍是**纯文本短代码** `[微笑]`）
 * ---------------------------------------------------------------------------
 * · 短代码格式与映射表来自 box-im 的 utils/emotion.web.ts（formatEmoji → `[名字]`），
 *   素材与版权声明见 src/static/emoji/（LICENSE.txt + README.md）。
 * · 这里**只往输入框里插文字**，绝不改消息结构、请求体或后端契约：
 *   发送走的还是 handleSend → createOutboxPayload(text, "TEXT")，一个字没动。
 * · 面板刻意不用 backdrop-filter / transform / filter / will-change / contain /
 *   perspective：本页有 fixed 弹层与 fixed 输入区，这些属性会创建 containing block
 *   把 fixed 后代劫持住（见仓库铁律 3）。面板本身也放在 .composer **之外**，
 *   不参与 fixed 元素做 containing block。
 * =========================================================================== */
const emojiOpen = ref(false);
/** 展开次数：面板每次重开都重新算一次「最近使用」（不引 watch，也不动其它状态） */
const emojiSessions = ref(0);
const recentEmoji = ref<string[]>([]);
const emojiRecentList = computed(() => (emojiSessions.value ? recentEmoji.value : []));

/* ===========================================================================
 * 「更多」面板（＋）与「＋ ↔ 发送」互斥切换（纯呈现层状态，不碰任何发送逻辑）
 * ---------------------------------------------------------------------------
 * · moreOpen / emojiOpen 一组**互斥**开关：两个面板都是从输入栏上沿弹出的，同时开着会互相盖住。
 * · anyPanelOpen → 最右侧按钮在面板打开时让位给「＋ 打开面板 / 键盘 收回面板」这个图标钮；
 *   showSendAction / showPlusAction 是两个**互补**的判定，模板里用 v-if / v-else 做成
 *   「同一个按钮位上的互斥替换」（微信的核心特征），不是并排出现两个按钮。
 * · 判据只读 content（输入框内容）与上面两个开关：不改 handleSend、不改 v-model、不改发送守卫。
 * · 这里有三个 computed 而不是在模板里写表达式：模板每帧重渲染都会求值，
 *   重函数/多条件一律预计算（仓库铁律 6）。
 * =========================================================================== */
const moreOpen = ref(false);
/** 表情面板或更多面板是否有一个正开着（最右侧图标钮的判据） */
const anyPanelOpen = computed(() => emojiOpen.value || moreOpen.value);
/** 最右侧位置显示「发送」：有输入内容（含纯空白，与微信一致）且此刻没有任何面板开着 */
const showSendAction = computed(() => String(content.value || "").length > 0 && !anyPanelOpen.value);
/** 最右侧位置显示「＋」：没有输入内容时才显示（有内容时被发送替换掉，两者绝不同时出现） */
const showPlusAction = computed(() => !showSendAction.value && !anyPanelOpen.value);
/** 表情面板开着时切换成「键盘」图标 */
const showEmojiToggle = computed(() => emojiOpen.value);
/** H5 的 <input> / <textarea> 是原生 DOM：有 focus()/blur() 才调用，小程序端没有 blur 就跳过 */
function blurComposerInput() {
  const field = document.querySelector(".composer .input") as unknown as HTMLInputElement | null;
  if (field && typeof field.blur === "function") {
    field.blur();
  }
}

function emojiCellKey(name: string, index: number) {
  return `${index}-${name}`;
}

function refreshRecentEmoji() {
  recentEmoji.value = listRecentEmoji();
}

function toggleEmojiPanel() {
  // 两个面板互斥：展开表情就先收起更多，否则两个面板会叠在一起
  moreOpen.value = false;
  emojiOpen.value = !emojiOpen.value;
  if (emojiOpen.value) {
    // 与微信一致：点开面板时收起软键盘（H5 里 blur 会顺带收起键盘，小程序端没有 blur 就跳过）
    blurComposerInput();
    emojiSessions.value += 1;
    refreshRecentEmoji();
  }
}

/** 点「＋」：开/关「更多」面板（与表情面板互斥） */
function toggleMorePanel() {
  emojiOpen.value = false;
  moreOpen.value = !moreOpen.value;
  if (moreOpen.value) {
    blurComposerInput();
  }
}

/** 点面板里的「相册」：复用现有发图逻辑（handleSendImage），只多做一步收面板 */
function handleAlbumFromMorePanel() {
  moreOpen.value = false;
  handleSendImage();
}

/**
 * 点表情 → 把短代码插到输入框**光标处**；拿不到光标位置就追加到末尾。
 *
 * <p>⚠️ 绝不整段替换：任何取不到光标/选区的情况（小程序端原生 input 就没有
 * setSelectionRange）都退化成「追加到末尾」，已输入的内容一个字都不会丢。</p>
 */
function insertEmoji(name: string) {
  const code = formatEmoji(name);
  const value = String(content.value || "");
  let caret = value.length;
  // H5 的 <input> 是原生 DOM：优先按用户的真实光标位置插入
  const input = document.querySelector(".composer .input") as HTMLInputElement | null;
  const start = input && typeof input.selectionStart === "number" ? input.selectionStart : null;
  if (input && start !== null && start <= value.length) {
    caret = start;
  }
  const next = value.slice(0, caret) + code + value.slice(caret);
  content.value = next;
  // 复原光标到插入内容之后（小程序端 setSelectionRange 不存在 → 走追加，同样正确）
  if (input && typeof input.setSelectionRange === "function") {
    const at = caret + code.length;
    nextTick(() => {
      try {
        input.focus();
        input.setSelectionRange(at, at);
      } catch {
        // 某些内核会在失焦时抛错：忽略即可，输入框内容已经是对的
      }
    });
  }
  refreshRecentEmoji();
  recentEmoji.value = rememberRecentEmoji(name);
}

/** 打开图片选择器（或点消息列表 / 页面隐藏）前收起面板：否则面板会一直悬在系统弹层上面 */
function closeEmojiPanel() {
  emojiOpen.value = false;
  moreOpen.value = false;
}

/**
 * 消息正文 → 渲染片段（纯文本里只把**已知**短代码换成内联小图）。
 *
 * <p>性能：切好的片段按「id/localId + 正文」缓存，列表重渲染不会重复切；
 * 正则与「名字 → 下标」查表都在 utils/emoji.ts 里预编译，渲染路径上不做编译。
 * 缓存条目数有上限，超了整体清空（只是一次重算，不会泄漏内存）。</p>
 */
const emojiSegmentCache = new Map<string, EmojiSegment[]>();

function messageSegments(item: ChatMessageItem): EmojiSegment[] {
  const text = String(item.content || "");
  if (!text) {
    return [];
  }
  const key = `${item.localId || item.id || 0}|${text}`;
  const cached = emojiSegmentCache.get(key);
  if (cached) {
    return cached;
  }
  const segments = splitEmoji(text);
  if (emojiSegmentCache.size > 600) {
    emojiSegmentCache.clear();
  }
  emojiSegmentCache.set(key, segments);
  return segments;
}

/**
 * 渲染行：在 {@link ChatDisplayMessage} 上**就地**再补一个纯展示字段 `recalled`。
 *
 * <p>刻意不改 utils/chatSync.ts 里已被别处引用的 {@code ChatDisplayMessage}：
 * 这次只是聊天页要一个多出来的渲染字段，没必要动公共类型。</p>
 */
type ChatRow = ChatDisplayMessage & {
  /** 是否是一条「已撤回」提示行（只用于渲染，不参与任何数据判断） */
  recalled?: boolean;
};

/**
 * 是否「已撤回」。
 *
 * <p>⚠️ 判据与 utils/chatSync.ts:460 的 {@code outgoingStatus} **逐字一致**
 * （{@code msgType === "RECALL" || status === 2}），不新增判据、不碰数据层：
 * 后端「2 = 撤回」的口径见 api/chat.ts:5，两处必须同源。</p>
 */
function isRecallMessage(item: ChatMessageItem): boolean {
  return item.msgType === "RECALL" || item.status === 2;
}

const displayMessages = computed<ChatRow[]>(() => {
  const list = messages.value;
  logSend("render", { messagesLen: list.length, sendingIds: sendingIds.value.length });
  return list.map((item, index) => ({
    ...item,
    mine: resolveMine(item),
    sendState: resolveSendState(item),
    // 预计算放在这里而不是模板里调函数：消息列表可能很长，模板里每行调一次不值当
    recalled: isRecallMessage(item),
    showTime: shouldShowChatTime(index === 0 ? null : messages.value[index - 1]?.createdAt, item.createdAt)
  }));
});

/**
 * 角标状态：服务端口径优先，本地「正在重发」只在服务端还没结论时才显示为发送中。
 *
 * <p>为什么不能无条件压成 sending：重发时 localId 会留在 {@code sendingIds} 直到重发结束，
 * 而重发成功后服务端已给了 seqNo/status —— 无条件压实会把刚拿回来的对勾打回「发送中」，
 * 表现为「角标要刷新才回来」。</p>
 */
function resolveSendState(item: ChatMessageItem): ChatSendState | undefined {
  if (sendingIds.value.indexOf(String(item.localId || "")) < 0) {
    return item.sendState;
  }
  const server = outgoingStatus({ ...item, mine: resolveMine(item) });
  // 服务端已经给出结论（已发送；历史取值 "read" 现在也只显示单勾）时不再覆盖；
  // 本地还是 sending/failed/未知才显示为发送中
  return server === "sent" || server === "read" ? item.sendState : "sending";
}

function openUser(userId?: number | null, mine = false) {
  if (mine) {
    uni.switchTab({ url: "/pages/mine/index" });
    return;
  }
  if (!userId) {
    return;
  }
  uni.navigateTo({ url: `/pages/mine/user?id=${userId}` });
}

/**
 * 是否是我发出的消息：优先后端 mine，缺失时（老后端/本地乐观消息）按 senderId 兜底。
 */
function resolveMine(message: ChatMessageItem): boolean {
  if (typeof message.mine === "boolean") {
    return message.mine;
  }
  const senderId = Number(message.senderId || 0);
  return senderId > 0 && senderId === myUserId.value;
}

function statusOf(message: ChatMessageItem): ChatOutgoingStatus {
  return outgoingStatus({ ...message, mine: resolveMine(message) });
}

function statusLabel(message: ChatMessageItem): string {
  return outgoingStatusLabel(statusOf(message));
}

function statusClass(message: ChatMessageItem): string {
  const status = statusOf(message);
  return status ? `st-${status}` : "";
}

/**
 * 已读上报的位点：优先取「对方发来的消息」的最大 seqNo —— 服务端只把
 * 「对方发给我、seq_no <= maxSeq」的消息置为已读，先报自己那条会让位点跳过对方的消息。
 * 一条都没有（只有我发的 / 老后端无 seqNo）时退回全部消息的最大值。
 */
function unreadMaxSeq(): number {
  const fromPeer = messages.value.filter((item) => resolveMine(item) === false);
  const seq = maxSeqOf(fromPeer);
  return seq > 0 ? seq : maxSeqOf(messages.value);
}

/** 已读位点：最大值出现时立刻发，其余合并到 1 秒后 */
function reportRead(seq: number, immediate = false) {
  readReporter.note(seq, { immediate });
}

/**
 * 进会话（以及每次全量刷新后）无条件上报一次已读：
 * - 有 seqNo 就带 maxSeq（服务端会清未读 + 把对方发给我的消息置已读）；
 * - 一条 seqNo 都拿不到（老后端 / 序号字段缺失）时不发 maxSeq=0（那等于"读到第 0 条"，
 *   服务端会当成位点 0 处理），改为走不带 body 的老口径，只清自己那侧未读数。
 *
 * <p>⚠️ 这条上报链路只服务于「自己的未读角标」，<b>本轮一行未动</b>：
 * 与气泡上的对勾无关，也不能因为去掉双勾而删掉（删了未读就永远不清零）。</p>
 */
function chatRead(): void {
  const seq = unreadMaxSeq();
  if (seq > 0) {
    readReporter.note(seq, { immediate: true });
    return;
  }
  readReporter.flushUnscoped();
}

/** 唯一的「无条件滚到底」原语：只有 `jumpToBottom` 与 {@link decideScrollToBottom} 会调它 */
function scrollToBottom() {
  uni.pageScrollTo({
    scrollTop: 99999,
    duration: 0
  });
}

/* ===========================================================================
 * 滚动决策（本轮新增：只决定「要不要滚到底」，不碰任何数据流 / 发送逻辑 / 已读上报）
 * ---------------------------------------------------------------------------
 * 用户反馈：消息没充满聊天框时，发送与收消息也会把界面拉到底 —— 微信/QQ 不会。
 * 这里落的就是微信的规则：① 列表没溢出 → 一个像素都不动；② 只有溢出才滚到底；
 * ③ 收消息只有「用户本来就在底部附近」时才跟随，他翻过历史就不许拽回来。
 *
 * ⚠️ 为什么**不能**直接用 `scrollHeight > clientHeight` 这条字面公式：
 *    本页 `.page` 是 `min-height: 100vh` + `padding-bottom: 112px`，而 uni-app H5 的
 *    框架 CSS 只给 uni-app / uni-page / uni-page-wrapper / uni-page-body 设了
 *    `box-sizing: border-box`（node_modules/@dcloudio/uni-h5/style/framework/base.css:19-26），
 *    `uni-view` 没有 → `.page` 是 content-box；再加上顶部 44px 导航栏
 *    （uni-h5 给本页把 `--window-top` 设成 44px，见 dist/uni-h5.es.js:7153-7166），
 *    **哪怕一条消息都没有，document 也能滚 156px（112 + 44）** →
 *    `scrollHeight - clientHeight` 恒 > 1 → 字面公式永远判定「溢出」，等于没修。
 *    （uni 自己的 pageScrollTo 就是在 document 上按这两个值夹取的：
 *     node_modules/@dcloudio/uni-shared/dist/uni-shared.es.js:1594-1600）
 *
 * ⭐ 所以判据改成量「消息列表自身」的几何：列表高 vs 列表真正能占的高度
 *    （视口高 − 列表上方占位 − 列表下方占位）。两个占位都由实测相减得到，
 *    不写死 112 / 44，因此完全不受上面那 156px 的干扰。
 *
 * 取值方式：`uni.createSelectorQuery().boundingClientRect()`（与 `uni.pageScrollTo` 同属
 *    uni API 家族，不引入第二套；`uni.getSystemInfoSync()` 在仓库里已有用法：
 *    pages/mine/crop.vue:9、pages/mine/index.vue:25、utils/request.ts:97）。
 *    uni 会把 rect 的 top/bottom 统一减去 `--window-top` 再返回，与
 *    `getSystemInfoSync().windowHeight` 是**同一坐标系**，两者可直接比较；
 *    两个 rect 相减得到的占位量又与滚动位置无关。全程按需测量，不缓存、不需要 @scroll 节流。
 * =========================================================================== */

/** 「底部附近」阈值（px）：列表底边离可视区底部那条线 60px 以内，算「用户本来就在看最新消息」 */
const NEAR_BOTTOM_PX = 60;
/** 用户滚动时检查「是不是已经滚回底部」的最小间隔（ms），只在提示挂着时才量 */
const NEW_MSG_TIP_SCROLL_MS = 120;

/**
 * 右下角「有新消息」提示是否挂着（规则④，微信做法）。
 * 只在「列表确实溢出 **且** 用户不在底部附近」时为真；自己发消息 / 回到页面 /
 * 用户滚回底部 / 点它一下都会收掉。
 */
const newMessagePending = ref(false);

/** 聊天页滚动几何（单位 px，全部同一坐标系） */
type ChatScrollBox = {
  /** 消息列表自身高度 */
  listHeight: number;
  /** 列表底边位置 */
  listBottom: number;
  /** 可视区高度 */
  viewportHeight: number;
  /** 列表**下方**被固定输入栏占掉的高度（即 .page 的 padding-bottom） */
  bottomReserve: number;
  /** 列表**上方**被状态横幅占掉的高度（即 .page 的 padding-top，常态为 0） */
  topReserve: number;
};

/** 量一次滚动几何；拿不到（节点还没渲染出来 / 视口高为 0）返回 null */
function measureChatScrollBox(): Promise<ChatScrollBox | null> {
  return new Promise((resolve) => {
    const query = uni.createSelectorQuery();
    query.select(".page").boundingClientRect();
    query.select(".list").boundingClientRect();
    query.exec((res) => {
      const pageRect = res?.[0];
      const listRect = res?.[1];
      const viewportHeight = Number(uni.getSystemInfoSync().windowHeight || 0);
      if (!pageRect || !listRect || !viewportHeight) {
        resolve(null);
        return;
      }
      const pageTop = Number(pageRect.top || 0);
      const pageBottom = Number(pageRect.bottom || 0);
      const listTop = Number(listRect.top || 0);
      const listBottom = Number(listRect.bottom || 0);
      resolve({
        listHeight: Number(listRect.height || 0),
        listBottom,
        viewportHeight,
        bottomReserve: Math.max(0, pageBottom - listBottom),
        topReserve: Math.max(0, listTop - pageTop)
      });
    });
  });
}

/** 列表是否真的超出了可视区（= 还有内容用户看不到） */
function isListOverflowing(box: ChatScrollBox): boolean {
  const available = box.viewportHeight - box.topReserve - box.bottomReserve;
  return available > 0 && box.listHeight > available + 1;
}

/** 用户是否本来就停在底部附近（列表底边已经贴住可视区底部那条线） */
function isNearBottom(box: ChatScrollBox): boolean {
  return box.listBottom <= box.viewportHeight - box.bottomReserve + NEAR_BOTTOM_PX;
}

/** 一次滚动决策的结果：overflow = 列表是否溢出；scrolled = 这次是否真的滚了 */
type ScrollDecision = {
  overflow: boolean;
  scrolled: boolean;
};

/**
 * 按微信/QQ 的规则决定要不要真的滚到底。
 *
 * @param follow "overflow"    只看「列表是否溢出」——自己发出去的消息、首次进会话
 *                             （溢出就一定让用户看到自己那条；没布满则一个像素都不动）
 *               "near-bottom" 还要「用户本来就在底部附近」——收到对方消息、后台刷新
 *                             （用户手动往上翻了历史，就绝不把他拽回底部）
 */
async function decideScrollToBottom(follow: "overflow" | "near-bottom", step: string): Promise<ScrollDecision> {
  const box = await measureChatScrollBox();
  if (!box) {
    // 量不到几何时的不对称兜底：收消息**宁可不滚**（绝不凭猜测把用户的视口拽走）；
    // 自己发消息 / 首次进会话则退回旧行为滚到底，保证用户一定看得到自己那条。
    logSend("scroll:nometrics", { step, follow });
    if (follow === "overflow") {
      scrollToBottom();
      return { overflow: true, scrolled: true };
    }
    return { overflow: false, scrolled: false };
  }
  const overflow = isListOverflowing(box);
  const nearBottom = isNearBottom(box);
  logSend("scroll:decide", {
    step,
    follow,
    overflow,
    nearBottom,
    listHeight: box.listHeight,
    listBottom: box.listBottom,
    viewportHeight: box.viewportHeight,
    topReserve: box.topReserve,
    bottomReserve: box.bottomReserve
  });
  // ⭐ 规则①：列表没超出可视区 —— 一个像素都不动（内容自然从顶部排列）
  if (!overflow) {
    return { overflow: false, scrolled: false };
  }
  // ⭐ 规则③：只有「本来就在底部附近」才跟随；用户翻了历史就不许拽回来
  if (follow === "near-bottom" && !nearBottom) {
    return { overflow: true, scrolled: false };
  }
  scrollToBottom();
  return { overflow: true, scrolled: true };
}

/**
 * 自己发出消息（乐观插入 / 服务端回执 / 重发）之后的跟随：溢出才滚到底，没溢出不动。
 * 顺带收掉「有新消息」提示 —— 自己发完消息时视口要么已在底部、要么全部内容都看得见。
 */
async function followOwnMessage(step: string): Promise<void> {
  const decision = await decideScrollToBottom("overflow", step);
  if (decision.scrolled || !decision.overflow) {
    newMessagePending.value = false;
  }
}

/**
 * 收到对方新消息之后的跟随：溢出 **且** 用户本来就在底部附近才滚到底。
 * 不跟随时挂出右下角的「↓」提示（微信做法），用户点它一下自己跳到底。
 */
async function followIncoming(step: string): Promise<void> {
  const decision = await decideScrollToBottom("near-bottom", step);
  newMessagePending.value = decision.overflow && !decision.scrolled;
}

/** 点右下角「有新消息」提示：用户明确要去底部，直接滚（不再走任何判据） */
function jumpToBottom() {
  newMessagePending.value = false;
  scrollToBottom();
}

async function loadSession() {
  session.value = await getChat(sessionId.value);
  uni.setNavigationBarTitle({ title: session.value.peerNickname || "私信" });
}

/**
 * 写入消息数组的统一入口：负责排序、走增量游标、不覆盖本地乐观消息。
 * batchSeq = 这批消息里的最大 seqNo（调用方用它做分页续拉/已读位点）。
 */
function upsertMessages(incoming: ChatMessageItem[]): { changed: boolean; batchSeq: number } {
  if (!incoming.length) {
    return { changed: false, batchSeq: 0 };
  }
  const rows = incoming.filter((item) => {
    if (!item) {
      return false;
    }
    const target = Number(item.sessionId || 0);
    if (target) {
      return target === sessionId.value;
    }
    // 老后端不保证回 sessionId：只认「localId 能对上本地已有条目」的那种，
    // 那是本会话刚发出去、服务端回显的同一条。localId 由本端生成，不会跨会话撞车；
    // 认不出来的一律丢弃 —— 绝不把别的会话、或排不了序的野消息插进来。
    if (!item.localId) {
      return false;
    }
    return messages.value.some((row) => String(row.localId || "") === String(item.localId));
  });
  if (!rows.length) {
    return { changed: false, batchSeq: 0 };
  }
  // 只在收到「非本地乐观消息」时推进增量游标：本地那条还没有 seqNo
  const confirmed = rows.filter((item) => !isOptimistic(item));
  const batchSeq = maxSeqOf(confirmed);

  const next = mergeIncoming(messages.value, rows);
  if (next === messages.value) {
    return { changed: false, batchSeq };
  }
  logSend("assign:upsertMessages", { before: messages.value.length, after: next.length, rows: rows.length });
  messages.value = next;

  if (batchSeq > 0) {
    setLastSeq(sessionId.value, batchSeq);
  }
  const at = maxCreatedAtOf(confirmed);
  if (at > 0) {
    setReadAt(sessionId.value, at);
  }
  return { changed: true, batchSeq };
}

/**
 * 增量拉取结果：
 * - changed = 合并进了新消息
 * - ok      = 接口成功但这段时间没有新消息（不用退回整页刷新）
 * - skip    = 没有本地游标 / 接口失败，交给调用方决定兜底
 */
type SyncOutcome = "changed" | "ok" | "skip";

/** 增量拉取：afterSeq = 上次记下的 lastSeq，只取新消息并合并去重（最多翻 5 页，防跑飞） */
async function syncIncoming(batchSize = 100): Promise<SyncOutcome> {
  if (!sessionId.value) {
    return "skip";
  }
  let cursor = getLastSeq(sessionId.value);
  // 没有本地游标（首次进入 / 老后端无 seqNo）时不走增量，交给原分页逻辑
  if (!cursor) {
    return "skip";
  }
  let changed = false;
  try {
    for (let page = 0; page < 5; page += 1) {
      const increment = await listChatMessagesAfter(sessionId.value, cursor, batchSize);
      if (!increment.list.length) {
        return changed ? "changed" : "ok";
      }
      const arrived = increment.list.filter((item) => item.mine !== true);
      const result = upsertMessages(increment.list);
      if (result.changed && arrived.length) {
        reportRead(maxSeqOf(arrived));
        // ⚠️ 只追加一次滚动决策，与上面那行已读上报**毫无关系**（reportRead 的调用点一个字没动）：
        //    增量拉到对方的真消息时，溢出且用户本来就在底部附近才跟随到底，否则不滚。
        void followIncoming("syncIncoming");
      }
      changed = changed || result.changed;
      const nextCursor = result.batchSeq || increment.serverMaxSeq;
      // 游标没前进就停，避免服务端返回不符合契约时死循环
      if (!increment.hasMore || nextCursor <= cursor) {
        return changed ? "changed" : "ok";
      }
      cursor = nextCursor;
    }
    return changed ? "changed" : "ok";
  } catch {
    // 增量失败保持现有列表：下一轮轮询或回到前台再试
    return changed ? "changed" : "skip";
  }
}

function applyIncoming(payload?: ChatMessageItem) {
  if (!payload || Number(payload.sessionId || 0) !== sessionId.value) {
    return;
  }
  const message = { ...payload, sessionId: sessionId.value };
  const existed = messages.value.some(
    (item) => Number(item.id) === Number(message.id) || (message.localId && item.localId === message.localId)
  );
  logSend("applyIncoming", { id: message.id, localId: message.localId || "", existed });
  if (existed) {
    return;
  }
  messages.value = sortTimeline(messages.value.concat(message));
  logSend("assign:applyIncoming", { len: messages.value.length });
  const seq = Number(message.seqNo || 0);
  if (seq > 0) {
    setLastSeq(sessionId.value, seq);
  }
  setReadAt(sessionId.value, Date.parse(String(message.createdAt || "")) || 0);
  // 收到对方的真消息：溢出且用户本来就在底部附近才跟随到底，否则不滚（最多挂个「新消息」提示）
  void followIncoming("applyIncoming");
  reportRead(seq);
  // 角标不在这里刷：消息到屏不等于服务端已读到（未读数由 markRead 清零），
  // 真正的重算在 readReporter 的 onRead 回调里
}

/**
 * IM（box 通道）私聊消息的节流增量拉取。
 *
 * <p>box 的推送体不带 kean 的会话序号语义，且和自研通道可能几乎同时到达同一批消息；
 * 逐条触发增量会把一个「连发 5 条」变成 5 次请求，所以合并到 400ms 内只拉一次。</p>
 */
let incomingSyncTimer: ReturnType<typeof setTimeout> | null = null;
const INCOMING_SYNC_DELAY_MS = 400;

/**
 * 收到 MESSAGE 事件（两条通道都走这里）。{@code eventSessionId} 是事件顶层的会话 id
 * （IM 通道会把 {@code data.sessionId} 也镜像到顶层），两者取到哪个用哪个。
 *
 * <p><b>取舍：不做「直接插气泡」，改为按本地游标拉一次增量</b>，原因有三：
 * 1) 推送来的那份数据没有 kean 的消息 id，直接插会与随后 HTTP/增量拉回来的同一条重复，
 *    而只有增量接口返回的是权威版本（带 id/seqNo/status）；
 * 2) 乱序：推送与本地 outbox 里的乐观消息、以及正在发送的那条需要一个统一的合并点，
 *    {@code syncIncoming} → {@code mergeIncoming} 就是那个点（按 localId 用服务端版本替换本地那条）；
 * 3) 与两条通道并存时的行为一致：自研通道带完整消息体时仍照旧直接插入（下面 applyIncoming 那条），
 *    只有「没有 id / 不是权威版本」的推送才退化成拉增量。</p>
 *
 * <p>会话 id 对不上的推送直接忽略 —— 后端未上线 {@code sessionId} 字段时就是这个分支，
 * 表现为「只刷未读角标、气泡等轮询补齐」，与本轮之前的现状一致。</p>
 *
 * <p>参数故意收 {@code unknown}：两条通道（自研 WS / box IM）投递过来的 {@code data}
 * 在类型上是并集，这里显式收窄一次，避免在调用点写容易出错的断言。</p>
 */
function handleMessageEvent(data: unknown, eventSessionId?: number) {
  if (!data || typeof data !== "object") {
    return;
  }
  const payload = data as Partial<ChatMessageItem> & { sessionId?: unknown };
  const target = Number(payload.sessionId || eventSessionId || 0);
  if (!target || target !== sessionId.value) {
    return;
  }
  const seq = Number(payload.seqNo || 0);
  // 权威版本（带消息 id，且有 seqNo 可排序）仍按原逻辑立即上屏；
  // 其余（例如 IM 通道里 id 缺席的那份）交给增量拉取，拿权威版本再合并。
  if (Number(payload.id || 0) !== 0 && seq > 0) {
    applyIncoming(data as Partial<ChatMessageItem>);
    return;
  }
  const cursor = Number(getLastSeq(sessionId.value) || 0);
  if (cursor <= 0) {
    // 没有游标就没有增量可拉：只有带 localId 的推送（发送方回显 / 自己别的端发的）才插入，
    // 它能和本地乐观消息按 localId 去重；其余情况宁可不插 —— 轮询兜底会走整页刷新，
    // 消息不会丢，但不会出现「没有 id、排不了序」的野气泡。
    if (payload.localId) {
      applyIncoming(data as Partial<ChatMessageItem>);
    }
    return;
  }
  if (incomingSyncTimer) {
    return;
  }
  incomingSyncTimer = setTimeout(() => {
    incomingSyncTimer = null;
    void syncIncoming();
  }, INCOMING_SYNC_DELAY_MS);
}

/**
 * 本地还没被服务端确认的消息（乐观插入或从 outbox 恢复的失败消息）。
 *
 * <p>它是「气泡只增不灭」的守卫：整页刷新（{@link loadFirstPage}）会把列表
 * 换成服务端那一页，而断网时本地那条<b>服务端根本没有</b> —— 不把它挑出来重挂，
 * 就会「刷新一下气泡就没了」。用户在断网时看到气泡消失，自然会以为消息丢了。</p>
 */
function unsentLocalMessages(): ChatMessageItem[] {
  return messages.value.filter((item) => {
    if (item.localId && isOptimistic(item)) {
      return true;
    }
    // 兜底：会话 id 没写上的本地条目（老数据）也算，宁可多留一条也不吞掉用户的消息
    return Number(item.id || 0) < 0;
  });
}

/** 首次进入没有本地游标时，仍然走原来的分页接口 */
async function loadFirstPage() {
  const data = await listChatMessages(sessionId.value, 1, 50);
  const rows = (data?.list || []).map((item) => ({ ...item, sessionId: sessionId.value }));
  // 服务端那一页 + 本地未确认的那些：合并而不是替换，避免整页刷新把乐观气泡冲掉
  const keepLocal = unsentLocalMessages();
  logSend("assign:loadFirstPage", {
    before: messages.value.length,
    rows: rows.length,
    keepLocal: keepLocal.length
  });
  messages.value = sortTimeline(mergeIncoming(rows, keepLocal));
  logSend("assign:loadFirstPage.done", { after: messages.value.length, displayLen: displayMessages.value.length });
  const seq = maxSeqOf(rows);
  // 老后端没有 seqNo 时游标保持 0，后续不再走增量接口，退回「每次全量拉第一页」的旧行为
  if (seq > 0) {
    setLastSeq(sessionId.value, seq);
  }
  const at = maxCreatedAtOf(rows);
  if (at > 0) {
    setReadAt(sessionId.value, at);
  }
}

/** 恢复上次没发成功的消息：只恢复「失败」态，真正在发送中的会随页面销毁重来 */
function restoreOutbox() {
  const pending = listOutbox(sessionId.value);
  logSend("restoreOutbox", { pending: pending.length, before: messages.value.length });
  if (!pending.length) {
    return;
  }
  const rows: ChatMessageItem[] = pending.map((entry) => ({
    id: -Math.floor(Math.random() * 1000000) - 1,
    sessionId: sessionId.value,
    senderId: myUserId.value,
    msgType: entry.msgType,
    content: entry.content,
    url: entry.msgType === "IMAGE" ? entry.content : null,
    createdAt: new Date(entry.updatedAt || Date.now()).toISOString(),
    mine: true,
    localId: entry.localId,
    sendState: "failed" as ChatSendState
  }));
  messages.value = sortTimeline(mergeIncoming(messages.value, rows));
  logSend("assign:restoreOutbox", { after: messages.value.length, displayLen: displayMessages.value.length });
}

/**
 * 拉取并铺满首屏。
 *
 * @param enter true = 首次进入会话（onLoad）；false = 轮询兜底 / 回前台的全量刷新。
 *              两者的滚动规则不同（见下），但拉数据的分支完全一样。
 */
async function loadMessages(enter = false) {
  await loadFirstPage();
  restoreOutbox();
  // 进入会话即上报已读（有 seqNo 带 maxSeq，没有就退回老口径）；后续更大的值走 1 秒节流。
  // 角标不在这里刷：必须在服务端确认已读后再重算，否则 markRead 失败时角标会假清零。
  chatRead();
  await nextTick();
  // ⭐ 首次进入：溢出就到底（看到最新一条），没布满就一动不动；
  //    后台刷新：只有用户本来就在底部附近才跟随，翻着历史时不许把他拽走。
  if (enter) {
    await followOwnMessage("enter");
    return;
  }
  await followIncoming("refresh");
}

/**
 * 乐观插入本地消息（幂等）。
 *
 * <p>两条硬性约束：</p>
 * <ol>
 *   <li><b>同步执行</b>：调用点必须在任何 await / 任何守卫之前，按下发送气泡立刻可见；</li>
 *   <li><b>不经过任何会话过滤</b>：这里是列表的直接写入，只做 mergeIncoming（按 localId 去重）
 *       + sortTimeline，sessionId 只作为字段写入，不参与筛选 —— 断网、会话 id 迟到都不影响上屏。</li>
 * </ol>
 *
 * <p>同一个 localId 重复调用只会返回已存在那条的 id，不会产生第二个气泡
 * （图片先插入、随后 sendWithOutbox 再插一次就是走这条幂等分支）。</p>
 */
function insertLocalMessage(input: {
  localId: string;
  content: string;
  msgType: string;
  sendState?: ChatSendState;
}): number {
  logSend("insertLocal.enter", {
    localId: input.localId || "",
    hasLocalId: Boolean(input.localId),
    msgType: input.msgType,
    len: input.content.length,
    before: messages.value.length
  });
  if (!input.localId) {
    // 没有 localId 就没有去重键：这里不是「本地乐观消息」的入口，直接不插
    logSend("insertLocal.return", { reason: "skip:empty-localId", messagesLen: messages.value.length });
    return 0;
  }
  const existed = messages.value.find((item) => String(item.localId || "") === input.localId);
  if (existed) {
    logSend("insertLocal.return", {
      reason: "skip:duplicate",
      id: existed.id,
      messagesLen: messages.value.length
    });
    return Number(existed.id);
  }
  const payload: ChatSendPayload = {
    localId: input.localId,
    content: input.content,
    msgType: input.msgType,
    attempts: 0
  };
  const local = buildLocalMessage(payload, {
    url: input.msgType === "IMAGE" ? input.content : null,
    sendState: input.sendState
  });
  const row = { ...local, sessionId: sessionId.value, senderId: myUserId.value };
  messages.value = sortTimeline(mergeIncoming(messages.value, [row]));
  // 插入后立刻对比「数组长度」与「实际渲染的 computed 长度」：
  // 两个数不一致就说明渲染侧把它过滤掉了。
  logSend("insertLocal.return", {
    reason: "inserted",
    id: row.id,
    messagesLen: messages.value.length,
    displayLen: displayMessages.value.length
  });
  nextTick(() => {
    logSend("insertLocal.nextTick", {
      messagesLen: messages.value.length,
      displayLen: displayMessages.value.length
    });
    // ⚠️ 这里以前是**无条件**滚到底，正是「消息没充满聊天框也被拉到底」的成因之一。
    //    它当初被加上来是为了修「断网发送看不到气泡」（离线只走这条乐观插入路径，
    //    联网时还能蹭 applyIncoming 的滚动）——那个诉求由「溢出就一定滚」原样保住：
    //    列表真的溢出时照旧滚到底，没溢出时新气泡本来就在视口里，不需要任何滚动。
    void followOwnMessage("insertLocal");
  });
  return row.id;
}

/** 重试用尽：把本地那条标成失败（红色感叹号可手动重发）。只改角标，绝不移除气泡 */
function markLocalFailed(localId: string) {
  const found = messages.value.some((item) => item.localId === localId);
  logSend("markLocalFailed", { localId: localId || "", found, len: messages.value.length });
  messages.value = messages.value.map((item) =>
    item.localId === localId ? { ...item, sendState: "failed" as ChatSendState } : item
  );
  logSend("markLocalFailed.done", { localId: localId || "", len: messages.value.length, found });
}

/**
 * 服务端确认落库、但回执里没有消息体（老后端）：此时气泡已不可删除，
 * 若 outbox 里没有这条（原实现只在失败时落盘），「重发」会取不到内容而假失败。
 * 这里补写一份原始内容，让重发路径依然可用。
 */
function rememberOutboxContent(entry: { localId: string; msgType: string; content: string; attempts: number }) {
  if (getOutboxEntry(sessionId.value, entry.localId)) {
    return;
  }
  upsertOutbox({
    localId: entry.localId,
    sessionId: sessionId.value,
    content: entry.content,
    msgType: entry.msgType,
    attempts: entry.attempts,
    updatedAt: Date.now()
  });
}

/** 服务端确认发送但没有消息体（老后端）：本地那条改成「已发送」 */
function markLocalSent(localId: string) {
  logSend("markLocalSent", { localId: localId || "" });
  messages.value = messages.value.map((item) =>
    item.localId === localId ? { ...item, sendState: undefined, status: item.status ?? 1 } : item
  );
}

/**
 * 重发时本地没有可重发的原始内容（例如这条其实已经落库、只是回执里没有消息体）。
 * 此时不能假装重发成功、也不能让气泡永远停在"发送中"：
 * 回到「已发送」角标并如实提示，用户可以重新输入内容发送。
 */
function markResendUnavailable(localId: string) {
  markLocalSent(localId);
  toast.info("这条已发出，无法再次发送");
}

/**
 * 发送成功回执：用服务端版本按 localId 替换本地那条乐观消息（清掉 sendState）。
 * 不走 upsertMessages —— 那条路径会按 sessionId 过滤，老后端不回 sessionId 时会整行丢弃，
 * 本地那条就永远停在"发送中"并盖住对勾。
 */
function confirmIncoming(message?: ChatMessageItem) {
  if (!message || typeof message !== "object") {
    return;
  }
  const row = { ...message, sessionId: sessionId.value };
  logSend("confirmIncoming", { id: row.id, localId: row.localId || "", seqNo: Number(row.seqNo || 0), status: row.status });
  messages.value = mergeConfirm(messages.value, [row]);
  logSend("confirmIncoming.done", { len: messages.value.length, displayLen: displayMessages.value.length });
  const seq = Number(row.seqNo || 0);
  if (seq > 0) {
    setLastSeq(sessionId.value, seq);
  }
}

/**
 * 「进行中」状态的看门狗。
 *
 * <p>一次发送最坏耗时 = 3 次网络尝试 × 15s 请求超时 + 两次退避(400/800ms)，约 47s；
 * 兜底时限 {@link SEND_FALLBACK_DEADLINE_MS} 是 32s。所以 60s 足够覆盖任何正常路径。</p>
 *
 * <p>为什么必须有：{@code inflight} / {@code sendingIds} 一旦因任何意外没被清掉，
 * 界面就会永久停在「发送中」，而用户看到的只是「点了没反应」。宁可误清一次（只是
 * loading 停早了一点，消息本身不受影响，气泡与失败角标照旧），也不能让界面永久卡死。</p>
 */
const BUSY_WATCHDOG_MS = 60000;
let busyWatchdogTimer: ReturnType<typeof setTimeout> | null = null;

function armBusyWatchdog() {
  if (busyWatchdogTimer) {
    return;
  }
  busyWatchdogTimer = setTimeout(() => {
    busyWatchdogTimer = null;
    if (inflight.value > 0 || sendingIds.value.length > 0) {
      console.warn("[kean-send] busy watchdog fired: 发送中状态超过上限未清，强制复位", {
        inflight: inflight.value,
        sendingIds: sendingIds.value.length
      });
      inflight.value = 0;
      sendingIds.value = [];
    }
    if (sending.value) {
      sending.value = false;
    }
  }, BUSY_WATCHDOG_MS);
}

function clearBusyWatchdog() {
  if (!busyWatchdogTimer) {
    return;
  }
  // 只有在确实没有在飞请求/在途重发时才取消，否则下一轮发送就没有保护了
  if (inflight.value <= 0 && sendingIds.value.length === 0 && !sending.value) {
    clearTimeout(busyWatchdogTimer);
    busyWatchdogTimer = null;
  }
}

async function sendPayload(payload: ChatSendPayload, confirm: (localId: string, message?: ChatMessageItem) => void) {
  const localId = String(payload.localId || "");
  if (!localId) {
    logSend("sendPayload.return", { reason: "no-localId" });
    return;
  }
  // 计数与递减在同一个函数里配对，无论从哪条路径进来都不会漏减（loading 不会卡住）
  inflight.value += 1;
  armBusyWatchdog();
  logSend("sendPayload.enter", { localId, msgType: payload.msgType, inflight: inflight.value, sessionId: sessionId.value });
  try {
    // 重试始终复用同一个 localId，靠服务端幂等去重
    const result = await sendWithOutbox(sendContext, payload);
    logSend("sendPayload.result", { localId, ok: result.ok, inserted: result.inserted, hasMessage: Boolean(result.message) });
    if (result.ok) {
      if (result.message) {
        confirmIncoming(result.message);
      } else {
        // 没带回消息体的老后端：把本地那条标成「已发送单勾」，否则会一直显示发送中
        markLocalSent(localId);
      }
      confirm(localId, result.message);
      // 自己发成功：溢出才滚到底（没布满时不动，见 followOwnMessage）
      void followOwnMessage("sendPayload");
      logSend("sendPayload.return", {
        reason: "ok",
        len: messages.value.length,
        displayLen: displayMessages.value.length
      });
      return;
    }
    // 失败只换角标：气泡留在原地（红色感叹号，可点击/长按重发）
    logSend("sendPayload.return", {
      reason: "failed",
      inserted: result.inserted,
      len: messages.value.length,
      displayLen: displayMessages.value.length
    });
    if (result.inserted) {
      markLocalFailed(localId);
      toast.error("消息发送失败，长按气泡或点感叹号可重发");
      return;
    }
    // 极端情况：气泡没能显示出来（列表异常），此时标"失败"会是隐形的，如实提示重发
    toast.error("消息未能显示，请重新发送");
  } catch (error) {
    // 兜底：任何意外都让这条消息进入失败态，而不是留下一个永远「发送中」的气泡
    logSend("sendPayload.throw", { localId, error: String((error as Error)?.message || error) });
    markLocalFailed(localId);
    toast.error((error as Error)?.message || "消息发送失败，长按气泡可重发");
  } finally {
    // 这里的 finally + chatSync 内部的兜底超时，共同保证按钮 loading 一定会被清掉
    inflight.value = Math.max(0, inflight.value - 1);
    clearBusyWatchdog();
  }
}

const sendContext: ChatSendContext = {
  sessionId: sessionId.value,
  onOutbox: (entry) => {
    rememberOutboxContent(entry);
  },
  insertLocal: insertLocalMessage
};

/** 发送前必做：气泡先落地（同步、不受任何守卫影响），再进网络阶段 */
function enqueueLocalMessage(payload: ChatSendPayload) {
  const localId = payload.localId || createLocalId();
  payload.localId = localId;
  logSend("enqueue.enter", { localId, msgType: payload.msgType, len: payload.content.length });
  const insertedId = insertLocalMessage({
    localId,
    content: payload.content,
    msgType: payload.msgType,
    sendState: "sending"
  });
  logSend("enqueue.exit", { localId, insertedId, messagesLen: messages.value.length });
  return localId;
}

/**
 * 点「发送」。
 *
 * <p><b>这里绝不因为「上一条还在发」而丢弃本次输入</b>：{@code content} 已在同步阶段清空，
 * 用户看到输入框空了却什么都没有发生，是比重复提交严重得多的缺陷。
 * 重复提交本身由两点兜住：① 输入框内容清空后第二次点击走 {@code !text} 直接返回；
 * ② 服务端按 {@code localId} 幂等去重。</p>
 */
async function handleSend() {
  // 第一行埋点：能区分「点击根本没进来」（wd-button 吞掉 / 按钮不可点）与「进来了但没插气泡」
  logSend("click:send", {
    len: content.value.length,
    sessionId: sessionId.value,
    busy: busy.value,
    inflight: inflight.value,
    sending: sending.value,
    blocked: sendBlocked.value
  });
  if (sendBlocked.value) {
    toast.error(sendBlocked.value);
    return;
  }
  const text = content.value.trim();
  if (!text) {
    logSend("click:send.skip", { reason: "empty-text" });
    return;
  }
  const payload = createOutboxPayload(text, "TEXT");
  content.value = "";
  // 发送后收起表情面板：输入框已清空，还挂着面板没有意义
  closeEmojiPanel();
  // ⭐ 乐观插入在任何 await / 任何守卫之前：点一下必然出现气泡
  enqueueLocalMessage(payload);
  if (!sessionId.value) {
    // 会话 id 还没就绪（onLoad 未完成）：直接给失败态 + 可重发，绝不静默丢弃
    logSend("click:send.skip", { reason: "no-sessionId", localId: payload.localId });
    markLocalFailed(payload.localId as string);
    toast.error("会话信息未就绪，请重新进入会话");
    return;
  }
  await sendPayload(payload, () => undefined);
}

function handleSendImage() {
  if (sendBlocked.value) {
    toast.error(sendBlocked.value);
    return;
  }
  // 系统选图弹层起来之前先收面板（面板是 fixed 的，留着会和系统弹层打架）
  closeEmojiPanel();
  uni.chooseImage({
    count: 1,
    sizeType: ["original"],
    sourceType: ["album", "camera"],
    success: async (res) => {
      const filePath = res.tempFilePaths?.[0];
      if (!filePath) {
        return;
      }
      const payload = createOutboxPayload(filePath, "IMAGE");
      // ① 气泡先出现（本地临时路径直接能显示），再谈上传
      enqueueLocalMessage(payload);
      sending.value = true;
      armBusyWatchdog();
      try {
        const uploaded = await uploadFile(filePath, "CHAT", { onProgress });
        // 本地乐观消息换成 objectKey，重发时就不需要再依赖临时文件
        payload.content = uploaded.objectKey;
        sending.value = false;
        await sendPayload(payload, (localId) => {
          messages.value = messages.value.map((item) =>
            item.localId === localId ? { ...item, content: uploaded.objectKey } : item
          );
        });
      } catch (error) {
        sending.value = false;
        // 上传阶段失败只标本地失败态：本地文件路径没法当消息内容入库，
        // 重启后"重发"也没意义（临时文件可能已被清理），让用户重新选图更诚实。
        markLocalFailed(payload.localId as string);
        toast.error((error as Error).message || "图片发送失败");
      } finally {
        resetUpload();
        clearBusyWatchdog();
      }
    },
    // 取消选图时也要把上传中状态收掉，否则 sending 会一直为真、按钮永远转圈
    fail: () => {
      sending.value = false;
      resetUpload();
      clearBusyWatchdog();
    }
  });
}

function previewImage(url?: string | null, content?: string) {
  const src = resolveMediaUrl(url || content);
  if (!src) {
    return;
  }
  uni.previewImage({ urls: [src], current: src });
}

/** 是否已经是服务端 objectKey（而不是仍在本地、需要重新上传的 blob / 临时文件） */
function isUploadedSource(source: string): boolean {
  if (!source) {
    return false;
  }
  return !/^(blob:|data:|file:|wxfile:|filesystem:|https?:\/\/localhost|.*\/_doc\/|.*_tmp_)/i.test(source);
}

/** 失败消息的重发入口：长按气泡或用感叹号角标触发 */
function handleResend(item: ChatMessageItem) {
  if (statusOf(item) !== "failed") {
    return;
  }
  const localId = String(item.localId || "");
  if (!localId) {
    toast.info("该消息无法重发，请重新发送");
    return;
  }
  uni.showActionSheet({
    itemList: ["重发", "删除"],
    success: (res) => {
      if (res.tapIndex === 0) {
        void resendMessage(localId, item);
        return;
      }
      if (res.tapIndex === 1) {
        removeOutbox(sessionId.value, localId);
        messages.value = messages.value.filter((row) => row.localId !== localId);
      }
    }
  });
}

/**
 * 图片消息失败：内容还不是 objectKey（本地临时文件）时先重新上传再发送。
 * H5 的 blob: 地址在上传失败后往往已经失效，这时只能如实提示重新选图。
 */
async function resendMessage(localId: string, item: ChatMessageItem) {
  if (item.msgType === "IMAGE") {
    const source = String(item.url || item.content || "");
    if (/^(blob:|data:)/i.test(source)) {
      toast.info("图片已失效，请重新选择图片发送");
      return;
    }
    if (!isUploadedSource(source)) {
      sendingIds.value = sendingIds.value.concat(localId);
      sending.value = true;
      armBusyWatchdog();
      try {
        const uploaded = await uploadFile(source, "CHAT", { onProgress });
        messages.value = messages.value.map((row) =>
          row.localId === localId ? { ...row, content: uploaded.objectKey, url: uploaded.objectKey } : row
        );
        sending.value = false;
        await sendPayload({ localId, content: uploaded.objectKey, msgType: "IMAGE", attempts: 0 }, () => undefined);
      } catch (error) {
        sending.value = false;
        markLocalFailed(localId);
        toast.error((error as Error).message || "图片发送失败");
      } finally {
        sendingIds.value = sendingIds.value.filter((id) => id !== localId);
        sending.value = false;
        resetUpload();
      }
      return;
    }
  }
  // 重发前确保这条气泡还在：气泡只增不灭（用户主动删除除外），重发不该把它弄丢
  insertLocalMessage({
    localId,
    content: String(item.content || ""),
    msgType: item.msgType,
    sendState: "sending"
  });
  sendingIds.value = sendingIds.value.concat(localId);
  armBusyWatchdog();
  try {
    const result = await retryOutboxEntry(sendContext, localId);
    if (result.ok && result.message) {
      // 与首发同一条路径：按 localId 用服务端版本替换本地那条（清掉"发送中"）
      confirmIncoming(result.message);
      // 重发成功：同样只在列表溢出时才滚到底
      void followOwnMessage("resend");
    } else if (result.ok) {
      // 老后端没回消息体：本地那条改成单勾，别一直卡在"发送中"
      markLocalSent(localId);
    } else if (getOutboxEntry(sessionId.value, localId)) {
      markLocalFailed(localId);
      toast.error("重发失败，请检查网络后重试");
    } else {
      // outbox 里没有原始内容（例如上次其实已落库、只是回执丢了消息体）：
      // 如实告诉用户无法重发，并回到「已发送」角标，不留下一条永远"发送中"的气泡
      markResendUnavailable(localId);
    }
  } finally {
    sendingIds.value = sendingIds.value.filter((id) => id !== localId);
    clearBusyWatchdog();
  }
}

/**
 * ⚠️ 本轮已删除 `markMineReadLocal` / `applyReadReceipt` 两个函数
 * （以及 `useLiveUpdates` 里的 `READ` 分支）：
 *
 * 它们的**唯一**作用是「收到对方已读 → 把我发出的气泡标成双勾」，
 * 也就是「对方已读」这条信息的渲染。气泡不再展示它之后，这两个函数与
 * `chatMerge.markMineRead` 一起成为死代码，留着只会让人误以为「已读还在驱动界面」。
 *
 * ⚠️ 刻意**不删**的是「已读上报」（`createReadReporter` / `chatRead` / `readReporter.note`）：
 * 它服务于**自己的未读角标**，与双勾是两条完全不同的链路。
 */

function handleReportUser() {
  const peerId = session.value?.peerUserId;
  if (!goReport("USER", peerId, session.value?.peerNickname)) {
    return;
  }
}

function handleReportLastMessage() {
  const item = [...messages.value].reverse().find((msg) => !resolveMine(msg));
  if (!item) {
    toast.info("暂无对方消息可举报");
    return;
  }
  goReport("MESSAGE", item.id, session.value?.peerNickname || "对方消息");
}

function handleBlockPeer() {
  const peerId = session.value?.peerUserId;
  if (!peerId) {
    return;
  }
  uni.showModal({
    title: "加入黑名单",
    content: `加入黑名单后，你将无法再与 ${session.value?.peerNickname || "对方"} 互发消息，确定继续？`,
    success: async (res) => {
      if (!res.confirm) {
        return;
      }
      try {
        await blockUser(peerId);
        toast.success("已加入黑名单");
        setTimeout(() => uni.navigateBack(), 400);
      } catch (error) {
        toast.error((error as Error).message || "加入黑名单失败");
      }
    }
  });
}

onLoad(async (query) => {
  sessionId.value = Number(query?.id || 0);
  if (!sessionId.value) {
    toast.error("会话不存在");
    return;
  }
  // 会话 id 到位后才绑定发送上下文与已读上报器
  sendContext.sessionId = sessionId.value;
  readReporter.reset();
  try {
    if (userStore.isLoggedIn.value) {
      try {
        const latest = await fetchMe();
        if (userStore.state.token) {
          userStore.setLogin(userStore.state.token, latest);
        }
      } catch {
        // 使用本地缓存
      }
    }
    await loadSession();
    // enter = true：首次进入会话的滚动规则（溢出才到底）与后台刷新不同
    await loadMessages(true);
  } catch (error) {
    toast.error((error as Error).message || "加载失败");
  }
});

onShow(() => {
  // 回到前台：带上 afterSeq 增量补拉积压的消息
  if (sessionId.value) {
    void syncIncoming();
    // 进会话（首次 onLoad 后紧跟 onShow）也走这里兜一次：loadMessages 抛错时
    // 至少还能按"当前已显示的最大 seqNo"上报一次已读，未读数不会一直挂着。
    // note() 自带幂等（没有更大的 seqNo 就什么都不发），不会重复打接口。
    chatRead();
  }
});

onHide(() => {
  closeEmojiPanel();
  readReporter.flush();
});

onUnload(() => {
  closeEmojiPanel();
  readReporter.flush();
});

/**
 * ④ 的收尾：用户自己把页面滚回底部附近时，把「有新消息」提示收掉（微信同款）。
 *
 * <p>⚠️ 这条钩子**不参与任何滚动决策**（滚不滚还是由 {@link decideScrollToBottom} 全权判断），
 * 只在提示已经挂着的时候量一次几何，用来判断「用户自己滚到底了没」。所以它：
 * ① 提示没挂着时立刻 return，平时零开销；② 120ms 节流，不会每次滚动事件都写状态；
 * ③ 没有任何新状态机 —— 只可能把一个 boolean 置回 false。</p>
 */
let newMsgTipCheckedAt = 0;
onPageScroll(() => {
  if (!newMessagePending.value) {
    return;
  }
  const now = Date.now();
  if (now - newMsgTipCheckedAt < NEW_MSG_TIP_SCROLL_MS) {
    return;
  }
  newMsgTipCheckedAt = now;
  void measureChatScrollBox().then((box) => {
    if (box && isNearBottom(box)) {
      newMessagePending.value = false;
    }
  });
});

useLiveUpdates((event) => {
  console.log("[kean-rt] page handler", {
    kind: event ? event.type || "unknown" : "poll",
    sessionId: event?.sessionId,
    maxSeq: event?.maxSeq
  });
  if (!event) {
    // 轮询兜底（WS 断线时）：优先增量，拿不到增量再退回整页刷新
    if (sessionId.value) {
      syncIncoming()
        .then((outcome) => {
          // 只有"没有游标 / 接口失败"才退回整页刷新，避免每次轮询都重拉 50 条
          if (outcome === "skip" && !getLastSeq(sessionId.value)) {
            // 断网时这两个请求必然失败：显式捕获，否则会变成一条看不到来源的未捕获拒绝
            void loadSession().catch((error) => {
              console.log("[kean-rt] poll loadSession failed", { error: String((error as Error)?.message || error) });
            });
            void loadMessages().catch((error) => {
              console.log("[kean-rt] poll loadMessages failed", { error: String((error as Error)?.message || error) });
            });
          }
        })
        .catch((error) => {
          console.log("[kean-rt] poll syncIncoming failed", { error: String((error as Error)?.message || error) });
        });
    }
    return;
  }
  if (event.type === "MESSAGE") {
    handleMessageEvent(event.data, event.sessionId);
    return;
  }
  // ⚠️ 本轮<b>移除</b> READ 分支（原来调 applyReadReceipt → markMineRead 标双勾）：
  //    气泡不再展示「对方已读」，收到 READ 事件就<b>不做任何事</b>（落到函数末尾自然结束）。
  //    这里刻意不留空分支、也不打日志：一是「不订阅/不处理」才是不再依赖实时已读的证据，
  //    二是这两条通道的 READ 本来就只来自自研 WS（box 的 imSocket 恒定不产生 READ），
  //    服务端 READ 推送另有 kean.im.read-receipt-enabled 单独把关（默认仍推）。
  if (event.type === "NOTICE" && (event.noticeType === "PEER_BANNED" || event.noticeType === "PEER_UNBANNED")) {
    if (!event.bizId || Number(event.bizId) === sessionId.value) {
      loadSession();
    }
  }
});

/**
 * 全局兜底埋点：离线分支抛出的未捕获异常此前在 H5 上完全不可见
 * （乐观插入若在某处抛错，用户只看到「点了没反应」）。
 */
onMounted(() => {
  logSend("mounted", { sendingHint: sendingHint.value });
  if (typeof window === "undefined") {
    return;
  }
  window.addEventListener(
    "unhandledrejection",
    (event: PromiseRejectionEvent) => {
      const reason = event?.reason;
      console.error("[kean-send][uncaught] unhandledrejection", {
        message: String((reason as Error)?.message || reason),
        stack: String((reason as Error)?.stack || "").split("\n").slice(0, 4).join(" | "),
        messagesLen: messages.value.length
      });
    },
    true
  );
  window.addEventListener(
    "error",
    (event: ErrorEvent) => {
      console.error("[kean-send][uncaught] error", {
        message: String(event?.message || ""),
        source: String(event?.filename || ""),
        line: event?.lineno,
        col: event?.colno,
        messagesLen: messages.value.length
      });
    },
    true
  );
});
</script>

<template>
  <view class="page" :class="{ 'has-peer-status': Boolean(peerNotice) }">
    <view v-if="peerNotice" class="peer-status" :class="{ banned: peerBanned }">{{ peerNotice }}</view>
    <view class="list" @click="closeEmojiPanel">
      <view v-for="item in displayMessages" :key="item.id">
        <!-- 时间提示：与 box 的 chat-message-item 同构 —— 整行居中、不画头像也不画气泡
             （box 的 TIP_TIME 与 TIP_TEXT 共用同一个 .message-tip 样式，故下面两条同款） -->
        <view v-if="item.showTime" class="stamp">
          <view class="stamp-inner">{{ formatChatTime(item.createdAt) }}</view>
        </view>
        <!-- 撤回提示：文案复用会话列表已有、且 zh/en 同序的 msgPreviewRecall
             （utils/i18n.ts:159 / :421，无占位符所以走 t() 而不是 tf()） -->
        <view v-if="item.recalled" class="stamp">
          <view class="stamp-inner">{{ t("msgPreviewRecall") }}</view>
        </view>
        <!-- 昵称显示规则（对齐 box）：box 只在 message.groupId && !message.selfSend
             （群聊 + 别人发的）时才画 .top 里的 .name，而且是**逐条都画**、不做
             「同侧连续消息省略昵称」那种收拢；本页只有私聊，所以昵称恒不渲染。
             将来接群聊时按同一判据在这里补一块 .top 即可，不需要改数据层。 -->
        <view v-else class="row" :class="{ mine: item.mine }">
          <view class="avatar" @click="openUser(item.mine ? userStore.state.user?.id : session?.peerUserId, item.mine)">
            <FallbackImage
              v-if="item.mine ? myAvatar : peerAvatar"
              class="avatar-img"
              :src="item.mine ? myAvatar : peerAvatar"
              mode="aspectFill"
            />
            <text v-else>{{ ((item.mine ? userStore.state.user?.nickname : session?.peerNickname) || "同").slice(0, 1) }}</text>
          </view>
          <view
            class="bubble"
            :class="{ image: item.msgType === 'IMAGE' }"
            @longpress="handleResend(item)"
          >
            <FallbackImage
              v-if="item.msgType === 'IMAGE'"
              class="photo"
              :src="resolveMediaUrl(item.url || item.content)"
              mode="widthFix"
              @click="previewImage(item.url, item.content)"
            />
            <!-- 文字气泡：已知短代码（如 [微笑]）渲染成与行高对齐的内联小图，
                 未知的方括号内容、URL、@ 之类一律原样显示（只有 image 段走图） -->
            <!-- ⭐ 消息类型分支的**唯一扩展点**就在这里（box 的 chat-message-item 同款结构）。
                 与 box 的类型对照 / 暂未支持的类型：
                   · TEXT     已支持（本分支；box 用 rich-text + $emo.transform 渲染表情）
                   · IMAGE    已支持（上面那个分支；box 的 .message-image）
                   · TIP_TIME 已支持（列表外的 .stamp 居中提示，box 的 .message-tip）
                   · RECALL   已支持（列表外的 .stamp 居中提示，判据 msgType/status=2）
                   · FILE     暂不支持 —— box 是 .message-file（白底、圆角 20rpx=10px、
                              min-height 120rpx=60px、内边距 30rpx=15px、右下角文件图标）
                   · AUDIO    暂不支持 —— box 是 .message-audio（复用气泡外形 + 播放图标
                              34rpx=17px + 时长文本），需要 uni.createInnerAudioContext
                   · ACT_RT_VOICE / ACT_RT_VIDEO  暂不支持 —— box 是 .chat-realtime
                              （flex + gap 16rpx=8px + 图标 40rpx=20px），要接音视频通话页
                 ⚠️ 刻意**不**为未知类型加任何 v-else-if 分支：这段 v-else 必须继续把
                    「不是 IMAGE 的一切」按文本渲染 —— 历史/异常 msgType（空串、小写 text）
                    一旦被判定成「类型不认识」，就会把正文整条吞掉，这是数据兼容问题，
                    不是样式问题。（预留的是上面的分支点与规格，不是新的判据。） -->
            <view v-else class="text">
              <template v-for="(segment, segIndex) in messageSegments(item)" :key="segIndex">
                <FallbackImage
                  v-if="segment.type === 'emoji'"
                  class="text-emoji"
                  :src="segment.path"
                  mode="aspectFit"
                />
                <template v-else>{{ segment.text }}</template>
              </template>
            </view>
          </view>
          <view
            v-if="item.mine && statusClass(item)"
            class="status"
            :class="statusClass(item)"
            @click="handleResend(item)"
          >{{ statusLabel(item) }}</view>
        </view>
      </view>
    </view>
    <!-- ④「有新消息」提示（微信/QQ 同款做法）：只在「列表确实溢出 **且** 用户不在底部附近」
         时才挂出来（newMessagePending 由 followIncoming 决定），点它一下直接到底。
         位置在右下、悬在输入栏之上；与 .composer/.actions/.panel 同层（.page 的直接子元素），
         所以**不含任何 transform/filter/backdrop-filter**，出现动画只用 opacity（见文件头铁律 2）。
         图标是 border 拼的实心下三角（本页不引图片素材、也不用 emoji 字符，同 .more-item__icon）。 -->
    <view v-if="newMessagePending" class="new-msg-tip" @click.stop="jumpToBottom">
      <view class="new-msg-arrow" />
    </view>
    <view class="actions">
      <text @click="handleReportUser">举报对方</text>
      <text @click="handleReportLastMessage">举报消息</text>
      <text @click="handleBlockPeer">加入黑名单</text>
    </view>
    <view v-if="sendBlocked" class="mute-tip" :class="{ banned: peerBanned }">{{ sendBlocked }}</view>
    <view v-else class="composer">
      <!-- 微信输入栏的从左到右顺序：[输入框 flex:1] [表情] [＋ / 发送]。
           语音/键盘切换那格因为本页不做语音而省略（见交付说明）。
           ⚠️ 这里没有任何 position/transform/backdrop-filter：.composer 是 .page 的直接子元素，
           一旦在它或 .page、.page 的任何祖先上引入「会创建 containing block」的属性，
           本页 6 个 fixed 元素（含这张输入栏本身与右下角的「新消息」提示）就会被劫持（仓库铁律 3）。 -->
      <!-- 多行输入：auto-height 随内容增高，高度上下限由 .input 的 min-/max-height 卡住（≈1~5 行），
           超出上限后由 overflow-y 内部滚动。仍是同一个 v-model 与同一个 @confirm（回车发送），
           类名仍是 .input —— insertEmoji 就是用 `.composer .input` 找这个原生 DOM 节点取光标的。 -->
      <textarea
        v-model="content"
        class="input"
        auto-height
        :maxlength="-1"
        confirm-type="send"
        placeholder="输入消息"
        @confirm="handleSend"
      />
      <!-- 表情钮在输入框右侧（微信顺序）；面板开着时由样式换成「键盘」图标。
           用 <view> 而不是 <text>：图标是 CSS 画的（::before / ::after + 描边），
           需要块级盒，且 title 只作悬停提示 —— 文案本身不丢，仍走 t('chatEmojiToggle')。 -->
      <view
        class="composer-icon"
        :class="{ active: showEmojiToggle }"
        @click.stop="toggleEmojiPanel"
      >
        <view v-if="showEmojiToggle" class="kb-icon" :title="t('chatEmojiToggle')" />
        <view v-else class="smile-icon" :title="t('chatEmojiToggle')" />
      </view>
      <!-- 「发送中」只做展示，不吞点击：原来那个 wd-button 在 loading=true 时不会派发 click，
           所以这个提示必须放在按钮外面，最右边那个按钮永远保持可点（重复提交由清空输入框
           + 服务端 localId 幂等兜住，绝不靠丢弃用户的点击来防）。 -->
      <text v-if="sendingHint" class="sending-tip">{{ sendingHint }}</text>
      <!-- ⭐ 最右侧一个**按钮位**：v-if / v-else 二选一（＋ ↔ 发送互斥替换），
           用 CSS animation（.action-swap）做淡入，所以是「替换」而不是硬跳。
           ⚠️ 刻意不用 <Transition>：Vue 的 Transition 是 Web 组件，
           本项目还有 mp-weixin 等小程序构建目标，那端拿不到 enter/leave 类；
           改用普通 class + keyframes 在两端行为一致。
           ⚠️ 也刻意不用 transform / scale 做这个过渡 —— 本页不许出现 transform（铁律 3），
           面板与遮罩的定位不能被任何 containing block 属性劫持。 -->
      <view class="composer-action">
        <view
          v-if="showSendAction"
          class="send-btn action-swap"
          @click.stop="handleSend"
        >发送</view>
        <view
          v-else-if="showPlusAction"
          class="more-btn action-swap"
          @click.stop="toggleMorePanel"
        />
        <view
          v-else
          class="more-btn keyboard action-swap"
          @click.stop="toggleMorePanel"
        />
      </view>
    </view>
    <!-- 「更多」面板（＋）：只做**呈现**，里面目前只有一项「相册」，
        它调用的就是原来那个 handleSendImage()（发图逻辑一个字没动，也没有新增上传入口）。
        文案复用会话列表已有的 msgPreviewImage（zh「[图片]」/ en「[Photo]」，
        utils/i18n.ts:158 与 :420，zh/en 同序，无占位符所以走 t() 而不是 tf()）——
        复用已有 key，不新增 i18n 条目。 -->
    <view v-if="moreOpen && !sendBlocked" class="panel panel-more" @click.stop @mousedown.prevent>
      <view class="panel-title">{{ t("chatEmojiTitle") }}</view>
      <view class="panel-grid">
        <view class="more-item" @click="handleAlbumFromMorePanel">
          <view class="more-item__icon"><view class="more-item__sun" /></view>
          <!-- 文案复用已有的 msgPreviewImage（zh「[图片]」/ en「[Photo]」，
               utils/i18n.ts:158 与 :420，zh/en 同序、无占位符 → 走 t() 不走 tf()） -->
          <text class="more-item__text">{{ t("msgPreviewImage") }}</text>
        </view>
      </view>
    </view>
    <!-- 表情面板：刻意放在 .composer **外面**（composer 是 position:fixed，
         面板留在里面就会跟着 fixed 定位一起跑），只贴住 composer 上沿，
         面板自身不含任何会创建 containing block 的属性；
         与「更多」面板共用 .panel 的定位与外观（两个面板互斥，不会同时出现） -->
    <view v-if="emojiOpen && !sendBlocked" class="panel panel-emoji" @click.stop @mousedown.prevent>
      <view class="panel-title">{{ t("chatEmojiTitle") }}</view>
      <scroll-view class="emoji-scroll" scroll-y>
        <template v-if="emojiRecentList.length">
          <view class="emoji-group-title">{{ t("chatEmojiRecent") }}</view>
          <view class="emoji-grid">
            <view
              v-for="(name, index) in emojiRecentList"
              :key="emojiCellKey(name, index)"
              class="emoji-cell"
              @click="insertEmoji(name)"
            >
              <FallbackImage class="emoji-face" :src="emojiPathOf(name)" mode="aspectFit" />
            </view>
          </view>
        </template>
        <view class="emoji-group-title">{{ t("chatEmojiTitle") }}</view>
        <view class="emoji-grid">
          <view
            v-for="(name, index) in EMOJI_NAME_LIST"
            :key="emojiCellKey(name, index)"
            class="emoji-cell"
            @click="insertEmoji(name)"
          >
            <FallbackImage class="emoji-face" :src="`/static/emoji/${index}.png`" mode="aspectFit" />
          </view>
        </view>
      </scroll-view>
    </view>
    <wd-toast />
  </view>
</template>

<style scoped>
/* ===========================================================================
 * 呈现层规格：全部数值来自 box-im 的权威参考实现（本机 .box-emoji-src/emoji-pack/）
 * ---------------------------------------------------------------------------
 *   · 气泡 / 头像 / 提示行：chat-message-item.vue.ref
 *   · 输入区 / 表情面板：   chat-box.vue.ref 的 .send-bar / .chat-emotion
 * box 用 rpx，本页用 px，按 750rpx = 375px 即 **rpx ÷ 2 = px** 换算，逐条写在注释里。
 *
 * ⚠️ 本文件**绝不**出现 backdrop-filter / transform / filter / will-change /
 *    contain / perspective：本页有 6 个 position: fixed 的元素（.peer-status /
 *    .composer / .actions / .mute-tip / .panel / .new-msg-tip）+ wd-toast 自带的 fixed 遮罩，
 *    它们的祖先都是 .page 这一层，一旦给 .page 或任何祖先加上这些属性，就会把
 *    fixed 后代劫持成「相对该容器定位」，弹层遮罩会点不到、关不掉（本链已翻车两次）。
 *    下面用到的 position: relative / absolute 只服务于气泡小尖角，
 *    既不是 containing block 触发属性（relative/absolute 不劫持 fixed 后代），
 *    .bubble 内也没有任何 fixed 后代。
 * =========================================================================== */
.page {
  /* 本方（我发出）气泡配色。box 用的是 $im-color-primary-light-2 底 + 白字，
     但那份 scss 变量源码还没取回（见交付说明的「需要我取回的文件」），
     所以这里沿用本页原有的浅蓝底 + 深蓝字，**不自己发明新色**；
     单点定义一次，免得气泡底色与小尖角颜色写两处走样。 */
  --kean-chat-self-bg: #b8d4ee;
  --kean-chat-self-text: #1e3a5c;
  min-height: 100vh;
  background: var(--kean-bg);
  /* 底部两条 fixed 栏占位：composer 56px + .actions ≈35px = 91px，再留 21px 余量
     （本轮把 composer 从 48px 抬到 56px：8 + 36 + 12） */
  padding-bottom: 112px;
}
.page.has-peer-status {
  padding-top: 48px;
}
.peer-status {
  position: fixed;
  left: 0;
  right: 0;
  top: 0;
  z-index: 30;
  margin: 0;
  padding: 12px 16px;
  background: #fff7e8;
  color: #d25f00;
  font-size: 13px;
  text-align: center;
  border-bottom: 1px solid #f2e3c6;
}
.peer-status.banned,
.mute-tip.banned {
  background: #fff1f0;
  color: var(--kean-danger);
  border-color: #fdcdc5;
}
.list {
  /* box 的 .chat-message-item 左右内边距 20rpx = 10px、上下 15rpx = 7.5px */
  padding: 8px 10px;
}
/* box 的 .message-tip（TIP_TIME 与 TIP_TEXT 共用）：外层 flex 居中，
   inner 撑到 80% 宽以内、word-break 兜底。box 的 inner 还有一层
   rgba(255,255,255,.3) 底色，那是给深色会话背景准备的 —— 在浅色底上等于没有，
   所以不照搬（也不另发明一个灰色底），仍是「居中灰字」。 */
.stamp {
  display: flex;
  justify-content: center;
  margin: 4px 0;
}
.stamp-inner {
  max-width: 80%;
  padding: 3px 10px;
  line-height: 18px;
  border-radius: 5px;
  color: #9aa4b2;
  font-size: 12px;
  text-align: center;
  word-break: break-word;
}
.row {
  display: flex;
  align-items: flex-start;
  /* box：.message-normal 的 padding-left 105rpx = 52.5px 是给头像留的缩进，
     头像占其中 80rpx = 40px，余下 12.5px，再加 .message-text 自己的
     margin-left 8rpx = 4px → 头像与气泡的实际间距 ≈ 16.5px，取 16px。
     上下间距来自 .chat-message-item 的 padding 15rpx×2 = 15px。 */
  gap: 16px;
  margin-bottom: 15px;
}
.row.mine {
  flex-direction: row-reverse;
}
.avatar {
  /* box 的 .message-normal 是 min-height: 80rpx = 40px，头像（size="small"）就是这 40px */
  width: 40px;
  height: 40px;
  border-radius: 50%;
  background: var(--kean-primary-soft);
  color: var(--kean-primary-active);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
  font-weight: 600;
  overflow: hidden;
  flex-shrink: 0;
}
.avatar-img {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  display: block;
}
.bubble {
  /* box 的气泡可用宽度：.message-normal 的头像缩进 105rpx = 52.5px +
     .bottom 的 80rpx = 40px 状态位预留 = 92.5px。本页是 flex 布局，
     这三段空间由「头像 40px + gap 16px + 状态位 40px」实际占掉，
     所以上限写成 calc(100% - 96px) —— 不给 68% 那种拍出来的比例。
     ⚠️ 状态位对**两侧都**预留，与 box 一致（box 的 .bottom 对非 mine 也留了 80rpx）。 */
  max-width: calc(100% - 96px);
  position: relative;
  background: var(--kean-card);
  /* box 的 .message-text：圆角 20rpx = 10px、内边距 16rpx 20rpx 16rpx 16rpx、
     行高 1.6、字号 $im-font-size = 30rpx = 15px、保留换行（white-space: pre-line） */
  border-radius: 10px;
  padding: 8px 10px 8px 8px;
  line-height: 1.6;
  font-size: 15px;
  word-break: break-word;
  white-space: pre-line;
}
/* box 每条气泡都带一个小尖角（:after），指向上一条同侧内容的方向。
   ⚠️ box **没有**「同侧连续消息圆角收拢」这套做法，所以这里也不自己发明 —— 见交付说明。
   数值照抄：left/right -10rpx = -5px、top 20rpx = 10px、border-width 12rpx = 6px。 */
.bubble::after {
  content: "";
  position: absolute;
  left: -5px;
  top: 10px;
  width: 0;
  height: 0;
  border-style: solid;
  border-color: transparent var(--kean-card) transparent transparent;
  border-width: 6px 6px 6px 0;
}
.row.mine .bubble {
  background: var(--kean-chat-self-bg);
  /* box 的 mine 分支：内边距左右镜像（16rpx 16rpx 16rpx 20rpx） */
  padding: 8px 8px 8px 10px;
}
.row.mine .bubble::after {
  left: auto;
  right: -5px;
  border-color: transparent transparent transparent var(--kean-chat-self-bg);
  border-width: 6px 0 6px 6px;
}
/* 图片气泡不走 .message-text 那条路（box 的 .message-image 没有白底、没有内边距、没有尖角），
   图片自己的圆角/阴影/尺寸由 .photo 负责 —— 两侧一致，不只是我方 */
.bubble.image {
  background: transparent;
  padding: 0;
}
.bubble.image::after {
  display: none;
}
.photo {
  display: block;
  /* box：maxSize = windowWidth * 0.6（即 60vw），历史无宽高数据时 min 100px；
     图片外框圆角 16rpx = 8px、阴影 0 2rpx 12rpx rgba(0,0,0,.06) */
  max-width: 60vw;
  min-width: 100px;
  border-radius: 8px;
  box-shadow: 0 1px 6px rgba(0, 0, 0, 0.06);
}
.text {
  color: var(--kean-text);
  font-size: 15px;
  line-height: 1.6;
  word-break: break-word;
}
/* 气泡里的表情：内联小图，vertical-align: middle 让它与 1.6 行高的文字对齐 */
.text-emoji {
  display: inline-block;
  width: 26px;
  height: 26px;
  margin: 0 1px;
  vertical-align: middle;
}
.row.mine .text {
  color: var(--kean-chat-self-text);
}
/* 我发出的消息的送达状态：发送中 / 单勾已发送 / ! 失败可重发
   ⚠️ 本轮移除 `.st-read`（双勾）样式：status 3 与 status 1 一样落到 .st-sent 单勾，
   见 utils/chatSync.ts 的 outgoingStatus / outgoingStatusLabel。
   ⚠️ 位置对齐 box：box 把「发送中」转圈与失败图标放在 .message-body 里，
   与气泡 align-items: center 垂直居中（.send-fail 另有 margin: 0 5rpx = 2.5px）。
   本页保持原有的「文字角标」实现（设计系统口径，见 styles/design-messages.css），
   只把对齐方式从贴气泡底边改成与气泡垂直居中。 */
.status {
  align-self: center;
  margin: 0 5px;
  color: #9aa4b2;
  font-size: 12px;
  line-height: 18px;
  white-space: nowrap;
}
.status.st-sending {
  color: #9aa4b2;
}
.status.st-sent {
  color: #8a9bb0;
}
.status.st-failed {
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border-radius: 9px;
  background: var(--kean-danger);
  color: #fff;
  font-weight: 700;
  text-align: center;
}
.actions {
  position: fixed;
  left: 0;
  right: 0;
  /* ⚠️ 必须等于 .composer 的总高度（8 + 36 + 12 = 56px）：
     这两个值加 .page 的 padding-bottom 是一组联动量，改一个就要同时改另外两个 */
  bottom: 56px;
  display: flex;
  justify-content: space-around;
  padding: 8px 12px;
  background: var(--kean-card);
  border-top: 1px solid var(--kean-line);
  color: var(--kean-primary);
  font-size: 13px;
}
.composer {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  display: flex;
  /* ⚠️ 用 center 而不是 flex-end：输入框长成多行时，表情钮与最右侧按钮跟着输入框垂直居中，
     与微信一致（微信多行输入时右侧两个钮也是居中，不贴底）。 */
  align-items: center;
  /* box 的 .send-bar：padding 10rpx（5px）+ padding-bottom 14rpx（7px）、
     图标之间 margin 0 10rpx（5px）→ 这里用 gap 等价表达。
     纵向取 8px / 12px：8 + 36 + 12 = **56px**，即微信那档 48~54px 的栏高，
     再叠 env(safe-area-inset-bottom) 给全面屏留安全区。 */
  gap: 5px;
  padding: 8px 8px calc(12px + env(safe-area-inset-bottom));
  /* box 的 .send-bar：底色 $im-bg（浅灰条）+ 1px 极浅顶边（--kean-line = #f2f3f5），
     输入框是白底 —— 输入框比栏底浅一档，视觉上「陷进去」 */
  background: var(--kean-bg);
  border-top: 1px solid var(--kean-line);
}
.mute-tip {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  padding: 14px 16px calc(14px + env(safe-area-inset-bottom));
  background: #fff7e8;
  color: #d25f00;
  font-size: 13px;
  text-align: center;
  border-top: 1px solid #f2e3c6;
}
/* 输入框：微信的浅灰底圆角输入框，但按 box 的层次做「白底 + 4px 小圆角」（不是大胶囊）。
   ⚠️ 高度是一组联动量里的一员：min 36px（单行）→ max 112px（≈5 行，line-height 20px），
   超过上限内部滚动。这三个值与 .composer 的 56px 栏高、.panel 的 bottom、.actions 的 bottom、
   .page 的 padding-bottom 必须一起改。 */
.input {
  flex: 1;
  min-height: 36px;
  max-height: 112px;
  /* box 的 .send-text：白底、圆角 8rpx = 4px、横向内边距 20rpx = 10px、
     字号 $im-font-size = 30rpx = 15px、行高 20px（多行时不至于挤在一起） */
  background: var(--kean-card);
  border-radius: 4px;
  padding: 8px 10px;
  font-size: 15px;
  line-height: 20px;
  box-sizing: border-box;
  overflow-y: auto;
}
/* 表情钮 / 更多钮：与输入框等高（36px）的方形热区，中性色图标。
   ⚠️ 本页不引任何图片素材，所以图标是 CSS 画的（圆脸 + 两点眼睛 + 一条下弧的嘴 /
   田字格 / 键盘），不是 iconfont、也不是 emoji 字符（emoji 字符跨平台观感不可控）。 */
.composer-icon {
  flex-shrink: 0;
  width: 36px;
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 6px;
  position: relative;
}
.composer-icon:active,
.composer-icon.active {
  background: var(--kean-line);
}
/* 表情图标：一根 1.5px 描边的圆脸（border-radius 50%）+ 两点眼睛 + 一条下弧的嘴。
   box 用的是 60rpx = 30px 的 iconfont 图标；本项目不引素材、也不新增 i18n 条目，
   所以用 CSS 画出来，并把原来的「表情 / Emoji」文案挪到 title（悬停提示）上，
   同时保证这颗钮在视觉上就是一个图标钮（微信的观感）。 */
.smile-icon {
  position: relative;
  display: block;
  width: 22px;
  height: 22px;
  box-sizing: border-box;
  border: 1.5px solid #4a4a4a;
  border-radius: 50%;
}
.smile-icon::before {
  content: "";
  position: absolute;
  left: 4px;
  top: 5px;
  width: 3px;
  height: 3px;
  border-radius: 50%;
  background: #4a4a4a;
  box-shadow: 8px 0 0 #4a4a4a;
}
.smile-icon::after {
  content: "";
  position: absolute;
  left: 5px;
  top: 9px;
  width: 9px;
  height: 6px;
  box-sizing: border-box;
  border: 1.5px solid #4a4a4a;
  border-top: none;
  border-left-color: transparent;
  border-right-color: transparent;
  border-radius: 0 0 9px 9px;
}
/* 键盘图标（面板开着时替换表情图标 —— 微信行为：点一下回到键盘）。
   ⚠️ 行高刻意不用 < 1 的值：小于 1 的行高在 H5 上可能被写成含 transform 的
   「按比例缩放」，而本页绝不许出现 transform（铁律 3），所以用 border 描边 + 行高 1。 */
.kb-icon {
  position: relative;
  display: block;
  width: 22px;
  height: 16px;
  box-sizing: border-box;
  border: 1.5px solid #4a4a4a;
  border-radius: 3px;
}
.kb-icon::before {
  content: "";
  position: absolute;
  left: 2px;
  right: 2px;
  top: 3px;
  height: 4px;
  /* 键帽：3px 实心 + 2px 间隙，横向重复（背景图而不是字符，观感稳定） */
  background-image: linear-gradient(90deg, #4a4a4a 0 3px, transparent 3px 5px);
  background-size: 5px 4px;
  background-repeat: repeat-x;
}
.kb-icon::after {
  content: "";
  position: absolute;
  left: 4px;
  right: 4px;
  bottom: 2px;
  height: 1.5px;
  background: #4a4a4a;
}
/* ===========================================================================
 * 最右侧那**一个按钮位**：＋ ↔ 发送互斥替换 + ＋（打开更多面板）/ 键盘（面板开着）
 * ---------------------------------------------------------------------------
 * ⚠️ 宽度固定 60px：两种状态共用同一格宽度，输入框（flex: 1）不会因为按钮文字长短
 *    而抖动；60px 是「发送」两个字 + 左右各 12px 内边距的实际需要。
 * ⚠️ 切换：v-if / v-else 二选一（互斥替换）+ 一个轻微淡入动画 .action-swap。
 *    没有用 Vue 的 <Transition>（小程序端拿不到 enter/leave 类），
 *    更没有用 transform / scale —— 本页禁 transform（铁律 3），
 *    transform 会把面板与固定层卷进新的 containing block 风险里。
 * =========================================================================== */
.composer-action {
  flex-shrink: 0;
  width: 60px;
  height: 36px;
}
.more-btn,
.send-btn {
  width: 100%;
  height: 100%;
  box-sizing: border-box;
  border-radius: 6px;
}
/* ＋ ↔ 发送的轻微过渡：只用 opacity（+ 这个 0.15s 的淡入），不碰 transform */
.action-swap {
  animation: action-swap-in 0.15s ease;
}
@keyframes action-swap-in {
  from {
    opacity: 0;
  }
  to {
    opacity: 1;
  }
}
/* 「＋」：CSS 画的两根等长细线（不是 "+" 字符，避免不同字体下粗细/基线不一致） */
.more-btn {
  position: relative;
  background: var(--kean-line);
}
.more-btn::before,
.more-btn::after {
  content: "";
  position: absolute;
  left: 50%;
  top: 50%;
  background: #4a4a4a;
  border-radius: 1px;
}
.more-btn::before {
  width: 16px;
  height: 2px;
  margin-left: -8px;
  margin-top: -1px;
}
.more-btn::after {
  width: 2px;
  height: 16px;
  margin-left: -1px;
  margin-top: -8px;
}
.more-btn:active {
  background: #e6e8ec;
}
.more-btn.keyboard::after {
  display: none;
}
/* 「发送」：主色实心小圆角方块 + 白字（微信的主按钮观感）。
   这里刻意不再用 wd-button：新按钮的宽度/圆角/文字色要精确控制，
   而 wd-* 是异步分包、样式永远排在入口 CSS 之后，覆盖它要靠堆特异性（铁律 4）；
   换成普通 view 后所有值都由本文件一处定义，且它不受任何 loading 属性影响 —— 永远可点
   （点击行为与原来完全一致：handleSend 自己判断空内容 / 被禁言）。 */
.send-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--kean-primary);
  color: #fff;
  font-size: 15px;
  line-height: 1;
  white-space: nowrap;
}
.send-btn:active {
  opacity: 0.85;
}
/* ===========================================================================
 * 面板公共定位（表情面板 / 更多面板）
 * ---------------------------------------------------------------------------
 * ⚠️ 面板固定在输入区上沿：bottom = **composer 高度 56px**（8 + 36 + 12）+ 安全区，
 *    因此**永远不会盖住输入框、表情钮和最右侧按钮**；关掉时是 v-if，不占位。
 *    （这个 56px 与 .composer 的 padding、.actions 的 bottom、.page 的 padding-bottom
 *    是一组联动量：改一个必须同时改另外三个。）
 * ⚠️ 两个面板互斥（emojiOpen / moreOpen），不同时出现，所以共用同一套定位。
 * =========================================================================== */
.panel {
  position: fixed;
  left: 0;
  right: 0;
  bottom: calc(56px + env(safe-area-inset-bottom));
  z-index: 60;
  /* box 的 .chat-emotion：padding 16rpx 24rpx = 8px 12px（纵向沿用 8/6 以免动高度） */
  padding: 8px 12px 6px;
  box-sizing: border-box;
  background: var(--kean-card);
  border-top: 1px solid var(--kean-line);
}
/* 表情面板：高度 232px + 内部 scroll-view 滚动（73 个表情一屏放不下） */
.panel-emoji {
  height: 232px;
}
/* 更多面板：现在是「相册」一个九宫格项，所以只占一行的实际高度
   （8 上内边距 + 22 小标题 + 56 图标 + 4 间距 + 16 文字 + 6 下内边距 = 112px，再留 4px 余量） */
.panel-more {
  height: 116px;
}
.panel-title {
  font-size: 12px;
  line-height: 16px;
  color: #9aa4b2;
  padding: 0 2px 6px;
}
.panel-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
}
/* 九宫格里的一个项目：白底圆角图标 + 一行文字（微信「＋」面板的同款形状） */
.more-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}
.more-item__icon {
  width: 56px;
  height: 56px;
  border-radius: 12px;
  background: var(--kean-bg);
  position: relative;
}
/* 用 CSS 画一个「相册」图标：一座山（一条水平底边 + 一个等腰三角峰）+ 一个太阳圆点，
   不引任何图片素材（三层：图标底 + 太阳子元素 + 山的三角用 border 拼，不依赖 clip-path）。 */
.more-item__sun {
  position: absolute;
  right: 13px;
  top: 12px;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #b8bdc6;
}
.more-item__icon::before {
  content: "";
  position: absolute;
  left: 13px;
  bottom: 14px;
  width: 30px;
  height: 4px;
  background: #b8bdc6;
}
.more-item__icon::after {
  content: "";
  position: absolute;
  left: 20px;
  bottom: 18px;
  width: 0;
  height: 0;
  border-left: 8px solid transparent;
  border-right: 8px solid transparent;
  border-bottom: 14px solid #b8bdc6;
}
.more-item__text {
  font-size: 12px;
  line-height: 16px;
  color: var(--kean-text);
}
.more-item:active .more-item__icon {
  background: var(--kean-line);
}
.emoji-scroll {
  height: 196px;
}
/* 分组小标题：box 的 .emotion-group-title 是 22rpx = 11px、左右内边距 4rpx = 2px、
   下外边距 10rpx = 5px；上面那 8px 等同于 box 的 .emotion-group 之间 16rpx 的间距
   （本项目没有 .emotion-group 包裹层，所以摊到标题上）。 */
.emoji-group-title {
  font-size: 11px;
  line-height: 16px;
  color: #9aa4b2;
  padding: 0 2px;
  margin: 8px 0 5px;
}
/* 8 列网格：73 个表情排 10 行，交给 scroll-view 滚。
   ⚠️ box 的 .emotion-item-list 是 repeat(7, 1fr)（gap 12rpx 16rpx = 6px 8px）：
   列数**刻意保留 8 列**——「8 列网格」是本页表情面板已定的功能形状（见交付说明），
   只把 gap 对齐成 box 的 6px 8px。 */
.emoji-grid {
  display: grid;
  grid-template-columns: repeat(8, 1fr);
  gap: 6px 8px;
}
.emoji-cell {
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
}
.emoji-cell:active {
  background: var(--kean-bg);
}
.emoji-face {
  width: 30px;
  height: 30px;
  display: block;
}
/* 发送中的文字提示：替代会吞点击的按钮 loading */
.sending-tip {
  color: #9aa4b2;
  font-size: 12px;
  white-space: nowrap;
}
/* ===========================================================================
 * ④「有新消息」提示（微信/QQ 的悬浮小圆钮，点击滚到底）
 * ---------------------------------------------------------------------------
 * ⚠️ 它是 .page 的**直接子元素**且 position: fixed —— 与 .composer / .actions /
 *    .panel / .mute-tip / .peer-status 同一层。所以：
 *    ① 自身一条 containing-block 属性都不加（本轮连 transform 都没用：下三角是
 *       border 拼的实心三角，出现动画只用 opacity）；
 *    ② bottom 与 .actions 的 56px、.composer 的 56px、.page 的 padding-bottom 112px
 *       是同一组联动量 —— 112px 正好落在 .actions 上沿（56 + 输入栏外沿），不会压住输入区。
 * ⚠️ z-index 55：低于 .panel 的 60（面板弹出来要盖住它），高于消息列表本身。
 * ⚠️ 文案：这里**只画一个下三角、不写「新消息」** —— 本页新增文案必须走 i18n（t()），
 *    而本轮限定只改本文件，utils/i18n.ts 里没有可复用的「新消息」key，所以不加文字。
 *    若要补文字：在 utils/i18n.ts 的 zh/en 同序位置各加一个 key 再 t() 即可。
 * =========================================================================== */
.new-msg-tip {
  position: fixed;
  right: 12px;
  bottom: calc(112px + env(safe-area-inset-bottom));
  z-index: 55;
  width: 34px;
  height: 34px;
  display: flex;
  align-items: center;
  justify-content: center;
  box-sizing: border-box;
  border-radius: 50%;
  background: var(--kean-card);
  border: 1px solid var(--kean-line);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.12);
  /* 出现时只淡入（不碰 transform） */
  animation: new-msg-tip-in 0.15s ease;
}
.new-msg-tip:active {
  background: var(--kean-line);
}
@keyframes new-msg-tip-in {
  from {
    opacity: 0;
  }
  to {
    opacity: 1;
  }
}
/* 向下的实心三角：与 .more-item__icon::after 同一画法（border 拼，无素材、无 transform） */
.new-msg-arrow {
  width: 0;
  height: 0;
  border-left: 6px solid transparent;
  border-right: 6px solid transparent;
  border-top: 8px solid var(--kean-primary);
}
</style>
