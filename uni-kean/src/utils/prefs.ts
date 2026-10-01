const PREFS_KEY = "kean_display_prefs";

export type ThemeMode = "light" | "dark" | "system";
export type FontSize = "s" | "m" | "l";
export type Lang = "zh" | "en";
export type WallpaperId = "default" | "meteor-cyan" | "meteor-violet";

export interface DisplayPrefs {
  theme: ThemeMode;
  fontSize: FontSize;
  lang: Lang;
  wallpaper: WallpaperId;
}

const DEFAULT_PREFS: DisplayPrefs = {
  theme: "light",
  fontSize: "m",
  lang: "zh",
  wallpaper: "default"
};

function parseWallpaper(value: unknown): WallpaperId {
  if (value === "meteor-cyan" || value === "meteor-violet") {
    return value;
  }
  return "default";
}

export function loadDisplayPrefs(): DisplayPrefs {
  try {
    const raw = uni.getStorageSync(PREFS_KEY) as Partial<DisplayPrefs> | "";
    if (!raw || typeof raw !== "object") {
      return { ...DEFAULT_PREFS };
    }
    return {
      // 深色已整体关闭：历史存过 dark / system 的用户一律读成浅色（自动回到浅色）。
      // 恢复深色时改回 `raw.theme === "dark" || raw.theme === "system" ? raw.theme : "light"`。
      theme: "light",
      fontSize: raw.fontSize === "s" || raw.fontSize === "l" ? raw.fontSize : "m",
      lang: raw.lang === "en" ? "en" : "zh",
      wallpaper: parseWallpaper(raw.wallpaper)
    };
  } catch {
    return { ...DEFAULT_PREFS };
  }
}

export function saveDisplayPrefs(prefs: DisplayPrefs) {
  // 深色已整体关闭：这里强制写 light，保证存储里不可能再出现 dark，
  // 也让历史 dark 值在下一次任意偏好变更时被就地抹掉。
  // 理由：选"写成 light"而不是"忽略 theme 字段"，是为了让存储结构保持完整、
  // 恢复功能时无需额外迁移；字号 / 语言 / 壁纸原样透传，不做任何改动。
  uni.setStorageSync(PREFS_KEY, { ...prefs, theme: "light" as ThemeMode });
}

/**
 * 深色模式暂整体关闭：代码保留以便将来恢复，因此函数签名（ThemeMode 入参）不变，
 * 但恒返回 "light"，调用方拿到的永远是浅色。
 * 浅色取值来自 styles/theme-vars.css（--kean-* 变量），深色覆盖规则仍留在
 * styles/display-appearance.css / styles/theme-vars.css 里，恢复时只需还原本函数。
 */
export function resolveTheme(_mode: ThemeMode): "light" | "dark" {
  return "light";
}

export function themeLabel(theme: ThemeMode, lang: Lang = "zh") {
  if (lang === "en") {
    return theme === "dark" ? "Dark" : theme === "system" ? "System" : "Light";
  }
  return theme === "dark" ? "深色" : theme === "system" ? "跟随系统" : "浅色";
}

export function fontLabel(size: FontSize, lang: Lang = "zh") {
  if (lang === "en") {
    return size === "s" ? "Small" : size === "l" ? "Large" : "Standard";
  }
  return size === "s" ? "较小" : size === "l" ? "较大" : "标准";
}

export function langLabel(lang: Lang) {
  return lang === "en" ? "English" : "简体中文";
}

export function wallpaperLabel(id: WallpaperId, lang: Lang = "zh") {
  if (lang === "en") {
    if (id === "meteor-cyan") {
      return "Cyan meteors";
    }
    if (id === "meteor-violet") {
      return "Violet meteors";
    }
    return "Default";
  }
  if (id === "meteor-cyan") {
    return "青白流星";
  }
  if (id === "meteor-violet") {
    return "紫金流星";
  }
  return "默认";
}

export function isMeteorWallpaper(id: WallpaperId) {
  return id === "meteor-cyan" || id === "meteor-violet";
}

export function applyWallpaperChrome(id?: WallpaperId) {
  applyDisplayAppearance(id);
}

type UniApiName = "setNavigationBarColor" | "setBackgroundColor" | "setTabBarStyle" | "setTabBarItem";

function callUniApi(name: UniApiName, options: Record<string, unknown>) {
  const api = (uni as unknown as Record<string, unknown>)[name];
  if (typeof api !== "function") {
    return;
  }
  try {
    (api as (opts: Record<string, unknown>) => void)({
      ...options,
      fail: () => undefined
    });
  } catch {
    // H5 等端部分 API 不存在或会同步抛错
  }
}

function applyTabBarSkin(meteor: boolean) {
  const color = meteor ? "#ffffff" : "#7A7E83";
  const selectedColor = meteor ? "#ffffff" : "#4D80F0";
  const backgroundColor = meteor ? "#1c1c1e" : "#FFFFFF";
  callUniApi("setTabBarStyle", {
    color,
    selectedColor,
    backgroundColor,
    borderStyle: "black"
  });
  paintH5TabBar(meteor, backgroundColor);
}

