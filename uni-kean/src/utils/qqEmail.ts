/**
 * 邮箱规则：只接受**完整 QQ 邮箱**（如 12345678@qq.com）。
 * 纯 QQ 号（12345678）、缺少 @、非 qq.com 域名一律不通过。
 *
 * 前后端同一套规则：这里拦住用户输入，服务端 kean/utils/QqEmails.java
 * （REQUIRED_PATTERN / normalize）兜底 —— 只改前端会被直接打接口绕过。
 */
const QQ_MAIL = /^[1-9]\d{4,10}@qq\.com$/i;

/** 邮箱不合规时的统一提示（与服务端 QqEmails.INVALID_MESSAGE 一致）。 */
export const QQ_EMAIL_HINT = "请填写完整 QQ 邮箱，例如 12345678@qq.com";

/** 完整 QQ 邮箱校验；通过返回规范化（去空白、转小写）后的邮箱，否则返回 null。 */
export function normalizeQqEmail(raw: string): string | null {
  const value = (raw || "").trim().toLowerCase().replace(/\s+/g, "");
  return QQ_MAIL.test(value) ? value : null;
}

export function isQqEmail(raw: string): boolean {
  return normalizeQqEmail(raw) !== null;
}
