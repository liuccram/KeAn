import { loadDisplayPrefs, type Lang } from "@/utils/prefs";

const DICT = {
  zh: {
    theme: "深浅色模式",
    font: "字体大小",
    lang: "语言",
    wallpaper: "页面壁纸",
    cover: "我的页背景",
    displayTitle: "界面与显示",
    allCampuses: "全部校区",
    allStatus: "全部",
    openStatus: "可申请",
    mineStatus: "我的",
    classDate: "上课日期",
    datePlaceholder: "不限日期",
    todayOnly: "今日",
    allDates: "全部日期",
    allTimes: "全部时间",
    accountSecurity: "账号与安全",
    changePassword: "修改密码",
    loginDevices: "管理登录设备",
    thisDevice: "本机",
    kick: "下线",
    noDevices: "暂无登录设备",
    defaultCover: "默认",
    customCover: "自定义"
  },
  en: {
    theme: "Appearance",
    font: "Font size",
    lang: "Language",
    wallpaper: "Wallpaper",
    cover: "Profile cover",
    displayTitle: "Display",
    allCampuses: "All campuses",
    allStatus: "All",
    openStatus: "Open",
    mineStatus: "Mine",
    classDate: "Class date",
    datePlaceholder: "Any date",
    todayOnly: "Today",
    allDates: "All dates",
    allTimes: "Any time",
    accountSecurity: "Account & security",
    changePassword: "Change password",
    loginDevices: "Login devices",
    thisDevice: "This device",
    kick: "Sign out",
    noDevices: "No devices",
    defaultCover: "Default",
    customCover: "Custom"
  }
} as const;

export function t(key: keyof typeof DICT.zh, lang?: Lang) {
  const current = lang ?? loadDisplayPrefs().lang;
  return DICT[current][key];
}