function paintH5TabBar(meteor: boolean, backgroundColor: string) {
  if (typeof document === "undefined") {
    return;
  }
  const styleId = "kean-tabbar-skin";
  let tag = document.getElementById(styleId) as HTMLStyleElement | null;
  if (!tag) {
    tag = document.createElement("style");
    tag.id = styleId;
    document.head.appendChild(tag);
  }
  tag.textContent = meteor
    ? `
html, body {
  background-color: #f5f6f8 !important;
}
.uni-tabbar,
.uni-tabbar-bottom {
  background-color: ${backgroundColor} !important;
  background: ${backgroundColor} !important;
}
.uni-tabbar__text,
.uni-tabbar__label,
.uni-tabbar-item-text {
  color: #ffffff !important;
}
.uni-tabbar-border {
  background-color: rgba(255, 255, 255, 0.12) !important;
}
.uni-placeholder {
  background: transparent !important;
}
`
    : `
html, body {
  background-color: #f5f6f8 !important;
}
.uni-tabbar,
.uni-tabbar-bottom {
  background-color: #ffffff !important;
  background: #ffffff !important;
}
.uni-tabbar-border {
  background-color: rgba(0, 0, 0, 0.1) !important;
}
.uni-placeholder {
  background: transparent !important;
}
`;

  const cfg = (window as unknown as { __uniConfig?: { tabBar?: Record<string, string> } }).__uniConfig?.tabBar;
  if (cfg) {
    cfg.backgroundColor = backgroundColor;
    cfg.color = meteor ? "#ffffff" : "#7A7E83";
    cfg.selectedColor = meteor ? "#ffffff" : "#4D80F0";
    cfg.borderStyle = "black";
  }

  document.querySelectorAll<HTMLElement>(".uni-tabbar, .uni-tabbar-bottom").forEach((el) => {
    if (el.classList.contains("uni-placeholder")) {
      return;
    }
    el.style.removeProperty("background");
    el.style.setProperty("background-color", backgroundColor, "important");
  });
  document.querySelectorAll<HTMLElement>(".uni-tabbar__text, .uni-tabbar__label, .uni-tabbar-item-text").forEach((el) => {
    if (meteor) {
      el.style.setProperty("color", "#ffffff", "important");
    } else {
      el.style.removeProperty("color");
    }
  });
}

export function applyDisplayAppearance(wallpaper?: WallpaperId) {
  const prefs = loadDisplayPrefs();
  const id = wallpaper ?? prefs.wallpaper;
  const meteor = isMeteorWallpaper(id);
  // 深色模式已整体关闭：resolveTheme 恒返回 "light"，所以 dark 恒为 false，
  // "kean-dark" 这个 class 永远只会被【移除】、不会再被加上（全项目仅此一处设置它）。
  // 保留 toggle 写法是为了将来恢复时只改 resolveTheme 一处即可生效。
  const dark = resolveTheme(prefs.theme) === "dark";
  const fontSize = prefs.fontSize === "s" ? "14px" : prefs.fontSize === "l" ? "18px" : "16px";

  if (typeof document !== "undefined") {
    const root = document.documentElement;
    root.style.setProperty("--kean-fs", fontSize);
    root.classList.toggle("kean-dark", dark && !meteor);
    root.classList.toggle("kean-meteor", meteor);
    root.style.removeProperty("--kean-wallpaper-image");
    root.style.removeProperty("--kean-page-bg");
    root.classList.toggle("kean-font-s", prefs.fontSize === "s");
    root.classList.toggle("kean-font-l", prefs.fontSize === "l");
    root.setAttribute("lang", prefs.lang === "en" ? "en" : "zh-CN");
    document.body?.style.setProperty("background-color", "#f5f6f8", "important");
  }

  const route = currentRoute();
  const skipChrome = /mine\/crop|auth\/login|auth\/register|auth\/forgot/.test(route);
  if (!skipChrome) {
    if (meteor) {
      const mainTabPage = /^(pages\/home\/index|pages\/publish\/index|pages\/message\/index)$/.test(route);
      if (mainTabPage) {
        callUniApi("setNavigationBarColor", {
          frontColor: "#ffffff",
          backgroundColor: "#1c1c1e"
        });
        callUniApi("setBackgroundColor", {
          backgroundColor: "#F5F6F8",
          backgroundColorTop: "#1c1c1e",
          backgroundColorBottom: "#F5F6F8"
        });
      } else {
        callUniApi("setNavigationBarColor", {
          frontColor: "#000000",
          backgroundColor: "#ffffff"
        });
        callUniApi("setBackgroundColor", {
          backgroundColor: "#F5F6F8",
          backgroundColorTop: "#ffffff",
          backgroundColorBottom: "#F5F6F8"
        });
      }
    } else {
      callUniApi("setNavigationBarColor", {
        frontColor: "#000000",
        backgroundColor: "#ffffff"
      });
      callUniApi("setBackgroundColor", {
        backgroundColor: "#F5F6F8",
        backgroundColorTop: "#ffffff",
        backgroundColorBottom: "#F5F6F8"
      });
    }
  }

  applyTabBarSkin(meteor);
  setTimeout(() => applyTabBarSkin(meteor), 50);

  const tabs = prefs.lang === "en" ? ["Home", "Post", "Inbox", "Me"] : ["首页", "发布", "消息", "我的"];
  tabs.forEach((text, index) => {
    callUniApi("setTabBarItem", { index, text });
  });
}

function currentRoute() {
  try {
    const pages = getCurrentPages();
    const last = pages[pages.length - 1] as { route?: string } | undefined;
    return last?.route || "";
  } catch {
    return "";
  }
}
