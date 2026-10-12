<script setup lang="ts">
import { listChats, unreadChatCount, type ChatSessionItem } from "@/api/chat";
import {
  listNotifications,
  markAllNotificationsRead,
  markNotificationRead,
  unreadNotificationCount,
  type NotificationItem
} from "@/api/notification";
import FallbackImage from "@/components/FallbackImage.vue";
import ListState from "@/components/ListState.vue";
import PageBackdrop from "@/components/PageBackdrop.vue";
import { usePageWallpaper } from "@/composables/usePageWallpaper";
import { useUserStore } from "@/store/user";
import { formatNoticeDay, formatNoticeTime, formatRelativeStamp, pad } from "@/utils/format";
import { emojiToPlainText, splitEmoji, type EmojiSegment } from "@/utils/emoji";
import { t, tf } from "@/utils/i18n";
import { refreshMessageBadge } from "@/utils/messageBadge";
import { hasNewerSeq, hasViewedSeq, syncViewedSeq } from "@/utils/chatStore";
import { noticeFocus, taskDetailUrl } from "@/utils/taskAction";
import { resolveMediaUrl } from "@/utils/request";
import { useLiveUpdates } from "@/composables/useLiveUpdates";
import { onPullDownRefresh, onReachBottom, onShow } from "@dcloudio/uni-app";
import { useToast } from "wot-design-uni";
import { computed, ref } from "vue";

type TabKey = "system" | "task" | "chat";
type TaskKind = "apply" | "fulfill" | "review" | "change";
/**
 * 通知的「视觉类型」——**只由后端字段判定**（type + bizType），绝不靠标题文案猜。
 * 表与判定顺序见 styles/design-messages.css 文件头 B 节（唯一权威表）。
 * 它与下面的 taskKind()（筛选 chip 用的业务分档）是两件事：
 *   · noticeVisual 管"长什么样"（图标 + 语义色 + 左侧色条）；
 *   · taskKind 管"归到哪个筛选 chip"（原有口径一字未改）。
 */
type NoticeVisual = "apply" | "task" | "review" | "change" | "report" | "system";

const toast = useToast();
const userStore = useUserStore();
const { wallpaperOn, wallpaperImage } = usePageWallpaper();
const tab = ref<TabKey>("task");
const taskFilter = ref<"all" | TaskKind>("all");
const pickedBusy = ref(false);
const list = ref<NotificationItem[]>([]);
const noticeTotal = ref(0);
const chats = ref<ChatSessionItem[]>([]);
/**
 * 会话列表的「有新消息」辅助提示：用 lastSeqNo 与本地已看位点比较。
 * 只影响这一个提示点，未读角标的既有口径（unreadCount）一点没动。
 * 老后端没有 lastSeqNo 时 hasNewerSeq 恒为 false，整个提示不出现。
 */
const freshChats = computed(() => {
  const hasNew = new Set<number>();
  chats.value.forEach((item) => {
    const lastSeqNo = Number(item.lastSeqNo || 0);
    if (!lastSeqNo) {
      return;
    }
    // 第一次看到这个会话时不提示（本地没有任何位点，没有「新」的参照物），只记下位点
    if (hasViewedSeq(item.id) && hasNewerSeq(item.id, lastSeqNo)) {
      hasNew.add(item.id);
      return;
    }
    // 已在会话里看到过这个位点，记录下来，下次会话列表刷新就能判断「真的有新的」
    syncViewedSeq(item.id, lastSeqNo);
  });
  return hasNew;
});
const loading = ref(false);
const chatLoading = ref(false);
const noticeError = ref("");
const chatError = ref("");
let noticeRequestId = 0;
let chatRequestId = 0;
const finished = ref(false);
const page = ref(1);
const dots = ref({ system: 0, task: 0, chat: 0 });
const currentUnread = computed(() => tab.value === "system" ? dots.value.system : tab.value === "task" ? dots.value.task : dots.value.chat);
// 大标题下的副标题：三档各一句，全部走 i18n（msgSubtitleSystem / msgSubtitleTask / msgSubtitleChat）
const messageDescription = computed(() =>
  tab.value === "system" ? t("msgSubtitleSystem") : tab.value === "task" ? t("msgSubtitleTask") : t("msgSubtitleChat")
);
const searchOpen = ref(false);
const searchQuery = ref("");
// 顶部标题栏改成页面内自绘（pages.json 的 navigationStyle: custom）后，刘海屏要自己
// 让出状态栏高度；取值与 pages/mine/index.vue 完全同一套（同一行、同一个 20 兜底）。
const statusBarHeight = uni.getSystemInfoSync().statusBarHeight || 20;
const noticeScope = computed(() => (tab.value === "system" ? "SYSTEM" : "TASK") as "SYSTEM" | "TASK");
// 系统通知（没有可跳转的任务/会话）点开后，在原地用弹层展示完整内容
const activeNotice = ref<NotificationItem | null>(null);
const noticeOpen = ref(false);

const TASK_KINDS: { key: TaskKind; label: string; hint: string }[] = [
  { key: "apply", label: "申请", hint: "谁来申请、接没接上" },
  { key: "fulfill", label: "履约", hint: "上课、拍照、确认完成" },
  { key: "review", label: "评价", hint: "完成后对对方进行评价" },
  { key: "change", label: "变动", hint: "取消、过期、信息变更" }
];

function taskKind(item: NotificationItem): TaskKind {
  const title = item.title || "";
  if (item.bizType === "REVIEW" || title.includes("已完成") || title.includes("自动完成")) {
    return "review";
  }
  if (item.type === "APPLICATION" || title.includes("申请")) {
    return "apply";
  }
  if (title.includes("取消") || title.includes("过期") || title.includes("已更新")) {
    return "change";
  }
  return "fulfill";
}

const taskKindStats = computed(() => {
  return TASK_KINDS.map((kind) => ({
    ...kind,
    count: list.value.filter((item) => taskKind(item) === kind.key).length,
    unread: list.value.filter((item) => taskKind(item) === kind.key && item.readFlag !== 1).length
  }));
});
const taskHeading = computed(() => taskFilter.value === "all" ? "最近动态" : `${TASK_KINDS.find((kind) => kind.key === taskFilter.value)?.label || ""}消息`);

const visibleNotices = computed(() => {
  const byKind = tab.value !== "task" || taskFilter.value === "all"
    ? list.value
    : list.value.filter((item) => taskKind(item) === taskFilter.value);
  const query = searchQuery.value.trim().toLocaleLowerCase();
  return query ? byKind.filter((item) => `${item.title || ""} ${item.content || ""}`.toLocaleLowerCase().includes(query)) : byKind;
});

const visibleChats = computed(() => {
  const query = searchQuery.value.trim().toLocaleLowerCase();
  return query ? chats.value.filter((item) => `${item.peerNickname || ""} ${previewTextOf(item)}`.toLocaleLowerCase().includes(query)) : chats.value;
});
const narrowedNoticeList = computed(() => Boolean(searchQuery.value.trim()) || (tab.value === "task" && taskFilter.value !== "all"));
const filteredNoticeNeedsMore = computed(() => narrowedNoticeList.value && !finished.value && !loading.value);

// 通知 Tab（系统 / 申请与履约）当前是否有内容可显示，交给 ListState 决定是加载中、失败还是空态
const noticeEmpty = computed(() =>
  visibleNotices.value.length === 0 && (Boolean(noticeError.value) || !narrowedNoticeList.value || finished.value)
);

const noticeEmptyText = computed(() => {
  if (tab.value === "system") {
    return "暂无系统通知";
  }
  return taskFilter.value === "all" ? "暂无申请或履约消息" : "暂无该类消息";
});

/* ===========================================================================
 * 顶部提示条（浅蓝圆角卡）的数据来源：**完全复用现有数据，没有新数据源**
 * ---------------------------------------------------------------------------
 * · 条数  = currentUnread（= dots.system / dots.task，来自两个既有的未读接口，
 *           口径与出现条件一个字都没动）；
 * · 副标题 = 当前列表里**最新一条未读通知**的标题（列表本身就是后端的时间倒序，
 *           所以第一条未读就是最新那条），文案走列表同一个 noticeTextOf()
 *           （定型文案 + 花括号兜底），不另外解析一遍；
 * · 点击   = 打开那条未读通知（复用 openItem 的既有行为：标记已读 + 跳任务详情/弹层）。
 *           若未读数来自服务端全量、而本页还没翻到那一条（列表里找不到未读项），
 *           则回落到原有行为「全部已读」（handleReadAll）——
 *           **不新造页面、不凭空跳转、不新增接口**。
 * =========================================================================== */
const hintTarget = computed<NotificationItem | null>(() => {
  if (tab.value === "chat" || (tab.value === "task" && taskFilter.value !== "all")) {
    return null;
  }
  return list.value.find((item) => item.readFlag !== 1) || null;
});
const hintTitle = computed(() =>
  tf(tab.value === "system" ? "msgHintSystem" : "msgHintTask", {
    count: currentUnread.value > 99 ? "99+" : String(currentUnread.value)
  })
);
const hintSubtitle = computed(() => (hintTarget.value ? noticeTextOf(hintTarget.value).title : ""));

/* ===========================================================================
 * 通知的「视觉类型」（决定图标块里画哪一枚图标、以及那一块的语义色）
 * ---------------------------------------------------------------------------
 * ⚠️ 判定只看 type / bizType 两个**后端字段**，顺序不可换（见 design-messages.css B 节）：
 *    先 REPORT（它是 SYSTEM 的子集，放后面会被吞），再 REVIEW，再 APPLICATION，
 *    再「TASK + 变动标题」，再 TASK，最后 SYSTEM 兜底。
 * ⚠️ 这里比原来的视觉分档多了一档 "report"：举报处理结果（type=SYSTEM + bizType=REPORT）
 *    原来没有任何类型标记（taskKind 会把它们兜底归进"履约"），而它们需要独立的
 *    危险色 + 旗帜图标（"你的举报已处理/未予处理"是强结论，不该和履约混在一起看）。
 *    业务分档 taskKind() **一个字都没动** —— 筛选 chip 的数量、归属与排序因此完全不变。
 * ⚠️ 事实核对：列表**只有一套**分组（按日期：今天/昨天/更早），加一条小节标题
 *    （系统档「系统通知」/ 具体 chip「X消息」/「全部」为「最近动态」）。
 *    代码里**没有**"申请/履约/评价/变动 四个业务分区"的实现 ——
 *    本轮只改视觉，**没有**去新写分组逻辑（要加得单独立项）。
 * =========================================================================== */
function noticeVisual(item: NotificationItem): NoticeVisual {
  const biz = String(item.bizType || "").toUpperCase();
  const type = String(item.type || "").toUpperCase();
  if (biz === "REPORT") {
    return "report";
  }
  if (biz === "REVIEW") {
    return "review";
  }
  if (type === "APPLICATION") {
    return "apply";
  }
  if (type === "TASK") {
    if (hasChangeTitle(item.title || "")) {
      return "change";
    }
    return "task";
  }
  return "system";
}

function noticeIconClass(item: NotificationItem) {
  return `msg-notice__ico--${noticeVisual(item)}`;
}

/** 一句话标签：走 t()，消息页本地不再出现硬编码中文类型名 */
function noticeTypeLabel(item: NotificationItem) {
  const key: Record<NoticeVisual, Parameters<typeof t>[0]> = {
    apply: "msgTypeApply",
    task: "msgTypeTask",
    review: "msgTypeReview",
    change: "msgTypeChange",
    report: "msgTypeReport",
    system: "msgTypeSystem"
  };
  return t(key[noticeVisual(item)]);
}

/**
 * 「待处理」（C 节）：项目里通知列表**没有**业务待办字段（只有 readFlag），
 * 所以判定只能是「未读 且 类型属于需要我动作的那几类」——
 * apply（有人申请 / 撤回）/ review（请为对方打星）/ change（被取消、信息变更需重看）。
 * system / report 是"告知"，不产生待办，不给 chip。
 */
function noticeTodo(item: NotificationItem) {
  if (item.readFlag === 1) {
    return false;
  }
  const visual = noticeVisual(item);
  return visual === "apply" || visual === "review" || visual === "change";
}

