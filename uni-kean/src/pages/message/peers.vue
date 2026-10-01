<script setup lang="ts">
import { fetchMe } from "@/api/auth";
import { listChatPeers, openChat, type ChatPeerItem } from "@/api/chat";
import FallbackImage from "@/components/FallbackImage.vue";
import ListState from "@/components/ListState.vue";
import { useUserStore } from "@/store/user";
import { actionBlockReason } from "@/utils/format";
import { resolveMediaUrl } from "@/utils/request";
import { onShow } from "@dcloudio/uni-app";
import { useToast } from "wot-design-uni";
import { computed, ref } from "vue";

const toast = useToast();
const userStore = useUserStore();
const keyword = ref("");
const list = ref<ChatPeerItem[]>([]);
const loading = ref(false);
const error = ref("");
const chatBlock = computed(() => actionBlockReason(userStore.state.user, "chat"));
// 有搜索词时的空结果和「本校没有可聊的人」不是一回事，文案要分开
const emptyText = computed(() => (keyword.value.trim() ? "没有找到相关同学" : "暂无可聊同学"));

async function load() {
  const first = list.value.length === 0;
  loading.value = true;
  error.value = "";
  try {
    list.value = await listChatPeers(keyword.value.trim() || undefined);
  } catch (err) {
    const message = (err as Error).message || "加载失败";
    error.value = message;
    // 已经有内容时只用轻提示，避免失败态把上一次的搜索结果顶掉
    if (!first) {
      toast.error(message);
    }
  } finally {
    loading.value = false;
  }
}

async function handleSelect(item: ChatPeerItem) {
  if (chatBlock.value) {
    toast.error(chatBlock.value);
    return;
  }
  try {
    const session = await openChat(item.id);
    uni.redirectTo({ url: `/pages/message/chat?id=${session.id}` });
  } catch (error) {
    toast.error((error as Error).message || "发起私信失败");
  }
}

onShow(async () => {
  if (userStore.isLoggedIn.value) {
    try {
      const latest = await fetchMe();
      if (userStore.state.token) {
        userStore.setLogin(userStore.state.token, latest);
      }
    } catch {
      // 使用本地缓存
    }
  }
  load();
});
</script>

<template>
  <view class="page">
    <view v-if="chatBlock" class="mute-tip">{{ chatBlock }}</view>
    <view class="search">
      <wd-search v-model="keyword" placeholder="搜索本校同学昵称" hide-cancel @search="load" @clear="load" />
    </view>
    <ListState
      :loading="loading"
      :error="error"
      :empty="list.length === 0"
      :empty-text="emptyText"
      @retry="load"
    >
      <view class="list">
        <view v-for="item in list" :key="item.id" class="row" @click="handleSelect(item)">
          <FallbackImage v-if="item.avatarUrl" class="avatar img" :src="resolveMediaUrl(item.avatarUrl)" mode="aspectFill" />
          <view v-else class="avatar">{{ (item.nickname || "同").slice(0, 1) }}</view>
          <view class="info">
            <view class="name">{{ item.nickname }}</view>
            <view class="campus">{{ item.campusName || "本校同学" }}</view>
          </view>
          <text class="go">私信</text>
        </view>
      </view>
    </ListState>
    <wd-toast />
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: var(--kean-bg);
}
.search {
  padding: 12px 12px 0;
}
.mute-tip {
  margin: 12px 16px 0;
  padding: 10px 12px;
  background: #fff7e8;
  color: #d25f00;
  font-size: 13px;
  border-radius: 8px;
}
.list {
  padding: 12px 16px;
}
.row {
  background: var(--kean-card);
  border-radius: 12px;
  padding: 12px 16px;
  margin-bottom: 10px;
  display: flex;
  align-items: center;
  gap: 12px;
}
.avatar {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  background: var(--kean-primary-soft);
  color: var(--kean-primary);
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 600;
  overflow: hidden;
}
.avatar.img {
  display: block;
  object-fit: cover;
}
.info {
  flex: 1;
}
.name {
  font-weight: 600;
  color: var(--kean-text);
}
.campus {
  margin-top: 4px;
  color: var(--kean-muted);
  font-size: 12px;
}
.go {
  color: var(--kean-primary);
  font-size: 13px;
}
</style>
