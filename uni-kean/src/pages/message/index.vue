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
import { formatNoticeDay, formatNoticeTime, formatRelativeStamp } from "@/utils/format";
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
const error = ref("");
const finished = ref(false);
const page = ref(1);
const dots = ref({ system: 0, task: 0, chat: 0 });
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

const groupedTaskNotices = computed(() => {
  return taskKindStats.value
    .map((kind) => ({
      ...kind,
      items: list.value.filter((item) => taskKind(item) === kind.key)
    }))
    .filter((section) => section.items.length);
});

const visibleNotices = computed(() => {
  if (tab.value !== "task" || taskFilter.value === "all") {
    return list.value;
  }
  return list.value.filter((item) => taskKind(item) === taskFilter.value);
});

// 通知 Tab（系统 / 申请与履约）当前是否有内容可显示，交给 ListState 决定是加载中、失败还是空态
const noticeEmpty = computed(() =>
  tab.value === "system" ? list.value.length === 0 : visibleNotices.value.length === 0
);

const noticeEmptyText = computed(() => {
  if (tab.value === "system") {
    return "暂无系统通知";
  }
  return taskFilter.value === "all" ? "暂无申请或履约消息" : "暂无该类消息";
});

/* ===========================================================================
 * 通知的「视觉类型」（图标 + 语义色 + 左侧 3px 色条）
 * ---------------------------------------------------------------------------
 * ⚠️ 判定只看 type / bizType 两个**后端字段**，顺序不可换（见 design-messages.css B 节）：
 *    先 REPORT（它是 SYSTEM 的子集，放后面会被吞），再 REVIEW，再 APPLICATION，
 *    再「TASK + 变动标题」，再 TASK，最后 SYSTEM 兜底。
 * ⚠️ 这里比原来的视觉分档多了一档 "report"：举报处理结果（type=SYSTEM + bizType=REPORT）
 *    原来没有任何类型标记（taskKind 会把它们兜底归进"履约"），而它们需要独立的
 *    危险色 + 旗帜图标（"你的举报已处理/未予处理"是强结论，不该和履约混在一起看）。
 *    业务分档 taskKind() **一个字都没动** —— 筛选 chip 的数量、归属与排序因此完全不变，
 *    只有 taskFilter==='all' 时的分区标题仍是原来那四档（那是业务分档，不是视觉分档）。
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

/** D 节：通知按日期分桶（今天 / 昨天 / 更早），组内才显示时间 */
const noticeDayGroups = computed(() => {
  const buckets: { key: "today" | "yesterday" | "earlier"; items: NotificationItem[] }[] = [];
  const index = { today: -1, yesterday: -1, earlier: -1 };
  visibleNotices.value.forEach((item) => {
    const key = formatNoticeDay(item.createdAt);
    if (index[key] < 0) {
      index[key] = buckets.length;
      buckets.push({ key, items: [] });
    }
    buckets[index[key]].items.push(item);
  });
  return buckets;
});

function noticeDayLabel(key: "today" | "yesterday" | "earlier") {
  if (key === "today") {
    return t("msgDayToday");
  }
  if (key === "yesterday") {
    return t("msgDayYesterday");
  }
  return t("msgDayEarlier");
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
  return chatPreviews.value.get(item.id)?.text || "";
}

// 当前 Tab 的加载状态：通知与私信是两次独立请求，不能共用一个 loading，
// 否则切换 Tab 时正在飞行的那次请求会把另一个 Tab 的加载挡住，最后显示成空态
const listLoading = computed(() => (tab.value === "chat" ? chatLoading.value : loading.value));

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

interface NoticeTemplate {
  titleKey: I18nKey;
  bodyKey: I18nKey;
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
 * 去掉角色前缀只留名字，但与 actorName 不同：**保留空**（取不到就返回空串，
 * 由传入 noticeTemplate 的 params 覆盖默认「同学」时不会被误填）。
 * 这里刻意不用——统一走 actorName 的兜底口径，见文件头注释。
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
  publisher?: NoticeTemplate;
  applicant?: NoticeTemplate;
  neutral?: NoticeTemplate;
}

function roleParams(item: NotificationItem, extra: Record<string, string> = {}): Record<string, string> {
  const poster = counterpartName(item.content);
  return {
    object: courseOf(item.content) || courseOf(item.title) || t("msgCommonClass" as MsgKey),
    name: poster,
    minutes: minutesOf(item.title),
    time: startAtOf(item.content) || endAtOf(item.content),
    ...extra
  };
}

