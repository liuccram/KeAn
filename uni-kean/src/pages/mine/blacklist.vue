<script setup lang="ts">
import { listBlacklist, unblockUser, type BlacklistItem } from "@/api/blacklist";
import { resolveMediaUrl } from "@/utils/request";
import { onShow } from "@dcloudio/uni-app";
import { useToast } from "wot-design-uni";
import { ref } from "vue";

const toast = useToast();
const loading = ref(false);
const list = ref<BlacklistItem[]>([]);

async function load() {
  loading.value = true;
  try {
    list.value = await listBlacklist();
  } catch (error) {
    toast.error((error as Error).message || "加载失败");
  } finally {
    loading.value = false;
  }
}

function handleUnblock(item: BlacklistItem) {
  uni.showModal({
    title: "取消拉黑",
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
    <view v-if="list.length" class="list">
      <view v-for="item in list" :key="item.id" class="row">
        <image v-if="item.avatarUrl" class="avatar img" :src="resolveMediaUrl(item.avatarUrl)" mode="aspectFill" />
        <view v-else class="avatar">{{ (item.nickname || "同").slice(0, 1) }}</view>
        <view class="info">
          <view class="name">{{ item.nickname }}</view>
          <view class="campus">{{ item.campusName || "本校同学" }}</view>
        </view>
        <wd-button size="small" plain @click="handleUnblock(item)">取消拉黑</wd-button>
      </view>
    </view>
    <wd-status-tip v-else-if="!loading" image="content" tip="黑名单是空的" />
    <wd-toast />
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: #f5f6f8;
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
</style>