/**
 * D 节：通知按日期分桶。
 *
 * <p>三档口径（今天 / 昨天 / 更早）不变，**只把「更早」那一档的标题从「更早」改成具体日期**：</p>
 * <ul>
 *   <li>今天 → 标题「今天」（t("msgDayToday")）；</li>
 *   <li>昨天 → 标题「昨天」（t("msgDayYesterday")）；</li>
 *   <li>⭐ 更早 → **按自然日再拆成若干小组，每组标题就是那一天**（同一年「10月8日」/
 *       跨年「2025年12月3日」）。理由是「更早」往往会横跨很多天，整组只挂一个日期
 *       （无论取最新还是最旧那条）都会把其余日期指错 —— 那正是用户说的"须标注日期"的反面。
 *       注意这**不是**三档变多档：今天 / 昨天两档仍各只有一个标题，
 *       只有「更早」这一档内部展开成「一天一组」。</li>
 * </ul>
 *
 * <p>⚠️ 这里只重建**呈现用的分组**，取数 / 分页 / 排序 / 未读一行未动：
 *    · 入参仍是原来的 {@link visibleNotices}（筛选与搜索的结果，顺序不动）；
 *    · 只是把 list.value 里的 item 按「已有的 createdAt 字段」重新归堆，
 *      不重新请求、不重排、不改 readFlag，也不产生任何新的加载分支；
 *    · 分组键是**机器字符串**（bucket:today / bucket:yesterday / 2026-10-08），
 *      与显示文案解耦 —— 切语言不会让分组重算成另一批键，v-for 的 :key 也始终唯一。</p>
 */
const noticeDayGroups = computed(() => {
  const buckets: { key: string; items: NotificationItem[] }[] = [];
  const index: Record<string, number> = {};
  const push = (key: string, item: NotificationItem) => {
    if (index[key] === undefined) {
      index[key] = buckets.length;
      buckets.push({ key, items: [] });
    }
    buckets[index[key]].items.push(item);
  };
  // 两个固定档用**稳定前缀**当分组键（bucket:today / bucket:yesterday），
  // 「更早」那些天用日期键（2026-10-08）。这样键永远是机器字符串、不与任何显示文案相撞。
  const TODAY_KEY = "bucket:today";
  const YESTERDAY_KEY = "bucket:yesterday";
  const dayKeyOf = (iso?: string | null) => {
    const date = new Date(String(iso || ""));
    if (Number.isNaN(date.getTime())) {
      return "";
    }
    // 同一年内不带年（"2026-10-08"），跨年带年（"2025-12-03"）——
    // 与 format.ts 的 monthDayText 同一套口径，只在本地分组键里用一次。
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
  };
  visibleNotices.value.forEach((item) => {
    const bucket = formatNoticeDay(item.createdAt);
    if (bucket === "today") {
      push(TODAY_KEY, item);
      return;
    }
    if (bucket === "yesterday") {
      push(YESTERDAY_KEY, item);
      return;
    }
    // 更早：一天一组。日期解析不出来（脏数据）时退回一个不再细分的兜底组，
    // 标题走下面 noticeDayLabel 的 msgDayEarlier 分支，绝不显示 "NaN"。
    push(dayKeyOf(item.createdAt) || "bucket:earlier", item);
  });
  return buckets;
});

/**
 * 分组标题：今天 / 昨天走既定文案；其余（更早那一档）把分组键当日期解析后本地化。
 * ⚠️ 带占位符的日期文案一律走 {@link tf}（msgMonthDay / msgYearMonthDay 都带 {mon}/{d}/{y}）。
 */
function noticeDayLabel(key: string) {
  if (key === "bucket:today") {
    return t("msgDayToday");
  }
  if (key === "bucket:yesterday") {
    return t("msgDayYesterday");
  }
  const matched = /^(\d{4})-(\d{2})-(\d{2})$/.exec(key);
  if (!matched) {
    return t("msgDayEarlier");
  }
  const year = Number(matched[1]);
  const mon = String(Number(matched[2]));
  const day = String(Number(matched[3]));
  return year === new Date().getFullYear()
    ? tf("msgMonthDay", { mon, d: day })
    : tf("msgYearMonthDay", { y: String(year), mon, d: day });
}

/* ===========================================================================
 * 会话预览：把消息类型变成"弱化小图标 + 文字"
 * ---------------------------------------------------------------------------
 * 事实（读代码确认）：ChatSessionItem **只有** lastContent 一个正文字段，
 * 没有 lastMsgType —— 所以：
 *   · 图片消息的 lastContent 落库的是 object key（StorageServiceImpl 的 CHAT 场景），
 *     于是原来会把一串 object key 直接当预览展示给用户（发给人看的列表里出现文件路径，
 *     既是信息噪音也算信息泄漏）。这里按扩展名识别并按 msgType=IMAGE 的口径显示「[图片]」；
 *   · 真机里若 lastContent 已经是「[图片]」这类文案，则原样保留（不重复包装）；
 *   · 撤回：msgType=RECALL 在 chatSync 里就是不显示状态（见 chatSync.ts:448），
 *     列表侧只能在内容里看到"撤回"字样的服务端文案，命中则给撤回图标。
 * =========================================================================== */
const IMAGE_EXT_RE = /\.(png|jpe?g|gif|webp|bmp|heic|heif)$/i;
const IMAGE_KEY_RE = /^\[(图片|照片|image|photo)\]$/i;
/**
 * ⚠️ 撤回的推断在真机下**只能靠这条**：ChatServiceImpl 把 last_content 写成
 * `"IMAGE".equals(type) ? "[图片]" : text`（见 ChatServiceImpl:353）——即使
 * status=2（撤回）或 RECALL，落库的仍是原文。所以：
 *   · 命中「[已撤回] / 撤回了一条消息 / 对方撤回了一条消息 / [Recalled]」这类**成品文案** → 换图标 + 定型文案；
 *   · 只是正文里**恰好出现过**"撤回"二字的普通聊天（如"这个申请我撤回了"）→ 保持纯文本，不误标；
 *   · 因此本项目在会话列表侧**无法**区分"我撤回的 / 对方撤回的"（会话 VO 没有 lastSenderId /
 *     lastMsgType），统一显示为不带人称的 [已撤回] —— 不猜人称，见「需要后端配合」清单。
 */
const RECALL_MSG_RE = /^\[?(已?撤回|撤回了一条消息|对方撤回了一条消息|recalled)\]?$/i;

type ChatPreviewKind = "text" | "image" | "recall";

function chatPreviewOf(item: ChatSessionItem): { kind: ChatPreviewKind; text: string } {
  const raw = String(item.lastContent || "").trim();
  if (!raw) {
    return { kind: "text", text: t("msgPreviewNone") };
  }
  if (IMAGE_EXT_RE.test(raw) || IMAGE_KEY_RE.test(raw)) {
    return { kind: "image", text: t("msgPreviewImage") };
  }
  if (RECALL_MSG_RE.test(raw)) {
    return { kind: "recall", text: t("msgPreviewRecall") };
  }
  return { kind: "text", text: raw };
}

/**
 * 预览只算一次：模板里要同时用 kind（决定要不要画图标）与 text，
 * 直接调两个函数会对每条会话重复跑两遍字符串处理。
 */
const chatPreviews = computed(() => {
  const map = new Map<number, { kind: ChatPreviewKind; text: string }>();
  chats.value.forEach((item) => {
    map.set(item.id, chatPreviewOf(item));
  });
  return map;
});

function previewKindOf(item: ChatSessionItem): ChatPreviewKind {
  return chatPreviews.value.get(item.id)?.kind || "text";
}

function previewTextOf(item: ChatSessionItem): string {
  const raw = chatPreviews.value.get(item.id)?.text || "";
  if (!raw) {
    return "";
  }
  // 预览行是单行省略号的纯文本：先把已知表情短代码换成 `[表情]`，
  // 免得列表里直接出现 `[微笑]` 这种原文（图片渲染见 previewSegmentsOf）。
  const plain = emojiToPlainText(raw).trim();
  return plain || t("msgPreviewNone");
}

/**
 * 预览行的渲染片段：已知表情短代码 → 内联小图（与聊天页气泡同一套映射）。
 * ⚠️ 只处理已知短代码；图片/撤回那两档走的是定型文案（[图片] / [已撤回]），
 *    它们的方括号内容不在表情表里，所以会原样保留，不会被误替换成图。
 */
function previewSegmentsOf(item: ChatSessionItem): EmojiSegment[] {
  const raw = chatPreviews.value.get(item.id)?.text || "";
  return raw ? splitEmoji(raw) : [];
}

// 当前 Tab 的加载状态：通知与私信是两次独立请求，不能共用一个 loading，
// 否则切换 Tab 时正在飞行的那次请求会把另一个 Tab 的加载挡住，最后显示成空态
const listLoading = computed(() => (tab.value === "chat" ? chatLoading.value : loading.value));
const currentError = computed(() => (tab.value === "chat" ? chatError.value : noticeError.value));

/* ===========================================================================
 * G 节：通知文案的「定型渲染」
 * ---------------------------------------------------------------------------
 * 背景（**字段审计结论，见本轮审计表**）：NotificationVO 只有
 *   id / type / title / content / bizType / bizId / readFlag / createdAt / receiverRole 九个字段，
 * 没有任何关联对象（任务标题、对方昵称、金额、时间都不在结构化字段里）。
 * ⚠️ 其中 receiverRole 是后端 V36 新增的**收件角色**（PUBLISHER / APPLICANT，历史通知为 null），
 * 让"同一标题发给两方"的通知不必再从正文反推角色；其余字段仍是散文素材。
 * 所以「一眼看清」的**唯一可用素材**是后端已经写进 title / content 的散文 ——
 * 其中任务名被统一放在「」里、对方昵称固定在固定句式的位置上（本轮逐调用点核对过：
 * TaskServiceImpl:500-523/828-911、TaskScheduleService:262-412、ApplicationServiceImpl:104-261、
 * AdminTaskServiceImpl:196-218、ReportServiceImpl:260-591、LoginDeviceServiceImpl:189-299、
 * AdminUserServiceImpl:198-254、AccountBanServiceImpl:82）。
 *
 * 因此本节的策略是**按 type + bizType 分流到定型文案**：
 *   · 对象名从「」里取（拿不到就整条回退原文，绝不硬编）；
 *   · type/bizType 认识但 title 是**本轮未见过的**（后端将来新增文案）→ 仍然回退原文，
 *     并且 [data-v] 一行都不会少地照旧显示 —— 只是没有定型句式，不会显示错。
 *   · 绝不**反向**用 title 文本去猜分类：视觉分类仍只看 type/bizType（noticeVisual）。
 *     这里的 title 匹配只用于「已知类目内部选哪一句定型文案」，命中不了就降级。
 *   · 后端正文里的多行结果（"处理结果：… / 回复：… / 变更如下：…"）由 noticeBodyLines
 *     原样附在定型正文后面 —— 那些是后端自由文本，本轮不改也不丢。
 * ⚠️ 新增/修改任何一句都要同步 utils/i18n.ts 的 zh + en（严格 1:1 同序）。
 * =========================================================================== */

import type { MsgKey } from "@/utils/i18n";
type I18nKey = Parameters<typeof t>[0];

