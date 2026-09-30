export function goReport(targetType: string, targetId?: number | null, targetLabel?: string | null) {
  if (!targetId) {
    return false;
  }
  let url = `/pages/mine/report?targetType=${encodeURIComponent(targetType)}&targetId=${targetId}`;
  if (targetLabel) {
    url += `&targetLabel=${encodeURIComponent(targetLabel)}`;
  }
  uni.navigateTo({ url });
  return true;
}