function pickRoleCopy(copy: RoleCopy, role: NoticeRole): NoticeTemplate | null {
  return copy[role] || copy.neutral || null;
}

function renderRoleCopy(item: NotificationItem, copy: RoleCopy, extra: Record<string, string> = {}): NoticeTemplate | null {
  const template = pickRoleCopy(copy, roleOf(item));
  if (!template) {
    return null;
  }
  return noticeTemplate(template.titleKey, template.bodyKey, roleParams(item, extra));
}

function templateForReport(
  item: NotificationItem,
  object: string,
  fallbackMark: string
): NoticeTemplate {
  const title = String(item.title || "").trim();
  const content = item.content || "";
  const typeLabel = typeLabelOf(content);
  const appellant = actorName((content.match(/^「?([^」]+?)」?\s*就/) || [])[1] || "");
  const base: Record<string, string> = {
    object,
    name: appellant,
    typeLabel: typeLabel || t("msgActorStudent" as MsgKey),
    mark: fallbackMark
  };
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
  const object = objectName(content) || objectName(title) || actorFallback();
  // 举报正文里的「处理结果：」值是后端映射好的一个标签（警告 / 删除内容 / 限制功能 / 封禁账号…）
  const outcome = contentLine(content, "处理结果：");
  // 对方昵称：后端每种通知的写法固定（本轮逐调用点核对），逐个兜住；都取不到就返回兜底人称
  const third = counterpartName(content);

  // ---------- 申请类（type=APPLICATION）----------
  if (type === "APPLICATION") {
    if (matches(title, ["有人申请了你的代课"])) {
      return noticeTemplate("msgAppNewApply" as I18nKey, "msgAppNewApplyBody" as I18nKey, { object, name: third });
    }
    if (matches(title, ["有人撤回了申请"])) {
      return noticeTemplate("msgAppWithdrawn" as I18nKey, "msgAppWithdrawnBody" as I18nKey, { object, name: third });
    }
  }

  // ---------- 评价类（bizType=REVIEW）----------
  if (biz === "REVIEW") {
    if (matches(title, ["代课已完成"])) {
      return noticeTemplate("msgRevCompleted" as I18nKey, "msgRevCompletedBody" as I18nKey, { object, name: third });
    }
    if (matches(title, ["代课已自动完成"])) {
      // 同一 title 发给两方，后端正文分别写「请为代课者 X 打星」/「请为发布者 X 打星」
      const publisherSide = /请为代课者/.test(content);
      return noticeTemplate(
        (publisherSide ? "msgRevAutoCompletedPublisher" : "msgRevAutoCompleted") as I18nKey,
        (publisherSide ? "msgRevAutoCompletedPublisherBody" : "msgRevAutoCompletedBody") as I18nKey,
        { object, name: third }
      );
    }
  }

  // ---------- 履约类（type=TASK）----------
  if (type === "TASK") {
    if (matches(title, ["申请已被接受"])) {
      return noticeTemplate("msgAppAccepted" as I18nKey, "msgAppAcceptedBody" as I18nKey, { object });
    }
    if (matches(title, ["申请未被选中"])) {
      return noticeTemplate("msgAppNotPicked" as I18nKey, "msgAppNotPickedBody" as I18nKey, { object });
    }
    if (matches(title, ["申请已被拒绝"])) {
      return noticeTemplate("msgAppRejectedByPublisher" as I18nKey, "msgAppRejectedByPublisherBody" as I18nKey, { object });
    }
    if (matches(title, ["可以上传现场照片"])) {
      return noticeTemplate("msgFulPhotoDue" as I18nKey, "msgFulPhotoDueBody" as I18nKey, roleParams(item));
    }
    if (matches(title, ["待对方上传照片"])) {
      return noticeTemplate("msgFulPhotoNeeded" as I18nKey, "msgFulPhotoNeededBody" as I18nKey, roleParams(item));
    }
    if (matches(title, ["代课者已上传照片"])) {
      return noticeTemplate("msgFulPhotoUploaded" as I18nKey, "msgFulPhotoUploadedBody" as I18nKey, roleParams(item));
    }
    if (matches(title, ["你已上传照片"])) {
      return noticeTemplate("msgFulSelfPhotoUploaded" as I18nKey, "msgFulSelfPhotoUploadedBody" as I18nKey, roleParams(item));
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
      return noticeTemplate("msgFulCancelledByPublisher" as I18nKey, "msgFulCancelledByPublisherBody" as I18nKey, roleParams(item));
    }
    if (matches(title, ["代课者已取消代课"])) {
      return noticeTemplate("msgFulCancelledByApplicant" as I18nKey, "msgFulCancelledByApplicantBody" as I18nKey, roleParams(item));
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
        roleParams(item)
      );
    }
    if (matches(title, ["代课任务已过期"])) {
      // 发布者侧标题里带「你发布的代课…」，代课者侧是「代课「X」…」（且只在匹配过时才发）
      const publisherSide = /^你发布的/.test(content);
      return noticeTemplate(
        (publisherSide ? "msgFulExpiredUnmatched" : "msgFulExpiredUnmatchedApplicant") as I18nKey,
        (publisherSide ? "msgFulExpiredUnmatchedBody" : "msgFulExpiredUnmatchedApplicantBody") as I18nKey,
        roleParams(item)
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
       */
      const course = courseOf(content) || courseOf(title);
      const minutes = minutesOf(title);
      const time = startAtOf(content);
      if (!course) {
        return noticeTemplate("msgFulStartsInFallback" as I18nKey, "msgFulStartsInFallbackBody" as I18nKey, {
          minutes,
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
        { object: course, minutes, time }
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
        { object, name: named ? actorName(publisher) : actorFallback() }
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
    return templateForReport(item, object, mark);
  }

  // ---------- 账号与安全（type=SYSTEM + bizType=USER）----------
  // 标题沿用后端原话（本来就没有歧义），正文换成定型句说明"这意味着什么/要不要动手"，
  // 后端正文里的设备名 / IP / 时间 / 管理员备注由 extraLines 原样附在后面（一行不丢）。
  if (type === "SYSTEM") {
    if (matches(title, ["账号安全提醒"])) {
      if (content.includes("仅允许一台设备在线")) {
        return noticeTemplate("msgSysSingleDevice" as I18nKey, "msgSysSingleDeviceBody" as I18nKey, {
          object: t("msgCommonClass" as MsgKey)
        });
      }
      return noticeTemplate("msgSysNewDevice" as I18nKey, "msgSysNewDeviceBody" as I18nKey, {
        object: t("msgCommonClass" as MsgKey)
      });
    }
    const accountTitleKey: Record<string, I18nKey> = {
      密码已被重置: "msgSysPasswordReset" as I18nKey,
      账号已解封: "msgSysUnbanned" as I18nKey,
      账号已被封禁: "msgSysBanned" as I18nKey,
      账号权限已调整: "msgSysRestrictions" as I18nKey
    };
    const accountKey = accountTitleKey[title];
    if (accountKey) {
      return noticeTemplate(accountKey, `${String(accountKey)}Body` as I18nKey, {
        object: t("msgCommonClass" as MsgKey)
      });
    }
  }
  return null;
}

/**
 * 列表/弹层共用的标题：定型文案优先，认不出来的一律原样显示后端 title。
 * ⚠️ 后端 title 已经写得很清楚（系统通知类），所以**不做**"把 title 也重写一遍"的无谓加工。
 */
function noticeTitle(item: NotificationItem): string {
  const template = noticeTemplateFor(item);
  return template ? template.title : String(item.title || "");
}

/**
 * 列表/弹层共用的正文行：
 *   lead       —— 定型正文（或降级后的后端原文首行）
 *   highlights —— 后端正文里的多行结论（"处理结果：… / 回复：… / 变更如下：…"）原样保留
 */
function noticeBodyLines(item: NotificationItem): { lead: string; highlights: string[] } {
  const content = String(item.content || "")
    .replace(/\r\n/g, "\n")
    .trim();
  const extras = extraLines(content);
  const template = noticeTemplateFor(item);
  if (!template) {
    const parts = content.split("\n").map((line) => line.trim()).filter(Boolean);
    return { lead: parts[0] || "", highlights: parts.slice(1) };
  }
  // "changed" 类的定型正文之后必须保留"变更如下：…"，不能丢信息
  const lead = template.body || content.split("\n")[0] || "";
  return { lead, highlights: extras };
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

// 列表里的可点暗示：会跳走的通知本来就能点进任务，举报结果在列表里已经完整展示，都不需要「查看详情」
function showsDetailHint(item: NotificationItem) {
  return !canOpenTask(item) && !isHandleNotice(item);
}

function goLogin() {
  uni.navigateTo({ url: "/pages/auth/login" });
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
  if (loading.value) {
    return;
  }
  if (reset) {
    page.value = 1;
    finished.value = false;
  }
  // 本次请求前列表是否为空：为空说明加载失败后没有内容可显示，交给 ListState 展示原因和重试
  const first = list.value.length === 0;
  loading.value = true;
  error.value = "";
  try {
    const data = await listNotifications(page.value, 20, noticeScope.value);
    const rows = data.list || [];
    list.value = reset ? rows : list.value.concat(rows);
    finished.value = list.value.length >= data.total;
  } catch (err) {
    const message = (err as Error).message || "加载失败";
    error.value = message;
    // 已经有内容时只用轻提示：这时若让失败态顶掉列表，比不提示更糟
    if (!first) {
      toast.error(message);
    }
  } finally {
    loading.value = false;
    uni.stopPullDownRefresh();
  }
}

async function loadChats() {
  if (!userStore.isLoggedIn.value) {
    chats.value = [];
    return;
  }
  const first = chats.value.length === 0;
  chatLoading.value = true;
  error.value = "";
  try {
    chats.value = await listChats();
  } catch (err) {
    const message = (err as Error).message || "加载失败";
    error.value = message;
    // 已经有私信列表时只用轻提示，避免失败态把已有内容顶掉
    if (!first) {
      toast.error(message);
    }
  } finally {
    chatLoading.value = false;
    uni.stopPullDownRefresh();
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
  load(true);
}

function switchTaskFilter(next: "all" | TaskKind) {
  taskFilter.value = next;
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
  activeTitle.value = noticeTitle(item);
  activeLines.value = noticeBodyLines(item);
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
        <!-- A 节：三档平级分段控件（结构 / 交互与原 tabs 完全一致；视觉已还原成
             改版前的「浅灰分段条 + 选中白胶囊」，样式在 styles/design-messages.css） -->
        <view class="msg-tabs">
          <view class="msg-tab" :class="{ 'msg-tab--on': tab === 'system' }" @click="switchTab('system')">
            <text>系统</text>
            <view v-if="dots.system > 0" class="msg-tab__dot" />
          </view>
          <view class="msg-tab" :class="{ 'msg-tab--on': tab === 'task' }" @click="switchTab('task')">
            <text>申请与履约</text>
            <view v-if="dots.task > 0" class="msg-tab__dot" />
          </view>
          <view class="msg-tab" :class="{ 'msg-tab--on': tab === 'chat' }" @click="switchTab('chat')">
            <text>私信</text>
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
          <view v-if="(tab === 'system' ? list.length : visibleNotices.length) || (tab === 'task' && taskFilter === 'all' && groupedTaskNotices.length)" class="msg-hintbar">
            <text class="msg-hintbar__text">{{
              tab === "system"
                ? "账号与平台通知"
                : taskFilter === "all"
                  ? "按申请、履约、评价、变动分开"
                  : TASK_KINDS.find((item) => item.key === taskFilter)?.hint || "点击进入对应代课"
            }}</text>
            <text class="msg-hintbar__link" @click="handleReadAll">全部已读</text>
          </view>
          <ListState
            :loading="listLoading"
            :error="error"
            :empty="noticeEmpty"
            :empty-text="noticeEmptyText"
            @retry="load(true)"
          >
            <!-- 骨架屏只在"确实一条都没有、正在首屏加载"时出现；有数据时后台刷新不闪 -->
            <view v-if="listLoading && !list.length" class="msg-list">
              <view v-for="index in 5" :key="index" class="msg-skel">
                <view class="msg-skel__avatar" />
                <view class="msg-skel__main">
                  <view class="msg-skel__line msg-skel__line--name" />
                  <view class="msg-skel__line msg-skel__line--text" />
                </view>
              </view>
            </view>
            <!-- ⚠️ 空态不写在这里：ListState 的三态优先于插槽 —— empty 为真时它直接渲染
                 wd-status-tip，插槽**根本不挂载**，在插槽里写空态是死代码
                 （本轮试了一版 .msg-empty 后确认并删除）。空态的视觉口径不因此放松：
                 design-kit.css 的 `.kean-mine .mine-empty`（44px 圆形图标 + 辅助灰文案）
                 就是规范的**命名基准**，ListState 那个 wd-status-tip 是它的组件版实现；
                 下一轮要自定义空态时套 `.mine-empty`，不要再起第三套。
                 文案仍走原来的 empty-text（暂无系统通知 / 暂无申请或履约消息 / 暂无私信）。 -->
            <template v-if="tab === 'task' && taskFilter === 'all' && groupedTaskNotices.length">
              <view v-for="section in groupedTaskNotices" :key="section.key" class="section">
                <view class="section-head">
                  <text class="section-label">{{ section.label }}</text>
                  <text class="section-hint">{{ section.hint }}</text>
                </view>
                <view
                  v-for="item in section.items"
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
                      <text class="msg-notice__title">{{ noticeTitle(item) }}</text>
                      <text class="msg-notice__time">{{ formatNoticeTime(item.createdAt) }}</text>
                    </view>
                    <view class="msg-notice__body">{{ noticeBodyLines(item).lead }}</view>
                    <view v-if="noticeTodo(item)" class="msg-notice__actions">
                      <text class="msg-chip--todo">{{ t("msgTodo") }}</text>
                    </view>
                    <view class="msg-notice__meta">
                      <text class="msg-notice__type">{{ noticeTypeLabel(item) }}</text>
                    </view>
                  </view>
                </view>
              </view>
              <view class="msg-end">{{ finished ? "没有更多了" : "上拉加载更多" }}</view>
            </template>
            <template v-else>
              <view class="msg-list">
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
                        <text class="msg-notice__title">{{ noticeTitle(item) }}</text>
                        <text class="msg-notice__time">{{ formatNoticeTime(item.createdAt) }}</text>
                      </view>
                      <view class="msg-notice__body" :class="{ 'msg-notice__body--handle': isHandleNotice(item) }">
                        <template v-if="isHandleNotice(item)">
                          <view>{{ noticeBodyLines(item).lead }}</view>
                          <view
                            v-for="(line, index) in noticeBodyLines(item).highlights"
                            :key="index"
                            class="msg-notice__result"
                          >{{ line }}</view>
                        </template>
                        <template v-else>{{ noticeBodyLines(item).lead }}</template>
                      </view>
                      <view v-if="noticeTodo(item)" class="msg-notice__actions">
                        <text class="msg-chip--todo">{{ t("msgTodo") }}</text>
                      </view>
                      <view class="msg-notice__meta">
                        <text class="msg-notice__type">{{ noticeTypeLabel(item) }}</text>
                        <text v-if="showsDetailHint(item)" class="msg-notice__more">查看详情 ›</text>
                      </view>
                    </view>
                  </view>
                </template>
              </view>
              <view class="msg-end">{{ finished ? "没有更多了" : "上拉加载更多" }}</view>
            </template>
          </ListState>
        </template>

        <template v-else>
          <view class="msg-hintbar">
            <text class="msg-hintbar__text">可在任务详情或同学主页发起私信</text>
          </view>
          <ListState
            :loading="listLoading"
            :error="error"
            :empty="chats.length === 0"
            empty-text="暂无私信"
            @retry="load(true)"
          >
            <!-- ⚠️ 空态不写在这里：ListState 的三态优先于插槽 —— empty 为真时它直接渲染
                 wd-status-tip，插槽**根本不挂载**，所以在插槽里写空态是死代码
                 （本轮试了一版 .msg-empty 后确认并删除）。空态仍由 ListState 的
                 wd-status-tip + 原来的 empty-text（暂无私信）负责，口径与改版前一致。 -->
            <view v-if="listLoading && !chats.length" class="msg-list">
              <view v-for="index in 5" :key="index" class="msg-skel">
                <view class="msg-skel__avatar" />
                <view class="msg-skel__main">
                  <view class="msg-skel__line msg-skel__line--name" />
                  <view class="msg-skel__line msg-skel__line--text" />
                </view>
              </view>
            </view>
            <view v-else class="msg-list">
              <view v-for="item in chats" :key="item.id" class="msg-chat" :class="{ 'msg-chat--unread': item.unreadCount > 0 }" @click="openChat(item)">
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
                      <text class="msg-chat__preview-text">{{ previewTextOf(item) }}</text>
                    </view>
                    <view v-if="item.unreadCount > 0" class="msg-chat__badge">{{ item.unreadCount > 99 ? "99+" : item.unreadCount }}</view>
                  </view>
                </view>
              </view>
            </view>
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
  overflow-x: hidden;
  max-width: 100%;
}

/* ---------- 「申请与履约 → 全部」档的业务分区标题（原有结构，原样保留） ---------- */
.section {
  margin-top: 8px;
}

.section-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  padding: 12px 16px 8px;
  min-width: 0;
}

.section-label {
  flex-shrink: 0;
  font-size: 13px;
  font-weight: 700;
}

.section-hint {
  min-width: 0;
  font-size: 11px;
  text-align: right;
}
</style>
