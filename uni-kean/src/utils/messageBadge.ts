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
    // 请求失败时保留上一次的角标。此前这里会主动 removeTabBarBadge，
    // 网络抖动一次角标就消失，用户会以为没有新消息（假阴性），比角标稍微滞后更糟。
    // 只有请求成功且未读数为 0 时才清除角标。
  }
}
