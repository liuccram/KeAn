/** 手机 App 访问主机后端的地址。电脑 WLAN IP 变了就改这一行，然后重新真机运行。 */
const DEFAULT_APP_BASE = ""; // 故意为空：生产必须由 VITE_API_BASE_URL 提供，避免打包后静默指向内网 IP

export function resolveApiBase(): string {
  const fromEnv = String(import.meta.env.VITE_API_BASE_URL || "").trim().replace(/\/$/, "");
  // #ifdef APP-PLUS
  return fromEnv || DEFAULT_APP_BASE;
  // #endif
  // #ifdef H5
  if (typeof window !== "undefined" && /^(localhost|127\.0\.0\.1)$/i.test(window.location.hostname)) {
    return "";
  }
  return fromEnv || DEFAULT_APP_BASE;
  // #endif
  return fromEnv || DEFAULT_APP_BASE;
}