/** 后端把对象名统一包在「」里；English 侧英文引号是“” —— 两种都认，取不到返回空串 */
function objectName(text?: string | null): string {
  const raw = String(text || "");
  const cn = raw.match(/「([^」]+)」/);
  if (cn && cn[1]) {
    return cn[1].trim();
  }
  const curly = raw.match(/[“"]([^”"]+)[”"]/);
  return curly && curly[1] ? curly[1].trim() : "";
}

/** 兜底人称：拿不到具体名字时用「同学」，绝不编造人名 */
function actorFallback(): string {
  return t("msgActorStudent" as MsgKey);
}

/**
 * 后端把"人"写成「发布者 张三 / 代课者 张三 / 管理员」—— 去掉角色前缀，只留可读的名字。
 * ⚠️ 只在**已经确定是哪一类通知**的前提下回填人名；取不到就返回兜底人称，
 * 绝不把整条模板的 object 当成名字用（msgFulSelfPhotoUploaded 这类无「」的正文会取到空）。
 */
function actorName(text?: string | null): string {
  let value = String(text || "").replace(/\s+/g, " ").trim();
  if (!value) {
    return actorFallback();
  }
  value = value.replace(/^(发布者|代课者|申请人|管理员)\s*/u, "").trim();
  if (!value) {
    return actorFallback();
  }
  if (/^(发布者|publisher)$/i.test(value)) {
    return t("msgActorPublisher" as MsgKey);
  }
  if (/^(管理员|admin)$/i.test(value)) {
    return t("msgActorAdmin" as MsgKey);
  }
  if (/^(代课者|申请人|用户|同学|substitute|applicant|user)$/i.test(value)) {
    return t("msgActorApplicant" as MsgKey);
  }
  return value;
}

/**
 * 对方昵称：后端每种通知的句式固定，按句式优先匹配，最后退回「名字 + 空格 + 动作」的通用形。
 * ⚠️ 顺序不能反：通用形容易被"代课「高数」已被…"这类无「」/无空格的句子误伤，所以放最后，
 * 且命中不了就返回兜底人称（不猜、不编）。
 */
function counterpartName(content?: string | null): string {
  const text = String(content || "");
  const patterns = [
    /代课者\s(\S+?)\s*已上传/,
    /代课者\s(\S+?)\s*未上传/,
    /代课者\s(\S+?)\s*已确认完成/,
    /代课者\s(\S+?)\s*取消了/,
    /发布者\s(\S+?)\s*已确认完成/,
    /发布者\s(\S+?)\s*修改了/,
    /请为代课者\s(\S+?)\s*打星/,
    /请为发布者\s(\S+?)\s*打星/,
    /为(?:代课者|发布者)\s(\S+?)\s*打星/,
    /^(\S+?)\s申请了/m,
    /^(\S+?)\s撤回了/m
  ];
  for (const pattern of patterns) {
    const hit = text.match(pattern);
    if (hit && hit[1]) {
      return actorName(hit[1]);
    }
  }
  return actorFallback();
}

/** 只取指定前缀的那一行（后端 appendHandleLines 的固定前缀） */
function contentLine(content: string | null | undefined, prefix: string): string {
  const line = String(content || "")
    .split("\n")
    .find((item) => item.trim().startsWith(prefix));
  return line ? line.trim().slice(prefix.length).trim() : "";
}

/** 「处理结果」等行是后端自由文本，原样保留（在定型正文之后单独成行） */
function extraLines(content?: string | null): string[] {
  return String(content || "")
    .split("\n")
    .slice(1)
    .map((line) => line.trim())
    .filter(Boolean);
}

/**
 * 任何值 → 可显示字符串。**null / undefined 一律变空串**（`String(null)` 会得到
 * 字面量 "null" 显示给用户，那比空白更糟）。后端字段缺失、词典键缺失都走这里。
 */
function text(value: unknown): string {
  return value === null || value === undefined ? "" : String(value);
}

/**
 * ⚠️ 未替换的占位符探测器（本轮硬指标：**任何情况下都不许把 {xxx} 显示给用户**）。
 * 词典是 `{object}`{minutes}`{time}`{name}`{typeLabel}`{mark}` 这套半角花括号，
 * 这里连全角 `｛｝` 一起认（输入法/后端改写可能带全角）；换行不算，占位符永远是单行的。
 */
function hasPlaceholder(value: string): boolean {
  return /[{}｛｝]/.test(String(value || ""));
}

/**
 * 定型文案的落地：把「标题 key + 正文 key + 占位符取值」渲染成一句能直接显示的文本。
 *
 * ⚠️ 标题与正文**走同一条插值路径**（都用 tf + 同一份 params）。原来的写法是
 *    `t(titleKey)` —— 标题**不做任何替换**，只在正文里做；于是同一句文案里
 *    正文的「下课时间 {time}」是好的、标题的「{object}」却原样显示（线上截图就是这个形态）。
 *    现在两边一致：params 里有 key 就换掉，缺 key 由下面的兜底拦下（绝不外泄花括号）。
 *
 * ⚠️ 整个函数**不可能抛**：i18n 查表（t / tf）整体包在 try/catch 里，
 *    任何异常（key 缺失、参数值不是字符串、词典被改坏）都当成"这次不渲染定型文案"，
 *    返回 null，由上层回退到后端原文。绝不允许异常从渲染路径里冒出去 ——
 *    渲染期一旦抛错，整个列表节点都不会挂载，用户看到的就是"消息描述全部消失"。
 *
 * ⚠️ 花括号兜底（治标但必须）：渲染完还要**再验一遍** title 与 body，
 *    只要还剩 `{object}` / `{minutes}` 这类令牌（或 replace 进来的空值把「」留成空括号），
 *    就判定"这次定型渲染失败" → 返回 null → 上层显示**后端原文**。
 *    宁可显示后端原文，也绝不显示花括号。
 */
function noticeTemplate(titleKey: I18nKey, bodyKey: I18nKey, params: Record<string, string> = {}): NoticeTemplate | null {
  try {
    const safe = assembleParams(params);
    const title = text(tf(titleKey, safe)).trim();
    const body = text(tf(bodyKey, safe)).trim();
    if (!title && !body) {
      return null;
    }
    // ⚠️ 兜底必须同时覆盖标题与正文：任何一边残留占位符/空括号，整条定型文案作废
    if (hasPlaceholder(title) || hasPlaceholder(body)) {
      return null;
    }
    const cleanTitle = title ? dropEmptySlots(title) : "";
    const cleanBody = body ? dropEmptySlots(body) : "";
    if ((title && !cleanTitle) || (body && !cleanBody)) {
      return null;
    }
    return {
      titleKey,
      bodyKey,
      params: safe,
      title: cleanTitle,
      body: cleanBody
    };
  } catch {
    return null;
  }
}

interface NoticeTemplate {
  titleKey: I18nKey;
  bodyKey: I18nKey;
  /** 这一条实际用到的占位符取值（noticeTemplate 已过滤空值），调试/审计用 */
  params: Record<string, string>;
  title: string;
  body: string;
}

/**
 * 缺值时的"整段删除"：把「上课时间 ，」这类只剩占位词的片段整段去掉，
 * 而不是留下一个光秃秃的冒号。删完为空时返回空串，调用方会回退后端原文。
 */
function dropEmptySlots(text: string): string {
  const pattern = /(?:上课时间|下课时间|开始时间|时间)\s*[:：]?\s*(?=[，。；、]|$)/g;
  let out = text;
  for (let i = 0; i < 3; i += 1) {
    const next = out.replace(pattern, "");
    if (next === out) {
      break;
    }
    out = next;
  }
  return out
    .replace(/[，、]\s*(?=[，。；、])/g, "")
    .replace(/[ \t]{2,}/g, " ")
    .replace(/\s+([，。；、])/g, "$1")
    .trim();
}

function matches(title: string, patterns: string[]): boolean {
  return patterns.some((pattern) => title === pattern);
}

/**
 * 模板里的 `{object}`（课程名）取值：**取不到就返回空串**（不返回「同学」那类人称兜底 ——
 * 课程名绝不能用人名顶替）。调用点用 firstText 收敛成非空值，见 roleParams。
 */
function courseOf(text?: string | null): string {
  return objectName(text) || "";
}

/** 句子里有没有名字（用于判断 {name} 该不该给） */
function hasName(value?: string | null): boolean {
  const raw = String(value || "").trim();
  if (!raw) {
    return false;
  }
  return !/^(发布者|代课者|申请人|管理员|用户|同学|publisher|substitute|admin|user)$/i.test(raw);
}

/** 后端「已于 X 下课」 */
function endAtOf(content?: string | null): string {
  return (String(content || "").match(/已于\s*([^，。]+?)\s*下课/) || [])[1] || "";
}

/** 后端「将于 X 开始」 */
function startAtOf(content?: string | null): string {
  return (String(content || "").match(/将于\s*([^，。]+?)\s*开始/) || [])[1] || "";
}

/** 后端「即将开始」标题里的 N 分钟：V36 起是「课程名」N 分钟后开始，V36 之前是「距上课还有 N 分钟」 */
function minutesOf(title?: string | null): string {
  const text = String(title || "");
  // 新标题里课程名排在分钟前面，所以先按后缀「N 分钟后开始」锚定 —— 万一课程名里自带
  // 「60 分钟精讲」这种片段，也不会把它当成开课倒计时；旧标题（距上课还有 N 分钟）走第二个正则。
  return (text.match(/(\d+)\s*分钟后开始/) || text.match(/(\d+)\s*分钟/) || [])[1] || "";
}

/** 举报正文里的「（违规类型）」标签，后端由 typeLabelOf 映射（虚假信息 / 骚扰辱骂…） */
function typeLabelOf(content?: string | null): string {
  const hit = String(content || "").match(/[（(]([^（）()]{1,12})[)）]/);
  return hit && hit[1] ? hit[1].trim() : "";
}

/**
 * 收件人是哪一方 —— 同一个 title 常常同时发给两方
 *（即将开始 / 待传照片 / 可以确认完成 / 自动完成 / 取消 / 过期）。
 *
 * 判定分两层，**优先用后端字段**：
 *   1. `NotificationItem.receiverRole`（后端 V36 起把收件角色落库，取值 PUBLISHER / APPLICANT）
 *      —— 权威且与文案解耦：后端以后改正文，判定也不会退化成中性；
 *   2. 拿不到字段（历史通知 / 老后端）才回退到**正文里的互斥短语**：
 *      代课者侧写「你…」「请按时到场」，发布者侧写「代课者 <名字>…」「请关注上课」。
 *
 * 两层都判定不出来时返回 neutral，由调用方给一条对两方都成立的中性文案（不猜人称）。
 */
type NoticeRole = "publisher" | "applicant" | "neutral";

const PUBLISHER_MARKERS = ["请关注上课", "代课者 ", "代课者「", "你或代课者"];
const APPLICANT_MARKERS = ["请按时到场", "你已上传", "你未上传", "你或发布者", "开始。你已上传"];

/** 后端 receiverRole → 客户端角色；不认识的值（含 null / 空串 / 老后端没有的字段）返回 null，交给调用方回退 */
function roleOfField(value?: string | null): NoticeRole | null {
  const raw = String(value || "")
    .trim()
    .toUpperCase();
  if (raw === "PUBLISHER") {
    return "publisher";
  }
  if (raw === "APPLICANT") {
    return "applicant";
  }
  return null;
}

/** 回退判定：只从正文里的互斥短语判角色（V36 之前唯一可用的办法，历史通知仍然要靠它） */
function roleOfContent(content?: string | null): NoticeRole {
  const text = String(content || "");
  if (PUBLISHER_MARKERS.some((marker) => text.includes(marker))) {
    return "publisher";
  }
  if (APPLICANT_MARKERS.some((marker) => text.includes(marker))) {
    return "applicant";
  }
  return "neutral";
}

/** 收件角色：优先用后端字段，字段为空时回退正文启发式（双保险） */
function roleOf(item: NotificationItem): NoticeRole {
  return roleOfField(item.receiverRole) || roleOfContent(item.content);
}

/** 同一 title 的「按角色分支」表条目 */
interface RoleCopy {
  publisher?: Pick<NoticeTemplate, "titleKey" | "bodyKey">;
  applicant?: Pick<NoticeTemplate, "titleKey" | "bodyKey">;
  neutral?: Pick<NoticeTemplate, "titleKey" | "bodyKey">;
}

/** 取第一个非空值（用来把"没抽到"从空串统一收敛成一个值，避免占位符被替换成空） */
function firstText(...values: unknown[]): string {
  for (const value of values) {
    const one = text(value).trim();
    if (one) {
      return one;
    }
  }
  return "";
}

/** 截断到可见字数（防御后端/词典把超长文本塞进标题占位符），不改任何业务语义 */
function shortText(value: unknown, max = 24): string {
  const one = text(value).trim();
  return one.length > max ? one.slice(0, max) : one;
}

/**
 * 所有传进 noticeTemplate 的取值统一先过这里：
 *   · null / undefined → 丢掉（`tf` 的 split/join 对 null 会插进字面量 "null"）；
 *   · 空串 / 全空白 → 丢掉，让词典里的 `{time}` 留在原地、由 noticeTemplate 的花括号兜底
 *     判定"渲染失败"并回退后端原文 —— 这样绝不会出现「下课时间 ，」或「「」」这种残缺句子；
 *   · 其余原样保留（含 `{` 的值由同一道兜底拦下）。
 */
function assembleParams(params: Record<string, string> = {}): Record<string, string> {
  const out: Record<string, string> = {};
  Object.keys(params).forEach((name) => {
    const value = params[name];
    if (typeof value === "string" && value.trim()) {
      out[name] = value;
    }
  });
  return out;
}

/**
 * ⭐ 占位符取值**唯一出口**：所有定型文案（title 与 body）都用这一份 params，杜绝
 * "正文给了、标题没给"这类的错配。
 *
 * 逐项说明（key 与 i18n 词典里的 `{...}` 一一对应）：
 *   · object —— 课程名。正文里的「」是历史/常态来源；后端 V36 起标题里也带课程名
 *     （「课程名」N 分钟后开始），所以标题是**兜底**来源，两个都抽、都抽不到用中性词「这节课」。
 *     注意：这里**不**用对象名替代（与 courseOf 一致，宁可中性也不编）。
 *   · name —— 对方昵称。后端按句式写「代课者 X …」，由 counterpartName 解析，取不到给「同学」。
 *   · minutes —— 仅在后端确实给了分钟数时才有值（形如「…N 分钟后开始」/旧的「距上课还有 N 分钟」）。
 *     取不到就**不放**这个 key：{minutes} 会由花括号兜底拦下 → 回退后端原文。
 *   · time —— 仅在后端确实写了「将于 X 开始」/「已于 X 下课」时才有值；取不到同上。
 */
function roleParams(item: NotificationItem, extra: Record<string, string> = {}): Record<string, string> {
  const content = text(item.content);
  const title = text(item.title);
  // ⚠️ 标题的「N 分钟后开始」是"后端确实给了分钟数"的唯一可靠标记；否则不从标题里凑数字，
  //    避免把课程名里的「60 分钟精讲」当成开课倒计时（minutesOf 内部已按后缀锚定）。
  const titleHasCountdown = /分钟后开始|距上课还有/.test(title);
  const params: Record<string, string> = {
    object: firstText(courseOf(content), titleHasCountdown ? courseOf(title) : "", t("msgCommonClass" as MsgKey)),
    name: firstText(counterpartName(content), actorFallback()),
    minutes: minutesOf(title),
    time: firstText(startAtOf(content), endAtOf(content)),
    ...extra
  };
  return assembleParams(params);
}

function pickRoleCopy(copy: RoleCopy, role: NoticeRole): Pick<NoticeTemplate, "titleKey" | "bodyKey"> | null {
  return copy[role] || copy.neutral || null;
}

function renderRoleCopy(item: NotificationItem, copy: RoleCopy, extra: Record<string, string> = {}): NoticeTemplate | null {
  const template = pickRoleCopy(copy, roleOf(item));
  if (!template) {
    return null;
  }
  return noticeTemplate(template.titleKey, template.bodyKey, roleParams(item, extra));
}

/**
 * 举报/申诉类的占位符：这一族标题与正文用到 `{object}`（被举报的内容/对象）、
 * `{typeLabel}`（违规类型）、`{name}`（申诉/举报人）。三个键在这里**统一补齐兜底值**，
 * 不再由调用点一个个传（历史上正是这种"有的传有的没传"，让某个 key 缺席、占位符原样外泄）。
 * `mark` 一并保留：目前 zh/en 词典里没有任何一句用到 `{mark}`（那三句是静态文案），
 * 传它是为了将来文案要用这个 key 时不必再回来改调用点。
 */
function reportParams(object: string, fallbackMark: string, appellant: string, typeLabel: string): Record<string, string> {
  return assembleParams({
    object: firstText(shortText(object), t("msgCommonClass" as MsgKey)),
    name: firstText(shortText(appellant), actorFallback()),
    typeLabel: firstText(shortText(typeLabel, 12), t("msgActorStudent" as MsgKey)),
    // ⚠️ fallbackMark 是**词典 key**（msgRepMarkHandled / msgRepMarkReply / msgRepMarkNotHandled），
    //    先查表再交给 tf；查不到就是空串 → assembleParams 丢掉这个 key → 花括号兜底回退后端原文，
    //    绝不会把 "undefined" 或 {mark} 显示给用户。
    mark: text(t(fallbackMark as I18nKey))
  });
}

function templateForReport(
  item: NotificationItem,
  object: string,
  fallbackMark: string
): NoticeTemplate | null {
  const title = String(item.title || "").trim();
  const content = item.content || "";
  const typeLabel = typeLabelOf(content);
  const appellant = actorName((content.match(/^「?([^」]+?)」?\s*就/) || [])[1] || "");
  const base = reportParams(object, fallbackMark, appellant, typeLabel);
  if (matches(title, ["被举报人已提出申诉"])) {
    return noticeTemplate("msgRepAppealed" as I18nKey, "msgRepAppealedBody" as I18nKey, base);
  }
  if (matches(title, ["收到用户申诉"])) {
    return noticeTemplate("msgRepAppealReceived" as I18nKey, "msgRepAppealReceivedBody" as I18nKey, base);
  }
  if (matches(title, ["申诉已受理"])) {
    return noticeTemplate("msgRepAppealAccepted" as I18nKey, "msgRepAppealAcceptedBody" as I18nKey, base);
  }
  if (matches(title, ["申诉未获支持"])) {
    return noticeTemplate("msgRepAppealRejected" as I18nKey, "msgRepAppealRejectedBody" as I18nKey, base);
  }
  if (matches(title, ["举报已处理"])) {
    return noticeTemplate("msgRepReportHandled" as I18nKey, "msgRepReportHandledBody" as I18nKey, base);
  }
  if (matches(title, ["举报未予处理"])) {
    return noticeTemplate("msgRepReportRejected" as I18nKey, "msgRepReportRejectedBody" as I18nKey, base);
  }
  if (matches(title, ["反馈已回复"])) {
    return noticeTemplate("msgRepFeedbackReplied" as I18nKey, "msgRepFeedbackRepliedBody" as I18nKey, base);
  }
  if (matches(title, ["反馈未予采纳"])) {
    return noticeTemplate("msgRepFeedbackRejected" as I18nKey, "msgRepFeedbackRejectedBody" as I18nKey, base);
  }
  if (matches(title, ["你收到一条平台警告"])) {
    return noticeTemplate("msgRepAccountWarned" as I18nKey, "msgRepAccountWarnedBody" as I18nKey, base);
  }
  if (matches(title, ["账号功能已受限"])) {
    return noticeTemplate("msgRepAccountRestricted" as I18nKey, "msgRepAccountRestrictedBody" as I18nKey, base);
  }
  if (matches(title, ["相关内容已被处理"])) {
    return noticeTemplate("msgRepContentRemoved" as I18nKey, "msgRepContentRemovedBody" as I18nKey, base);
  }
  // 管理员侧：举报 / 反馈提交（收件人是管理员）
  if (matches(title, ["收到用户举报"])) {
    return noticeTemplate("msgRepReportReceived" as I18nKey, "msgRepReportReceivedBody" as I18nKey, base);
  }
  if (matches(title, ["收到用户反馈"])) {
    return noticeTemplate("msgRepFeedbackReceived" as I18nKey, "msgRepFeedbackReceivedBody" as I18nKey, base);
  }
  // 认得出是举报结果、但没见过这句标题：不猜结论，明确告诉用户"点开看"
  return noticeTemplate("msgRepReportHandled" as I18nKey, fallbackMark as I18nKey, base);
}

/**
 * 定型文案表：key 由 type + bizType（必要时加 title）选定。
 * title 取值全部来自后端**字面量**（本轮逐处核对），因此这里是"已知集合内的路由"，
 * 不是"靠文案猜类型"。
 */
function noticeTemplateFor(item: NotificationItem): NoticeTemplate | null {
  const title = String(item.title || "").trim();
  const content = item.content || "";
  const type = String(item.type || "").toUpperCase();
  const biz = String(item.bizType || "").toUpperCase();
  // 举报正文里的「处理结果：」值是后端映射好的一个标签（警告 / 删除内容 / 限制功能 / 封禁账号…）
  const outcome = contentLine(content, "处理结果：");
  /**
   * ⭐ 占位符取值只有这一份（roleParams）：每个分支都把**完整** params 交给 noticeTemplate，
   * 由 renderRoleCopy / 直接调用统一传参。历史写法是各分支各传一部分（`{ object }` /
   * `{ object, name: third }`），标题与正文一旦要的 key 不一样就会漏 —— 本轮按分支逐条核对后统一。
   */
  const params = roleParams(item);

  // ---------- 申请类（type=APPLICATION）----------
  if (type === "APPLICATION") {
    if (matches(title, ["有人申请了你的代课"])) {
      return noticeTemplate("msgAppNewApply" as I18nKey, "msgAppNewApplyBody" as I18nKey, params);
    }
    if (matches(title, ["有人撤回了申请"])) {
      return noticeTemplate("msgAppWithdrawn" as I18nKey, "msgAppWithdrawnBody" as I18nKey, params);
    }
  }

  // ---------- 评价类（bizType=REVIEW）----------
  if (biz === "REVIEW") {
    if (matches(title, ["代课已完成"])) {
      return noticeTemplate("msgRevCompleted" as I18nKey, "msgRevCompletedBody" as I18nKey, params);
    }
    if (matches(title, ["代课已自动完成"])) {
      // 同一 title 发给两方，后端正文分别写「请为代课者 X 打星」/「请为发布者 X 打星」
      const publisherSide = /请为代课者/.test(content);
      return noticeTemplate(
        (publisherSide ? "msgRevAutoCompletedPublisher" : "msgRevAutoCompleted") as I18nKey,
        (publisherSide ? "msgRevAutoCompletedPublisherBody" : "msgRevAutoCompletedBody") as I18nKey,
        params
      );
    }
  }

  // ---------- 履约类（type=TASK）----------
  if (type === "TASK") {
    if (matches(title, ["申请已被接受"])) {
      return noticeTemplate("msgAppAccepted" as I18nKey, "msgAppAcceptedBody" as I18nKey, params);
    }
    if (matches(title, ["申请未被选中"])) {
      return noticeTemplate("msgAppNotPicked" as I18nKey, "msgAppNotPickedBody" as I18nKey, params);
    }
    if (matches(title, ["申请已被拒绝"])) {
      return noticeTemplate("msgAppRejectedByPublisher" as I18nKey, "msgAppRejectedByPublisherBody" as I18nKey, params);
    }
    if (matches(title, ["可以上传现场照片"])) {
      return noticeTemplate("msgFulPhotoDue" as I18nKey, "msgFulPhotoDueBody" as I18nKey, params);
    }
    if (matches(title, ["待对方上传照片"])) {
      return noticeTemplate("msgFulPhotoNeeded" as I18nKey, "msgFulPhotoNeededBody" as I18nKey, params);
    }
    if (matches(title, ["代课者已上传照片"])) {
      return noticeTemplate("msgFulPhotoUploaded" as I18nKey, "msgFulPhotoUploadedBody" as I18nKey, params);
    }
    if (matches(title, ["你已上传照片"])) {
      return noticeTemplate("msgFulSelfPhotoUploaded" as I18nKey, "msgFulSelfPhotoUploadedBody" as I18nKey, params);
    }
    if (matches(title, ["可以确认完成"])) {
      // 同一 title 发给两方，后端正文分别写「你或代课者…」/「你或发布者…」
      return renderRoleCopy(item, {
        publisher: { titleKey: "msgFulConfirmable" as I18nKey, bodyKey: "msgFulConfirmableBody" as I18nKey },
        applicant: {
          titleKey: "msgFulConfirmableApplicant" as I18nKey,
          bodyKey: "msgFulConfirmableApplicantBody" as I18nKey
        }
      });
    }
    if (matches(title, ["发布者已取消代课"])) {
      return noticeTemplate("msgFulCancelledByPublisher" as I18nKey, "msgFulCancelledByPublisherBody" as I18nKey, params);
    }
    if (matches(title, ["代课者已取消代课"])) {
      return noticeTemplate("msgFulCancelledByApplicant" as I18nKey, "msgFulCancelledByApplicantBody" as I18nKey, params);
    }
    if (matches(title, ["管理员已取消代课"])) {
      return renderRoleCopy(item, {
        publisher: {
          titleKey: "msgFulCancelledByAdminPublisher" as I18nKey,
          bodyKey: "msgFulCancelledByAdminPublisherBody" as I18nKey
        },
        applicant: {
          titleKey: "msgFulCancelledByAdminApplicant" as I18nKey,
          bodyKey: "msgFulCancelledByAdminApplicantBody" as I18nKey
        }
      });
    }
    if (matches(title, ["匹配任务已过期"])) {
      // 后端发布者侧写「代课者 X 未上传照片」，代课者侧写「你未上传照片」
      const publisherSide = /代课者\s/.test(content);
      return noticeTemplate(
        (publisherSide ? "msgFulExpiredMatchedPublisher" : "msgFulExpiredMatched") as I18nKey,
        (publisherSide ? "msgFulExpiredMatchedPublisherBody" : "msgFulExpiredMatchedBody") as I18nKey,
        params
      );
    }
    if (matches(title, ["代课任务已过期"])) {
      // 发布者侧标题里带「你发布的代课…」，代课者侧是「代课「X」…」（且只在匹配过时才发）
      const publisherSide = /^你发布的/.test(content);
      return noticeTemplate(
        (publisherSide ? "msgFulExpiredUnmatched" : "msgFulExpiredUnmatchedApplicant") as I18nKey,
        (publisherSide ? "msgFulExpiredUnmatchedBody" : "msgFulExpiredUnmatchedApplicantBody") as I18nKey,
        params
      );
    }
    // 「即将开始」：后端 V36 起标题带课程名（「课程名」N 分钟后开始），V36 之前是「距上课还有 N 分钟」。
    // 两种标题都认（历史通知的定型文案不会因此丢），判定本身仍只看 type=TASK + 标题形态。
    if (title.startsWith("距上课还有") || title.includes("分钟后开始")) {
      /**
       * ⚠️ 后端把课程名写进标题之前，课程名只在正文里（正文一直写「代课「X」将于…开始」），
       * 发布者侧正文还额外带了代课者名字。所以课程名取正文优先、标题兜底：
       *   · 课程名取到 → 按角色分句（发布者「你的「X」…」/ 代课者「你要代课的「X」…」）；
       *   · 课程名取不到 → 走 msgFulStartsInFallback（「有一节课 X 分钟后开始」），
       *     绝不渲染成空的【】/「」。
       * 收件角色由 roleOf 判定：V36 起优先后端 receiverRole 字段（即将开始这条一定会带上），
       * 历史通知才回退正文启发式。
       *
       * ⚠️ 这三条就是线上截图里的那两句（「你要代课的「{object}」{minutes} 分钟后开始」）：
       *    课程名与分钟数在这里都从**标题与正文两处**抽（courseOf 正文优先、标题兜底；
       *    minutesOf 只认「N 分钟后开始」/「距上课还有 N 分钟」），抽不到就走兜底句或回退后端原文。
       */
      const course = firstText(courseOf(content), courseOf(title));
      const time = startAtOf(content);
      if (!course) {
        return noticeTemplate("msgFulStartsInFallback" as I18nKey, "msgFulStartsInFallbackBody" as I18nKey, {
          minutes: minutesOf(title),
          time
        });
      }
      return renderRoleCopy(
        item,
        {
          publisher: { titleKey: "msgFulStartsInPublisher" as I18nKey, bodyKey: "msgFulStartsInPublisherBody" as I18nKey },
          applicant: {
            titleKey: "msgFulStartsInApplicant" as I18nKey,
            bodyKey: "msgFulStartsInApplicantBody" as I18nKey
          }
        },
        { object: course, minutes: minutesOf(title), time }
      );
    }
    // 「代课信息已更新」：字段审计结论 —— 后端只把变更内容写成一行自由文本
    // （TaskServiceImpl:503-518，无结构化字段），所以这里说清"谁 + 哪个对象 + 改了"，
    // 具体改了哪几项由"变更如下：…"原样接在后面。
    if (matches(title, ["代课信息已更新"])) {
      const publisher = (content.match(/发布者\s+([^，。]+?)\s*修改了/) || [])[1] || "";
      // ⚠️ 这一条只发给申请者/已接受的代课者，所以是「你收到的代课被改了」；
      //    发布者自己改完不会收到通知（TaskServiceImpl 只遍历 application）。
      const named = hasName(publisher);
      return noticeTemplate(
        "msgFulUpdatedBy" as I18nKey,
        "msgFulUpdatedBody" as I18nKey,
        { ...params, name: named ? actorName(publisher) : actorFallback() }
      );
    }
  }

  // ---------- 举报与申诉（bizType=REPORT）----------
  if (biz === "REPORT") {
    const mark = outcome
      ? "msgRepMarkHandled"
      : title.includes("回复")
        ? "msgRepMarkReply"
        : "msgRepMarkNotHandled";
    // 举报正文里的「」与课程名不是一回事（可能是被举报的用户名/内容），这里沿用原来的
    // objectName(content) 优先口径，交给 reportParams 补齐 {object}/{name}/{typeLabel}/{mark}。
    const reported = firstText(objectName(content), objectName(title), t("msgCommonClass" as MsgKey));
    return templateForReport(item, reported, mark);
  }

  // ---------- 账号与安全（type=SYSTEM + bizType=USER）----------
  // 标题沿用后端原话（本来就没有歧义），正文换成定型句说明"这意味着什么/要不要动手"，
  // 后端正文里的设备名 / IP / 时间 / 管理员备注由 extraLines 原样附在后面（一行不丢）。
  if (type === "SYSTEM") {
    // 这一族（msgSysNewDevice / msgSysSingleDevice / msgSysPasswordReset / msgSysUnbanned /
    // msgSysBanned / msgSysRestrictions 的 title+body）**全是静态句**，词典里没有任何占位符；
    // 仍然统一给一份 params：将来某句要用 `{object}` 时不必再回来补调用点，且 tf 会忽略多余的 key。
    const sysParams: Record<string, string> = { object: t("msgCommonClass" as MsgKey) };
    if (matches(title, ["账号安全提醒"])) {
      if (content.includes("仅允许一台设备在线")) {
        return noticeTemplate("msgSysSingleDevice" as I18nKey, "msgSysSingleDeviceBody" as I18nKey, sysParams);
      }
      return noticeTemplate("msgSysNewDevice" as I18nKey, "msgSysNewDeviceBody" as I18nKey, sysParams);
    }
    const accountTitleKey: Record<string, I18nKey> = {
      密码已被重置: "msgSysPasswordReset" as I18nKey,
      账号已解封: "msgSysUnbanned" as I18nKey,
      账号已被封禁: "msgSysBanned" as I18nKey,
      账号权限已调整: "msgSysRestrictions" as I18nKey
    };
    const accountKey = accountTitleKey[title];
    if (accountKey) {
      return noticeTemplate(accountKey, `${String(accountKey)}Body` as I18nKey, sysParams);
    }
  }
  return null;
}

/**
 * 列表/弹层共用的标题：定型文案优先，认不出来的一律原样显示后端 title。
 * ⚠️ 后端 title 已经写得很清楚（系统通知类），所以**不做**"把 title 也重写一遍"的无谓加工。
 */
function noticeTitle(item: NotificationItem): string {
  try {
    const template = noticeTemplateFor(item);
    return (template ? template.title : "") || text(item.title);
  } catch {
    // 解析出错也要有字：回退后端 title（宁可显示旧文案，绝不空白）
    return text(item.title);
  }
}

/** 只按后端原文分行：第一行当正文，其余当"结论行"（无定型文案时的口径） */
function rawBodyLines(content: string): { lead: string; highlights: string[] } {
  const parts = content
    .split("\n")
    .map((line) => line.trim())
    .filter(Boolean);
  return { lead: parts[0] || "", highlights: parts.slice(1) };
}

/**
 * 列表/弹层共用的正文行：
 *   lead       —— 定型正文（或降级后的后端原文首行）
 *   highlights —— 后端正文里的多行结论（"处理结果：… / 回复：… / 变更如下：…"）原样保留
 */
function noticeBodyLines(item: NotificationItem): { lead: string; highlights: string[] } {
  // 后端原文先算出来：下面任何一个分支出问题，都用它兜底（宁可显示旧文案，绝不空白）
  let content = "";
  try {
    content = text(item.content)
      .replace(/\r\n/g, "\n")
      .trim();
    const extras = extraLines(content);
    const template = noticeTemplateFor(item);
    if (!template) {
      return rawBodyLines(content);
    }
    // "changed" 类的定型正文之后必须保留"变更如下：…"，不能丢信息
    // ⚠️ 定型正文为空（词典缺失等）时回退后端原文首行，绝不返回空串
    const lead = template.body || content.split("\n")[0] || content || "";
    return { lead, highlights: extras };
  } catch {
    return content ? rawBodyLines(content) : { lead: "", highlights: [] };
  }
}

/**
 * 渲染前的最后一道闸（位置见 noticeTexts / noticeTextOf）：**不许把占位符花括号交给模板**。
 * 定型渲染（noticeTemplate）已经保证不会产生带花括号的结果，这里的对象是另外两条路：
 *   1. 后端原文兜底（rawBodyLines 的 lead / highlights）—— 后端不该发 `{object}`，
 *      但真发了也不能让它上屏；
 *   2. 任何将来新增的渲染分支忘了走 noticeTemplate。
 * 命中花括号 → 判定这一格"给不出可读文本" → 返回空串（标题→后端 title 的调用点已有兜底）；
 * ⚠️ 全部返回**空串**而不是 null：uni-app 模板里 null 不会渲染成文字，但空串语义清楚。
 */
function plainText(value: unknown): string {
  const one = text(value);
  return hasPlaceholder(one) ? "" : one;
}

/**
 * 列表与弹层共用的**预计算**结果：模板里原来对每条通知要调 noticeTitle 一次、
 * noticeBodyLines 4 次（1193 / 1226 / 1228 / 1233 一带），任何一次抛错都会让
 * 整个列表节点挂不上。这里改成每条只算一次、整表 try/catch，模板只读 map：
 *   · 算不出来的那条退回后端 title / content 原文；
 *   · 计算本身再怎么出问题，也只是这一条降级，不会连累其他行，更不会让整块空白。
 * ⚠️ 出口一律过 plainText：残留 `{...}` 的一律不给显示。
 */
const noticeTexts = computed(() => {
  const map = new Map<number, { title: string; lead: string; highlights: string[] }>();
  visibleNotices.value.forEach((item) => {
    const rawTitle = text(item.title);
    const rawContent = text(item.content)
      .replace(/\r\n/g, "\n")
      .trim();
    let title = rawTitle;
    let lines = rawBodyLines(rawContent);
    try {
      title = noticeTitle(item) || rawTitle;
    } catch {
      title = rawTitle;
    }
    try {
      lines = noticeBodyLines(item);
    } catch {
      lines = rawBodyLines(rawContent);
    }
    map.set(item.id, {
      title: plainText(title),
      lead: plainText(lines.lead || rawContent),
      highlights: (Array.isArray(lines.highlights) ? lines.highlights : []).map(plainText)
    });
  });
  return map;
});

type NoticeText = { title: string; lead: string; highlights: string[] };

function noticeTextOf(item: NotificationItem): NoticeText {
  const hit = noticeTexts.value.get(item.id);
  if (hit) {
    return hit;
  }
  // 列表之外的数据（弹层里可能已不在当前筛选结果内）：现算一次，仍然全程兜底
  const rawContent = text(item.content)
    .replace(/\r\n/g, "\n")
    .trim();
  let title = text(item.title);
  let lines = rawBodyLines(rawContent);
  try {
    title = noticeTitle(item) || title;
  } catch {
    // 保持后端原文
  }
  try {
    lines = noticeBodyLines(item);
  } catch {
    // 保持 rawBodyLines
  }
  return {
    title: plainText(title),
    lead: plainText(lines.lead || rawContent),
    highlights: (Array.isArray(lines.highlights) ? lines.highlights : []).map(plainText)
  };
}

/* ===========================================================================
 * 描述行的「对象名加深」：设计稿里描述是「《面向对象C》· 1位用户申请了…」这种
 * 「对象名深色 + 后半句灰」的形态。我们**不新增任何数据**，只是把 noticeTextOf()
 * 已经产出的那一句按引号（后端把对象名统一包在「」/“”里，见 objectName()）切成几段，
 * 引号里的那一段单独加深 —— 拆不出引号的（绝大多数系统通知）就原样一整段灰字，
 * 与改动前逐字一致。仍是单行文本流，外面那层 2 行省略不受影响。
 * =========================================================================== */
type LeadSegment = { text: string; strong: boolean };
const OBJECT_QUOTE_RE = /(「[^」]*」|“[^”]*”)/;

function splitLead(lead: string): LeadSegment[] {
  const source = text(lead);
  if (!source) {
    return [];
  }
  return source
    .split(OBJECT_QUOTE_RE)
    .filter((part) => Boolean(part))
    .map((part) => ({ text: part, strong: OBJECT_QUOTE_RE.test(part) }));
}

/** 每条通知只切一次（与 noticeTexts 同一份数据源，模板里只读 map） */
const noticeLeadSegments = computed(() => {
  const map = new Map<number, LeadSegment[]>();
  noticeTexts.value.forEach((one, id) => {
    map.set(id, splitLead(one.lead));
  });
  return map;
});

function leadSegmentsOf(item: NotificationItem): LeadSegment[] {
  const hit = noticeLeadSegments.value.get(item.id);
  if (hit) {
    return hit;
  }
  // 列表之外的数据（与 noticeTextOf 的兜底同一个口径）：退成一整段普通文本
  const one = noticeTextOf(item).lead;
  return one ? [{ text: one, strong: false }] : [];
}

// 弹层里也要用同一份文案：打开时就冻结，避免列表刷新后弹层内容跟着变
const activeTitle = ref("");
const activeLines = ref<{ lead: string; highlights: string[] }>({ lead: "", highlights: [] });

function isHandleNotice(item: NotificationItem) {
  return item.bizType === "REPORT";
}

/**
 * 标题里带这些字样的通知 = 「变动」类（被取消 / 过期 / 信息变更 / 管理员介入）。
 * ⚠️ 这个字符串匹配**只在"视觉分类"里用**（决定图标与 3px 色条的颜色），
 * 不再用来给标题染色 —— 原来 `isAlertTitle()` 把这三个标题涂成危险色，
 * 而"有人申请了你的代课"其实不需要红字警告（红色留给"变动/举报"这类真的坏消息）。
 */
function hasChangeTitle(title: string) {
  return title.includes("取消") || title.includes("过期") || title.includes("已更新") || title.includes("管理员");
}

// 这里就是改动前 openItem 里的跳转判断，一字未改地抽出来：
// 只有能落到具体任务/会话上的通知（申请、履约、评价类）才跳走，其余（系统通知、举报处理结果）不跳。
function canOpenTask(item: NotificationItem): item is NotificationItem & { bizId: number } {
  if (!item.bizId || item.bizType === "REPORT") {
    return false;
  }
  return item.bizType === "TASK" || item.bizType === "REVIEW";
}

// ⚠️ 原来这里还有一个 showsDetailHint()（只给系统通知显示「查看详情 ›」）。
// 本轮改版后每张卡片右端都有一枚统一的箭头 ›（设计稿的口径），
// 那句文字与之重复，已随模板一并移除；canOpenTask() 本身一字未动。

function goLogin() {
  uni.navigateTo({ url: "/pages/auth/login" });
}

function goFindTasks() {
  uni.switchTab({ url: "/pages/home/index" });
}

function toggleSearch() {
  searchOpen.value = !searchOpen.value;
  if (!searchOpen.value) {
    searchQuery.value = "";
  }
}

function openSettings() {
  uni.navigateTo({ url: "/pages/mine/settings" });
}

async function loadDots() {
  if (!userStore.isLoggedIn.value) {
    dots.value = { system: 0, task: 0, chat: 0 };
    return;
  }
  try {
    const [system, task, chat] = await Promise.all([
      unreadNotificationCount("SYSTEM"),
      unreadNotificationCount("TASK"),
      unreadChatCount()
    ]);
    dots.value = {
      system: Number(system || 0),
      task: Number(task || 0),
      chat: Number(chat || 0)
    };
  } catch {
    // ignore
  }
}

async function loadNotices(reset = false) {
  if (!userStore.isLoggedIn.value) {
    list.value = [];
    return;
  }
  if (loading.value && !reset) {
    return;
  }
  if (reset) {
    page.value = 1;
    finished.value = false;
  }
  // 本次请求前列表是否为空：为空说明加载失败后没有内容可显示，交给 ListState 展示原因和重试
  const requestId = ++noticeRequestId;
  const requestScope = noticeScope.value;
  const requestPage = page.value;
  const first = list.value.length === 0;
  loading.value = true;
  noticeError.value = "";
  try {
    const data = await listNotifications(requestPage, 20, requestScope);
    if (requestId !== noticeRequestId || requestScope !== noticeScope.value) {
      return;
    }
    const rows = data.list || [];
    list.value = reset ? rows : list.value.concat(rows);
    noticeTotal.value = Number(data.total || 0);
    finished.value = list.value.length >= data.total;
  } catch (err) {
    if (requestId !== noticeRequestId) {
      return;
    }
    const message = (err as Error).message || "加载失败";
    noticeError.value = message;
    // 已经有内容时只用轻提示：这时若让失败态顶掉列表，比不提示更糟
    if (!first) {
      toast.error(message);
    }
  } finally {
    if (requestId === noticeRequestId) {
      loading.value = false;
      uni.stopPullDownRefresh();
    }
  }
}

async function loadChats() {
  if (!userStore.isLoggedIn.value) {
    chats.value = [];
    return;
  }
  const first = chats.value.length === 0;
  const requestId = ++chatRequestId;
  chatLoading.value = true;
  chatError.value = "";
  try {
    const rows = await listChats();
    if (requestId === chatRequestId) {
      chats.value = rows;
    }
  } catch (err) {
    if (requestId !== chatRequestId) {
      return;
    }
    const message = (err as Error).message || "加载失败";
    chatError.value = message;
    // 已经有私信列表时只用轻提示，避免失败态把已有内容顶掉
    if (!first) {
      toast.error(message);
    }
  } finally {
    if (requestId === chatRequestId) {
      chatLoading.value = false;
      uni.stopPullDownRefresh();
    }
  }
}

async function load(reset = false) {
  if (tab.value === "chat") {
    await loadChats();
  } else {
    await loadNotices(reset);
  }
  await loadDots();
  await refreshMessageBadge();
}

function switchTab(next: TabKey) {
  if (tab.value === next) {
    return;
  }
  tab.value = next;
  if (next !== "task") {
    taskFilter.value = "all";
  }
  if (next !== "chat") {
    list.value = [];
    noticeTotal.value = 0;
    noticeError.value = "";
    page.value = 1;
    finished.value = false;
  }
  load(true);
}

function switchTaskFilter(next: "all" | TaskKind) {
  taskFilter.value = next;
}

function loadNextNoticePage() {
  if (loading.value || finished.value) {
    return;
  }
  page.value += 1;
  void loadNotices(false);
}

function pickBusyTab() {
  const { system, task, chat } = dots.value;
  const current = tab.value === "system" ? system : tab.value === "task" ? task : chat;
  if (current > 0) {
    return;
  }
  if (chat > 0 && chat >= task && chat >= system) {
    tab.value = "chat";
    return;
  }
  if (task > 0) {
    tab.value = "task";
    return;
  }
  if (system > 0) {
    tab.value = "system";
    return;
  }
  if (!pickedBusy.value) {
    tab.value = "task";
  }
  pickedBusy.value = true;
}

async function openItem(item: NotificationItem) {
  if (item.readFlag !== 1) {
    try {
      await markNotificationRead(item.id);
      item.readFlag = 1;
      await loadDots();
      await refreshMessageBadge();
    } catch {
      // 仍允许进入详情
    }
  }
  if (canOpenTask(item)) {
    const focus = noticeFocus(item);
    uni.navigateTo({ url: taskDetailUrl(item.bizId, focus) });
    return;
  }
  // 系统通知等没有可跳转的任务/会话：弹层展示完整标题、正文和时间
  activeNotice.value = item;
  // 文案在打开时冻结成同一份，避免列表后台刷新后弹层内容跟列表不一致
  // ⚠️ 走与列表同一个预计算结果（noticeTextOf 自带 try/catch 与后端原文兜底）：
  //    弹层里这两行原是唯一没被兜住的调用点，一旦抛错弹层正文会整块空白。
  const frozen = noticeTextOf(item);
  activeTitle.value = frozen.title;
  activeLines.value = { lead: frozen.lead, highlights: frozen.highlights };
  noticeOpen.value = true;
}

function openChat(item: ChatSessionItem) {
  uni.navigateTo({ url: `/pages/message/chat?id=${item.id}` });
}

function openPeer(item: ChatSessionItem) {
  if (!item.peerUserId) {
    return;
  }
  uni.navigateTo({ url: `/pages/mine/user?id=${item.peerUserId}` });
}

/**
 * 顶部提示条点击：优先打开那条最新的未读通知（复用 openItem 的既有行为：
 * 标记已读 → 能落到任务上的跳任务详情，其余弹层展示）。
 * 列表里找不到未读项时（未读数来自服务端全量、本页分页还没翻到），
 * 回落到原有的「全部已读」，不凭空跳转、不新增页面。
 */
function openHint() {
  const target = hintTarget.value;
  if (target) {
    void openItem(target);
    return;
  }
  void handleReadAll();
}

async function handleReadAll() {
  try {
    await markAllNotificationsRead(noticeScope.value);
    list.value = list.value.map((item) => ({ ...item, readFlag: 1 }));
    await loadDots();
    await refreshMessageBadge();
  } catch (error) {
    toast.error((error as Error).message || "操作失败");
  }
}

onShow(async () => {
  await loadDots();
  pickBusyTab();
  await load(true);
});

useLiveUpdates((event) => {
  if (!event || event.type === "NOTICE" || event.type === "MESSAGE") {
    load(true);
  }
});

onPullDownRefresh(() => {
  load(true);
});

onReachBottom(() => {
  if (tab.value === "chat" || finished.value || loading.value) {
    return;
  }
  page.value += 1;
  loadNotices(false);
});
</script>

<template>
  <view class="page kean-msg" :class="{ skinned: wallpaperOn }">
    <PageBackdrop :src="wallpaperImage" />
    <view v-if="!userStore.isLoggedIn" class="guest msg-guest">
      <wd-status-tip image="content" tip="登录后查看系统通知、申请履约与私信" />
      <wd-button type="primary" @click="goLogin">去登录</wd-button>
    </view>
    <template v-else>
      <view class="msg-shell">
        <!-- 标题、分类说明与常用入口；一级分类保持可见，二级筛选随列表滚动。 -->
        <view class="status-bar" :style="{ height: statusBarHeight + 'px' }" />
        <view class="msg-nav">
          <view class="msg-nav__heading">
            <text class="msg-nav__title">消息</text>
            <text class="msg-nav__subtitle">{{ messageDescription }}</text>
          </view>
          <view class="msg-nav__actions">
            <view class="msg-nav__action" @click="toggleSearch"><wd-icon :name="searchOpen ? 'close' : 'search'" /></view>
            <view class="msg-nav__action" @click="openSettings"><wd-icon name="setting" /></view>
          </view>
        </view>
        <view v-if="searchOpen" class="msg-search">
          <wd-icon name="search" />
          <input v-model="searchQuery" confirm-type="search" placeholder="搜索通知或聊天" />
          <text v-if="searchQuery" class="msg-search__clear" @click="searchQuery = ''">清除</text>
        </view>
        <!-- A 节：三档平级 tab（结构 / 交互与原 tabs 完全一致）。本轮视觉改成
             设计稿的「纯文字 + 选中项文字下方短下划线」：没有胶囊、没有灰条底，
             三档左对齐、档间距 28px，整行下方一条极浅的整宽分隔线。
             样式分别落在本文件 scoped（几何）与 styles/design-messages.css（字阶/颜色）。 -->
        <view class="msg-tabs">
          <view class="msg-tab" :class="{ 'msg-tab--on': tab === 'system' }" @click="switchTab('system')">
            <text class="msg-tab__label">系统</text>
            <view v-if="dots.system > 0" class="msg-tab__dot" />
          </view>
          <view class="msg-tab" :class="{ 'msg-tab--on': tab === 'task' }" @click="switchTab('task')">
            <text class="msg-tab__label">申请与履约</text>
            <view v-if="dots.task > 0" class="msg-tab__dot" />
          </view>
          <view class="msg-tab" :class="{ 'msg-tab--on': tab === 'chat' }" @click="switchTab('chat')">
            <text class="msg-tab__label">私信</text>
            <view v-if="dots.chat > 0" class="msg-tab__dot" />
          </view>
        </view>

        <template v-if="tab !== 'chat'">
          <view v-if="tab === 'task'" class="msg-chips">
            <view class="msg-chip" :class="{ 'msg-chip--on': taskFilter === 'all' }" @click="switchTaskFilter('all')">
              全部
              <view v-if="dots.task > 0" class="msg-chip__dot" />
            </view>
            <view
              v-for="kind in taskKindStats"
              :key="kind.key"
              class="msg-chip"
              :class="{ 'msg-chip--on': taskFilter === kind.key }"
              @click="switchTaskFilter(kind.key)"
            >
              {{ kind.label }}
              <view v-if="kind.unread > 0" class="msg-chip__dot" />
            </view>
          </view>
          <!-- 顶部提示条：数据全部来自现有未读计数 + 已加载列表里最新的一条未读通知
               （见脚本里的 hintTarget / hintTitle / hintSubtitle），
               点击复用 openItem 的既有行为；没有可打开的未读项时回落到原有的「全部已读」。 -->
          <view
            v-if="currentUnread > 0 && !searchQuery && (tab === 'system' || taskFilter === 'all')"
            class="msg-hintbar"
            @click="openHint"
          >
            <view class="msg-hintbar__ico"><wd-icon name="notification" /></view>
            <view class="msg-hintbar__main">
              <text class="msg-hintbar__text">{{ hintTitle }}</text>
              <text v-if="hintSubtitle" class="msg-hintbar__sub">{{ hintSubtitle }}</text>
            </view>
            <text class="msg-hintbar__arrow">›</text>
          </view>
          <ListState
            :loading="listLoading"
            :error="currentError"
            :empty="noticeEmpty"
            :empty-text="searchQuery ? '没有找到匹配的消息' : noticeEmptyText"
            @retry="load(true)"
          >
            <template #loading>
              <view class="msg-loading" aria-label="加载消息">
                <view v-for="index in 5" :key="index" class="msg-skel">
                  <view class="msg-skel__avatar" />
                  <view class="msg-skel__main">
                    <view class="msg-skel__line msg-skel__line--name" />
                    <view class="msg-skel__line msg-skel__line--text" />
                  </view>
                </view>
              </view>
            </template>
            <template #empty>
              <view class="msg-empty">
                <view class="msg-empty__icon"><wd-icon :name="tab === 'system' ? 'notification' : 'chat'" /></view>
                <text class="msg-empty__title">{{ searchQuery ? '没有找到匹配的消息' : tab === 'system' ? '暂时没有平台提醒' : taskFilter === 'all' ? '还没有申请或履约消息' : '这个分类还没有消息' }}</text>
                <text class="msg-empty__copy">{{ searchQuery ? '试试更短的关键词，或清除搜索继续浏览。' : tab === 'system' ? '账号和平台动态会在这里及时通知你。' : '收到新的申请、进度变化或评价后，会按时间显示在这里。' }}</text>
                <wd-button v-if="searchQuery" plain size="small" @click="searchQuery = ''">清除搜索</wd-button>
                <wd-button v-if="tab === 'task' && taskFilter !== 'all'" plain size="small" @click="switchTaskFilter('all')">查看全部消息</wd-button>
              </view>
            </template>
            <template #default>
              <view class="msg-list">
                <view class="msg-list__heading">
                  <text>{{ tab === 'system' ? '系统通知' : taskHeading }}</text>
                  <text v-if="tab === 'system' || taskFilter === 'all'">{{ noticeTotal }} 条</text>
                </view>
                <view v-if="filteredNoticeNeedsMore && !visibleNotices.length" class="msg-filter-more">
                  <text>当前已加载消息中没有匹配项，继续查找更早的记录</text>
                  <text class="msg-filter-more__action" @click="loadNextNoticePage">继续加载</text>
                </view>
                <template v-for="group in noticeDayGroups" :key="group.key">
                  <view class="msg-day">{{ noticeDayLabel(group.key) }}</view>
                  <view
                    v-for="item in group.items"
                    :key="item.id"
                    class="msg-notice"
                    :class="[`msg-notice--${noticeVisual(item)}`, { 'msg-notice--unread': item.readFlag !== 1 }]"
                    @click="openItem(item)"
                  >
                    <view class="msg-notice__tile">
                      <view class="msg-notice__ico" :class="noticeIconClass(item)" />
                    </view>
                    <view class="msg-notice__main">
                      <view class="msg-notice__top">
                        <!-- 未读只有「标题左侧一枚 7px 圆点」这一个标记（不再叠加标题加粗，
                             免得同一个状态被编码两次，见 design-messages.css 的说明） -->
                        <view v-if="item.readFlag !== 1" class="msg-notice__unread-dot" />
                        <text class="msg-notice__title">{{ noticeTextOf(item).title }}</text>
                      </view>
                      <view class="msg-notice__body" :class="{ 'msg-notice__body--handle': isHandleNotice(item) }">
                        <template v-if="isHandleNotice(item)">
                          <view>{{ noticeTextOf(item).lead }}</view>
                          <view
                            v-for="(line, index) in noticeTextOf(item).highlights"
                            :key="index"
                            class="msg-notice__result"
                          >{{ line }}</view>
                        </template>
                        <!-- 描述里的对象名（后端写在「」/“”里）单独加深：仍是一整句文本流，
                             外面那层「最多 2 行 + 省略号」不受影响 -->
                        <text v-else class="msg-notice__lead">
                          <text
                            v-for="(seg, segIndex) in leadSegmentsOf(item)"
                            :key="`lead-${segIndex}`"
                            :class="{ 'msg-notice__lead-strong': seg.strong }"
                          >{{ seg.text }}</text>
                        </text>
                      </view>
                      <view v-if="noticeTodo(item)" class="msg-notice__actions">
                        <text class="msg-chip--todo">{{ t("msgTodo") }}</text>
                      </view>
                      <view class="msg-notice__meta">
                        <text class="msg-notice__type">{{ noticeTypeLabel(item) }}</text>
                      </view>
                    </view>
                    <!-- 右列：时间在上、箭头在下（设计稿口径）；箭头是整卡可点的统一暗示 -->
                    <view class="msg-notice__aside">
                      <text class="msg-notice__time">{{ formatNoticeTime(item.createdAt) }}</text>
                      <text class="msg-notice__chevron">›</text>
                    </view>
                  </view>
                </template>
              </view>
              <view class="msg-end">{{ finished ? "没有更多了" : "上拉加载更多" }}</view>
            </template>
          </ListState>
        </template>

        <template v-else>
          <ListState
            :loading="listLoading"
            :error="currentError"
            :empty="visibleChats.length === 0"
            :empty-text="searchQuery ? '没有找到匹配的私信' : '暂无私信'"
            @retry="load(true)"
          >
            <template #loading>
              <view class="msg-loading" aria-label="加载私信">
                <view v-for="index in 5" :key="index" class="msg-skel">
                  <view class="msg-skel__avatar" />
                  <view class="msg-skel__main">
                    <view class="msg-skel__line msg-skel__line--name" />
                    <view class="msg-skel__line msg-skel__line--text" />
                  </view>
                </view>
              </view>
            </template>
            <template #empty>
              <view class="msg-empty">
                <view class="msg-empty__icon"><wd-icon name="chat" /></view>
                <text class="msg-empty__title">{{ searchQuery ? '没有找到匹配的私信' : '还没有开始对话' }}</text>
                <text class="msg-empty__copy">{{ searchQuery ? '换个关键词试试，或清除搜索查看全部会话。' : '在课程求助或同学主页发起私信，确认教室和上课安排。' }}</text>
                <wd-button v-if="searchQuery" plain size="small" @click="searchQuery = ''">清除搜索</wd-button>
                <wd-button v-else type="primary" size="small" @click="goFindTasks">浏览课程求助</wd-button>
              </view>
            </template>
            <template #default>
            <view class="msg-list">
              <view class="msg-list__heading"><text>最近联系</text><text>{{ visibleChats.length }} 位</text></view>
              <view v-for="item in visibleChats" :key="item.id" class="msg-chat" :class="{ 'msg-chat--unread': item.unreadCount > 0 }" @click="openChat(item)">
                <FallbackImage
                  v-if="item.peerAvatarUrl"
                  class="msg-chat__avatar msg-chat__avatar-img"
                  :src="resolveMediaUrl(item.peerAvatarUrl)"
                  mode="aspectFill"
                  @click.stop="openPeer(item)"
                />
                <view v-else class="msg-chat__avatar msg-chat__avatar--text" @click.stop="openPeer(item)">
                  <text>{{ (item.peerNickname || "同").slice(0, 1) }}</text>
                </view>
                <view class="msg-chat__main">
                  <view class="msg-chat__top">
                    <view class="msg-chat__name-row">
                      <text class="msg-chat__name">{{ item.peerNickname || "同学" }}</text>
                      <view v-if="freshChats.has(item.id)" class="msg-chat__fresh" />
                      <text v-if="item.peerBanned" class="msg-chat__tag msg-chat__tag--banned">已封禁</text>
                      <text v-else-if="item.peerMuted" class="msg-chat__tag">已禁言</text>
                    </view>
                    <text class="msg-chat__time">{{ formatRelativeStamp(item.lastMessageAt) }}</text>
                  </view>
                  <view class="msg-chat__bottom">
                    <view class="msg-chat__preview">
                      <view
                        v-if="previewKindOf(item) !== 'text'"
                        class="msg-chat__ptype"
                        :class="`msg-chat__ptype--${previewKindOf(item)}`"
                      />
                      <text class="msg-chat__preview-text">
                        <template v-for="(segment, segIndex) in previewSegmentsOf(item)" :key="`pv-${segIndex}`">
                          <FallbackImage
                            v-if="segment.type === 'emoji'"
                            class="msg-chat__preview-emoji"
                            :src="segment.path"
                            mode="aspectFit"
                          />
                          <template v-else>{{ segment.text }}</template>
                        </template>
                      </text>
                    </view>
                    <view v-if="item.unreadCount > 0" class="msg-chat__badge">{{ item.unreadCount > 99 ? "99+" : item.unreadCount }}</view>
                  </view>
                </view>
              </view>
            </view>
            </template>
          </ListState>
        </template>
      </view>
    </template>
    <!-- 系统通知详情：接口没有「按 id 查单条」，直接用列表里已有的这条数据展示完整内容 -->
    <wd-popup
      v-model="noticeOpen"
      position="bottom"
      safe-area-inset-bottom
      :z-index="999"
      custom-style="border-radius:16px 16px 0 0"
    >
      <view v-if="activeNotice" class="msg-sheet">
        <view class="msg-sheet__head">
          <text class="msg-sheet__title">{{ activeTitle }}</text>
          <text class="msg-sheet__time">{{ formatNoticeTime(activeNotice.createdAt) }}</text>
        </view>
        <view class="msg-sheet__body">
          <template v-if="isHandleNotice(activeNotice)">
            <view>{{ activeLines.lead }}</view>
            <view
              v-for="(line, index) in activeLines.highlights"
              :key="index"
              class="msg-notice__result"
            >{{ line }}</view>
          </template>
          <template v-else>{{ activeLines.lead }}</template>
        </view>
        <view class="msg-sheet__foot">
          <wd-button type="primary" block @click="noticeOpen = false">知道了</wd-button>
        </view>
      </view>
    </wd-popup>
    <wd-toast />
  </view>
</template>

<style scoped>
/* ===========================================================================
 * 这个页面**只留结构与尺寸**（flex / margin / padding / 宽度上限）：
 * 颜色、字阶、玻璃、圆角、徽标、图标全部在 src/styles/design-messages.css
 * （作用域 .kean-msg）里，因为壁纸皮肤与深色皮肤的取值只在全局表里有一份 ——
 * 页面 scoped 里再写一遍颜色，换肤时必漏。改动前先读那份文件的 A~E 五节规范。
 * =========================================================================== */
.page {
  position: relative;
  min-height: 100vh;
  /* ⚠️⚠️ 这两行是吸顶（.msg-tabs 的 position: sticky）的**生死线**：只要有一个轴是
     hidden / auto / scroll，这一层就成了**滚动容器**，sticky 立刻改以它为参照；
     而 .page 是 min-height（高度随内容长、自己从不滚动）→ 吸顶写了等于没写。
     所以两行都必须是"不建滚动容器"的值：
       · `overflow-x: visible`（第一行＝**老内核兜底**）：不认 clip 的内核在解析期丢掉
         第二行、留下的就是它。visible 是唯一绝对不建滚动容器的取值。
         为什么安全：横向裁剪的最终落点在**视口** —— uni-h5 的 `body{overflow-x:hidden}`
         (base.css:14-17) 会被规范传导到视口，页面横向照样拉不动；而且本页没有任何一处
         会横向溢出到 .page（.msg-chips 自己就是 overflow-x:auto 的横向滚动容器，
         行内长文本都有 overflow:hidden + 省略号），两条 tab 底衬的外阴影左右各只多出
         10px，正好落在视口裁剪区之外 → 裁剪位置与观感都和原来一致。
       · `overflow-x: clip`（第二行＝**现代内核**）：只裁剪、不建滚动容器（这正是它
         区别于 hidden 的设计用途），横向裁剪语义一行不减。
     ⚠️⚠️ 只改这一层**不够**：真正挡住吸顶的是更外面的 uni-page-body
     （App.vue 的 `page{overflow-x:hidden}` 被 uni-app H5 改写成
     `uni-page-body{…;overflow-x:hidden}`）—— 覆盖规则在 styles/design-messages.css
     的 A 节（`uni-page[data-page="pages/message/index"] uni-page-body`），
     **两处必须成套看**，缺任何一处三档 tab 都不会吸顶。
     （真正滚的是 document：uni-h5 的下拉刷新就是读
     document.documentElement.scrollTop 判断的，见 uni-h5.es.js:27530。） */
  overflow-x: visible;
  overflow-x: clip;
  max-width: 100%;
}

/* 自绘导航页需让出状态栏空间，一级分类保持吸顶。 */
.status-bar {
  width: 100%;
}
/* 头部：左对齐大标题「消息」30px/700 + 随档变化的副标题；右侧搜索、设置两枚 22px 描边图标。
   ⚠️ 这一块**不吸顶**（标题随内容滚走），滚动行为与改动前完全一致。 */
.msg-nav {
  display: flex;
  align-items: center;
  min-height: 64px;
  padding: 8px 16px 10px;
  gap: 12px;
}
.msg-nav__heading { display: flex; flex: 1; min-width: 0; flex-direction: column; gap: 6px; }
.msg-nav__title { color: var(--kean-t1); font-size: 30px; font-weight: 700; line-height: 1.15; }
.msg-nav__subtitle { color: var(--kean-t3); font-size: 13px; line-height: 1.4; }
.msg-nav__actions {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: flex-end;
  gap: 20px;
}
.msg-nav__action { display: flex; width: 32px; height: 36px; align-items: center; justify-content: center; font-size: 22px; }
.msg-search {
  display: flex;
  align-items: center;
  gap: 9px;
  margin: 4px 16px 8px;
  padding: 0 12px;
  min-height: 40px;
  border-radius: 12px;
}
.msg-search input { flex: 1; min-width: 0; height: 38px; font-size: 13px; }
.msg-search__clear { flex-shrink: 0; font-size: 11px; }
.msg-list__heading {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  padding: 12px 16px 6px;
  font-size: 15px;
  font-weight: 700;
}
.msg-list__heading text + text { font-size: 10px; font-weight: 500; }
.msg-nav__action { color: var(--kean-t1); }
.msg-shell { gap: 0; padding-top: 0; }
.msg-search { color: var(--kean-t3); background: var(--kean-card); border: 1px solid var(--kean-line); }
.msg-search input { color: var(--kean-t1); }
.msg-search__clear { color: var(--kean-primary-active); }
.msg-list__heading { color: var(--kean-t1); }
.msg-list__heading text + text { color: var(--kean-t3); }
/* 三档 tab：纯文字 + 选中项文字下方一条短下划线；三档左对齐（不均分），档间距 28px。
   ⚠️ 吸顶与「不透明底」一个字都没动：background 仍是页面底色（吸顶后下面有行滚过），
      两侧 16px 的缝仍由同一色的外阴影底衬补上（box-shadow 保留，未删）。 */
.msg-tabs {
  top: env(safe-area-inset-top);
  gap: 28px;
  margin: 0 16px;
  padding: 0;
  border-bottom: 0;
  border-radius: 0;
  background: var(--kean-msg-bg, #fff);
  box-shadow:
    calc(-1 * var(--kean-sp-4) - 10px) 0 0 0 var(--kean-msg-bg),
    calc(var(--kean-sp-4) + 10px) 0 0 0 var(--kean-msg-bg);
}
/* tab 区下方那条极浅的**整宽**分隔线：本行左右各有 16px 外边距，所以用 ::after 各外扩
   16px 撑到页面两侧（.page 的 overflow-x: clip 正好在页面边界裁掉多余部分）。
   ⚠️ pointer-events: none —— 这条 1px 压在 tab 的底边上，不能把点击吃掉。 */
.msg-tabs::after {
  position: absolute;
  right: -16px;
  bottom: 0;
  left: -16px;
  height: 1px;
  background: var(--kean-msg-line);
  content: "";
  pointer-events: none;
}
.msg-tab,
.msg-tab--on {
  height: 44px;
  border-radius: 0;
  background: transparent;
  color: var(--kean-t3);
  font-size: 15px;
  font-weight: 500;
}
.msg-tab--on { position: relative; color: var(--kean-t1); font-weight: 700; }
/* 选中下划线：贴着**文字**画（宽度 = 文字宽度），不与文字重叠（下方留 6px）。
   ⚠️ 锚点从「整档」搬到 .msg-tab__label（position: relative）—— 原来是按档宽取
      left/right 22%，档宽不等长时下划线宽度就对不上文字。
   margin-top 与 padding-bottom 成对（6px）：抵消 padding 造成的 3px 上移，
   让文字仍严格居中于 44px 的档高。 */
.msg-tab__label { position: relative; display: inline-block; margin-top: 6px; padding-bottom: 6px; }
.msg-tab--on .msg-tab__label::after {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  height: 3px;
  border-radius: 2px;
  background: var(--kean-primary);
  content: "";
}
.msg-tab__dot { width: 5px; height: 5px; }
.msg-chips { padding: 10px 16px 12px; }
.msg-chip { height: 30px; padding: 0 14px; border: 1px solid var(--kean-msg-line); background: var(--kean-msg-row); color: var(--kean-sub); font-size: 13px; }
.msg-chip--on { border-color: var(--kean-primary); background: var(--kean-primary); color: #fff; }
.msg-chip--on .msg-chip__dot { background: #fff; }
/* 顶部提示条：主色极浅底的圆角卡（34px 圆角图标块 + 标题/副标题 + 右箭头）。
   底色与字色在 styles/design-messages.css（三种皮肤各一份），这里只写几何。
   ⚠️ 上边距 10px 是给「系统」档（tab 分隔线下方）用的；「申请与履约」档上面还有
      一层 chip（自带 12px 下内边距），所以那一档实际留白是 22px —— 比设计稿略松一点，
      换取两个档共用同一条规则（不为一个档多写一套选择器）。 */
.msg-hintbar { margin: 10px 16px 12px; padding: 12px; border: 0; border-radius: 12px; }
.msg-hintbar__ico { display: flex; width: 34px; height: 34px; flex-shrink: 0; align-items: center; justify-content: center; border-radius: 12px; font-size: 18px; }
.msg-hintbar__main { display: flex; flex: 1; min-width: 0; flex-direction: column; gap: 2px; }
.msg-hintbar__arrow { flex-shrink: 0; font-size: 16px; line-height: 1; }
/* 通知行 = 独立白卡：左右页边距 16px、卡片间距 10px、内边距 13px。
   底色/边框/圆角在 styles/design-messages.css（白卡比页面底色亮，三个皮肤都走现有令牌）。 */
.msg-notice { align-items: flex-start; margin: 0 16px 10px; padding: 13px 14px; }
.msg-notice__tile { width: 42px; height: 42px; border: 0; border-radius: 12px; }
.msg-notice__ico { width: 21px; height: 21px; background-size: 21px 21px; }
.msg-notice__top { align-items: center; }
/* 未读标记：标题左侧一枚 7px 圆点（与改动前的颜色同一个值，只改了尺寸与位置）。
   ⚠️ 同时**取消**了「未读标题加粗」那条规则 —— 按设计稿，同一个状态只用「点」表达一次。 */
.msg-notice__unread-dot { flex: 0 0 7px; width: 7px; height: 7px; border-radius: 50%; background: #e45b63; }
/* 右列：时间在上、箭头在下 */
.msg-notice__aside { display: flex; flex-shrink: 0; flex-direction: column; align-items: flex-end; gap: 6px; }
.msg-notice__chevron { font-size: 16px; line-height: 1; }
.msg-notice__meta { margin-top: 5px; }
.msg-filter-more { display: flex; flex-direction: column; align-items: center; gap: 10px; padding: 34px 24px; color: var(--kean-t3); font-size: 12px; line-height: 1.6; text-align: center; }
.msg-filter-more__action { padding: 7px 16px; border: 1px solid rgba(50, 111, 224, .2); border-radius: 18px; color: var(--kean-primary-active); font-weight: 600; }
.msg-chat { padding: 13px 16px; }
.msg-chat + .msg-chat::before { left: 72px; right: 16px; }
.msg-chat__name { max-width: min(55vw, 220px); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.msg-empty { border: 0; border-radius: 0; background: transparent; }
.page.skinned .msg-empty { border: 0; border-radius: 0; background: transparent; }
.msg-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 9px;
  margin: 24px 16px;
  padding: 20px 18px;
  border: 0;
  background: transparent;
  text-align: center;
}

.msg-empty__icon {
  display: flex;
  width: 46px;
  height: 46px;
  align-items: center;
  justify-content: center;
  margin-bottom: 3px;
  border: 1px solid var(--kean-line);
  border-radius: 50%;
  color: var(--kean-primary-active);
  background: var(--kean-primary-soft);
  font-size: 23px;
}

.msg-empty__title { color: var(--kean-t1); font-size: 15px; font-weight: 700; }
.msg-empty__copy { max-width: 280px; color: var(--kean-t3); font-size: 11px; line-height: 1.65; }
.msg-empty :deep(.wd-button) { margin-top: 5px; border-radius: 11px; }
.page.skinned .msg-empty { border-color: rgba(255,255,255,.13); background: rgba(18,23,34,.86); }
.page.skinned .msg-empty__title { color: #eaf1fb; }
.page.skinned .msg-empty__copy { color: #b2bfd1; }
.page.skinned .msg-empty__icon { border-color: rgba(255,255,255,.12); color: #c4d7f2; background: rgba(93,131,190,.22); }

/* 会话预览里的表情：内联小图（18px），与 13px 的预览字对齐。
   ⚠️ 选择器特意写成两层（0,2,0）：全局表里 `.kean-msg .msg-chat__preview-text`
   是 0,2,0，靠这个特异性压住，不用 !important。 */
.msg-chat__preview-text .msg-chat__preview-emoji {
  display: inline-block;
  width: 18px;
  height: 18px;
  margin: 0 1px;
  vertical-align: middle;
}
</style>
