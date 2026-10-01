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
      theme: raw.theme === "dark" || raw.theme === "system" ? raw.theme : "light",
      fontSize: raw.fontSize === "s" || raw.fontSize === "l" ? raw.fontSize : "m",
      lang: raw.lang === "en" ? "en" : "zh",
      wallpaper: parseWallpaper(raw.wallpaper)
    };
  } catch {
    return { ...DEFAULT_PREFS };
  }
}

export function saveDisplayPrefs(prefs: DisplayPrefs) {
  uni.setStorageSync(PREFS_KEY, prefs);
}

export function resolveTheme(mode: ThemeMode): "light" | "dark" {
  if (mode === "light" || mode === "dark") {
    return mode;
  }
  try {
    const info = uni.getSystemInfoSync() as { theme?: string; osTheme?: string };
    const system = (info.theme || info.osTheme || "").toLowerCase();
    return system === "dark" ? "dark" : "light";
  } catch {
    return "light";
  }
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
  // 深浅色此前被硬编码为 false：设置页能存、display-appearance.css 里 html.kean-dark
  // 的规则也写全了，但这个 class 永远不会被加上，于是"深色模式"点了没有任何反应。
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
