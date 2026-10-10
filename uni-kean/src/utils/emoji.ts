/* ===========================================================================
 * 内置表情（emoji pack）—— 纯客户端实现，后端零改动
 * ---------------------------------------------------------------------------
 * 素材与映射表的来源：box-im（https://github.com/bluexsx/box-im），MIT License，
 * Copyright (c) 2022 blue。图片放在 src/static/emoji/0.png ~ 72.png，
 * 版权声明全文见 src/static/emoji/LICENSE.txt。
 *
 * ⚠️⚠️ 下面 EMOJI_NAME_LIST 是**索引即文件名**的一一对应表：
 *       EMOJI_NAME_LIST[i] ↔ static/emoji/<i>.png
 *   所以：**一项都不能少、不能多、顺序绝对不能改**（顺序错 = 全部表情错位）。
 *   来源：box 的 src/utils/emotion.web.ts 里的 emoTextList，逐字照抄。
 *   改这个数组前先读 src/static/emoji/LICENSE.txt 与 README（同目录）。
 *
 * 短代码格式（照抄 box 的权威实现，见 emotion.web.ts 的 formatEmoji）：
 *   存进消息里的就是 `[名字]` —— 例如 `[微笑]`、`[六六六]`、`[OK]`。
 *   既不是 `:smile:`，也不是直接把图片塞进消息体；消息体始终是**纯文本**，
 *   图片只在渲染时替换出来（与 box 的 $emo.transform 一致）。
 * =========================================================================== */

/**
 * 表情名列表（73 项）。
 *
 * box 的 `emoTextList` 原文共 74 项，而随包素材只有 `0.png`~`72.png`（73 个）——
 * 第 74 项（下标 73，`月亮`）要对应 `73.png`，但该文件在 box 的素材目录里不存在，
 * 因此它是一条**没有图片的死条目**（box 的 `textToUrl` 对它返回空串）。
 *
 * 本项目按「数组与素材必须严格一一对应」处理：只收 0~72 这 73 项，
 * 与 static/emoji/0.png ~ 72.png 数量、下标完全一致（详见 README 的校验一节）。
 */
export const EMOJI_NAME_LIST: string[] = [
  "六六六",
  "微笑",
  "抱心",
  "捂脸",
  "点赞",
  "笑哭",
  "读书",
  "听歌",
  "期待",
  "飞吻",
  "吃瓜",
  "可怜",
  "惊讶",
  "生气",
  "困",
  "思考",
  "拜托",
  "怒火",
  "笑指",
  "疑惑",
  "书呆",
  "晕",
  "星眼",
  "伤心",
  "无语",
  "好的",
  "比耶",
  "呲牙",
  "奶茶",
  "放大",
  "大哭",
  "庆祝",
  "花痴",
  "闭嘴",
  "吐舌",
  "鼻涕",
  "爆头",
  "喊话",
  "吐彩",
  "嘘",
  "酷笑",
  "斜眼",
  "憨笑",
  "担心",
  "心动",
  "害羞",
  "祈祷",
  "土豪",
  "发财",
  "叹气",
  "紧张",
  "口罩",
  "鼓掌",
  "挥手",
  "耶",
  "比心",
  "OK",
  "指上",
  "指右",
  "摇滚",
  "合十",
  "碰拳",
  "握拳",
  "赞",
  "倒赞",
  "六",
  "大便",
  "蛋糕",
  "红包",
  "礼花",
  "咖啡",
  "西瓜",
  "月亮"
];

/** 静态素材目录（小程序端静态文件必须用「目录相对」写法，不能用别名） */
const EMOJI_DIR = "static/emoji";

/** 表情名 → 下标（0 基，即文件名） */
const NAME_TO_INDEX = new Map<string, number>();
EMOJI_NAME_LIST.forEach((name, index) => {
  if (!NAME_TO_INDEX.has(name)) {
    NAME_TO_INDEX.set(name, index);
  }
});

/** 短代码 — 与 box 的 emojiRegex 同形：`[名字]`，名字是 1~4 个汉字，或恰好 `OK` */
export const EMOJI_REGEX = /\[(OK|[\u4E00-\u9FA5]{1,4})\]/gi;

/**
 * 预编译的「全局匹配」正则副本在带 g 的 exec 循环里会记忆 lastIndex，
 * 一旦中途 break 就会污染下一次调用 —— 所以走 `String.replace`/`split`
 * 这两个不会留下状态的 API，绝不把模块级正则直接拿去做 exec 循环。
 */

/** 把表情名变成短代码：`微笑` → `[微笑]`（与 box 的 formatEmoji 完全一致） */
export function formatEmoji(word: string): string {
  return `[${word}]`;
}

/**
 * 取短代码里的名字：`[微笑]` → `微笑`；不是短代码时原样返回。
 * 与 box 的 parseEmojiWord 口径一致（`OK` 大小写不敏感地归一成 `OK`）。
 */
