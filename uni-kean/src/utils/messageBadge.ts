import { unreadChatCount } from "@/api/chat";
import { unreadNotificationCount } from "@/api/notification";
import { getToken } from "@/utils/storage";

const MESSAGE_TAB_INDEX = 2;

export async function refreshMessageBadge() {
  if (!getToken()) {
    uni.removeTabBarBadge({ index: MESSAGE_TAB_INDEX });
    return;
  }
  try {
    const [noticeCount, chatCount] = await Promise.all([unreadNotificationCount(), unreadChatCount()]);
    const count = Number(noticeCount || 0) + Number(chatCount || 0);
    if (count > 0) {
      uni.setTabBarBadge({
        index: MESSAGE_TAB_INDEX,
        text: count > 99 ? "99+" : String(count)
      });
    } else {
      uni.removeTabBarBadge({ index: MESSAGE_TAB_INDEX });
    }
  } catch {
    uni.removeTabBarBadge({ index: MESSAGE_TAB_INDEX });
  }
}
