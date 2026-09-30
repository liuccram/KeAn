<script setup lang="ts">
import { fetchMe } from "@/api/auth";
import { listChatPeers, openChat, type ChatPeerItem } from "@/api/chat";
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
const chatBlock = computed(() => actionBlockReason(userStore.state.user, "chat"));

async function load() {
  loading.value = true;
  try {
    list.value = await listChatPeers(keyword.value.trim() || undefined);
  } catch (error) {
    toast.error((error as Error).message || "加载失败");
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
    toast.error((error as Error).message || "发起私聊失败");
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
    <view v-if="list.length" class="list">
      <view v-for="item in list" :key="item.id" class="row" @click="handleSelect(item)">
        <image v-if="item.avatarUrl" class="avatar img" :src="resolveMediaUrl(item.avatarUrl)" mode="aspectFill" />
        <view v-else class="avatar">{{ (item.nickname || "同").slice(0, 1) }}</view>
        <view class="info">
          <view class="name">{{ item.nickname }}</view>
          <view class="campus">{{ item.campusName || "本校同学" }}</view>
        </view>
        <text class="go">私聊</text>
      </view>
    </view>
    <wd-status-tip v-else-if="!loading" image="content" tip="暂无可聊同学" />
    <wd-toast />
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: #f5f6f8;
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
  background: #fff;
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
  background: #dbe7ff;
  color: #4d80f0;
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
  color: #1d2129;
}
.campus {
  margin-top: 4px;
  color: #86909c;
  font-size: 12px;
}
.go {
  color: #4d80f0;
  font-size: 13px;
}
</style>