export function parseEmojiWord(text: string): string {
  const match = String(text).match(/^\[(OK|[\u4E00-\u9FA5]{1,4})\]$/i);
  if (!match) {
    return text;
  }
  const word = match[1];
  return word.toUpperCase() === "OK" ? "OK" : word;
}

/** 表情名 → 下标；不是已知表情返回 -1（未知短代码一律原样显示，绝不误替换） */
export function emojiIndexOf(word: string): number {
  const index = NAME_TO_INDEX.get(word);
  return index === undefined ? -1 : index;
}

/** 表情名或短代码 → 图片路径；未知表情 / 素材缺失返回空串 */
export function emojiPathOf(wordOrCode: string): string {
  const word = parseEmojiWord(wordOrCode);
  const index = emojiIndexOf(word);
  if (index < 0) {
    return "";
  }
  return `/${EMOJI_DIR}/${index}.png`;
}

/** 表情名/短代码 → 可直接喂给 `<image>` 的 src（未知表情返回空串） */
export function emojiUrl(wordOrCode: string): string {
  return emojiPathOf(wordOrCode);
}

/**
 * 文本里是否含「已知」短代码（未知的方括号内容不算）。
 * ⚠️ EMOJI_REGEX 带 g，`test` 会推进 lastIndex —— 这里必须显式归零，
 * 否则同一个字符串第二次调用会返回 false（正则状态泄漏）。
 */
export function hasEmoji(content: string): boolean {
  EMOJI_REGEX.lastIndex = 0;
  return EMOJI_REGEX.test(String(content || ""));
}

/** 渲染片段：`emoji` 渲染成图，`text` 原样显示 */
export type EmojiSegment = {
  type: "text" | "emoji";
  /** 文字内容（text 段用） */
  text?: string;
  /** 表情名（emoji 段用） */
  name?: string;
  /** 图片路径（emoji 段用；类型上可选，避免模板里 v-if 收窄不到时报错） */
  path?: string;
  /** 存进消息的短代码，如 `[微笑]`（emoji 段用） */
  code?: string;
};

/**
 * 把一段文本切成「文字 / 表情图」片段，供模板 v-for 渲染出**内联小图**。
 *
 * - 只认**已知**短代码：`[微笑]` → 图；`[不存在]`、`[abc]`、`[1]` 一律原样当文字；
 * - 一条消息里多个表情、表情与文字混排都能处理；
 * - 正则与查表都是模块级预编译的，函数本身只做一次 split 遍历，可在渲染里反复调用。
 */
export function splitEmoji(content: string): EmojiSegment[] {
  const raw = String(content == null ? "" : content);
  if (!raw) {
    return [];
  }
  const parts = raw.split(EMOJI_REGEX);
  const segments: EmojiSegment[] = [];
  parts.forEach((part, index) => {
    if (part === undefined || part === "") {
      return;
    }
    // split 用了捕获组：奇数下标是捕获到的名字，偶数下标是捕获组之间的普通文字
    if (index % 2 === 1) {
      const word = part.toUpperCase() === "OK" ? "OK" : part;
      const path = emojiPathOf(word);
      if (path) {
        segments.push({ type: "emoji", name: word, path, code: formatEmoji(word) });
        return;
      }
    }
    segments.push({ type: "text", text: part });
  });
  return segments;
}

/** 纯文本摘要：把已知短代码换成 `[表情]`，供不渲染图片的场景（如预览降级）使用 */
export function emojiToPlainText(content: string, placeholder = "[表情]"): string {
  return splitEmoji(content)
    .map((segment) => (segment.type === "emoji" ? placeholder : segment.text))
    .join("");
}

/* ---------------------------------------------------------------------------
 * 最近使用（box 用 sqlite 的 recent_emojis 表，这里用 localStorage 简化）
 * ------------------------------------------------------------------------- */

const RECENT_KEY = "kean.emoji.recent";
const RECENT_MAX = 14;

/** 读取最近使用列表（只保留仍然存在的表情，顺序为「最近用的在前」） */
export function listRecentEmoji(): string[] {
  try {
    const raw = uni.getStorageSync(RECENT_KEY);
    const list: unknown = typeof raw === "string" && raw ? JSON.parse(raw) : raw;
    if (!Array.isArray(list)) {
      return [];
    }
    return list
      .map((item) => String(item || ""))
      .filter((item) => emojiIndexOf(item) >= 0)
      .slice(0, RECENT_MAX);
  } catch {
    return [];
  }
}

/** 记一次使用：置顶 + 去重 + 截断；存储异常时静默失败（不因为记历史影响发消息） */
export function rememberRecentEmoji(word: string): string[] {
  const name = parseEmojiWord(word);
  if (emojiIndexOf(name) < 0) {
    return listRecentEmoji();
  }
  const next = [name, ...listRecentEmoji().filter((item) => item !== name)].slice(0, RECENT_MAX);
  try {
    uni.setStorageSync(RECENT_KEY, JSON.stringify(next));
  } catch {
    // 忽略：最近使用只是便利功能
  }
  return next;
}
