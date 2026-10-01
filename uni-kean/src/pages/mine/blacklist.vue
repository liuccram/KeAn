<script setup lang="ts">
import { listBlacklist, unblockUser, type BlacklistItem } from "@/api/blacklist";
import FallbackImage from "@/components/FallbackImage.vue";
import ListState from "@/components/ListState.vue";
import { resolveMediaUrl } from "@/utils/request";
import { onShow } from "@dcloudio/uni-app";
import { useToast } from "wot-design-uni";
import { ref } from "vue";

const toast = useToast();
const loading = ref(false);
const error = ref("");
const list = ref<BlacklistItem[]>([]);

async function load() {
  const first = list.value.length === 0;
  if (first) {
    loading.value = true;
  }
  error.value = "";
  try {
    list.value = await listBlacklist();
  } catch (err) {
    const message = (err as Error).message || "加载失败";
    error.value = message;
    // 已有内容时只用轻提示：这时若让失败态顶掉列表，比不提示更糟。
    if (!first) {
      toast.error(message);
    }
  } finally {
    if (first) {
      loading.value = false;
    }
  }
}

function handleUnblock(item: BlacklistItem) {
  uni.showModal({
    title: "移出黑名单",
    content: `确定将 ${item.nickname} 移出黑名单？`,
    success: async (res) => {
      if (!res.confirm) {
        return;
      }
      try {
        await unblockUser(item.blockedUserId);
        toast.success("已移出黑名单");
        await load();
      } catch (error) {
        toast.error((error as Error).message || "操作失败");
      }
    }
  });
}

onShow(() => {
  load();
});
</script>

<template>
  <view class="page">
    <ListState
      :loading="loading"
      :error="error"
      :empty="list.length === 0"
      empty-text="黑名单是空的"
      @retry="load"
    >
      <view class="list">
        <view v-for="item in list" :key="item.id" class="row">
          <FallbackImage v-if="item.avatarUrl" class="avatar img" :src="resolveMediaUrl(item.avatarUrl)" mode="aspectFill" />
          <view v-else class="avatar">{{ (item.nickname || "同").slice(0, 1) }}</view>
          <view class="info">
            <view class="name">{{ item.nickname }}</view>
            <view class="campus">{{ item.campusName || "本校同学" }}</view>
          </view>
          <wd-button size="small" plain @click="handleUnblock(item)">移出黑名单</wd-button>
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
</style>
