/** QQ 号 5–11 位数字，不以 0 开头。可只填号，也可带 @qq.com。 */
const QQ_NO = /^[1-9]\d{4,10}$/;
const QQ_MAIL = /^[1-9]\d{4,10}@qq\.com$/i;

export function normalizeQqEmail(raw: string): string | null {
  const value = (raw || "").trim().toLowerCase().replace(/\s+/g, "");
  if (!value) {
    return null;
  }
  if (QQ_NO.test(value)) {
    return `${value}@qq.com`;
  }
  if (QQ_MAIL.test(value)) {
    return value;
  }
  return null;
}

export function isQqEmail(raw: string): boolean {
  return normalizeQqEmail(raw) !== null;
}
