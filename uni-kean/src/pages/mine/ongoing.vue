<script setup lang="ts">
import ListState from "@/components/ListState.vue";
import OngoingTasks from "@/components/OngoingTasks.vue";
import { useLiveUpdates } from "@/composables/useLiveUpdates";
import { useOngoingTasks } from "@/composables/useOngoingTasks";
import { onShow } from "@dcloudio/uni-app";
import { useToast } from "wot-design-uni";

const toast = useToast();
const { items, loading, error, load } = useOngoingTasks();

/**
 * 刷新：已经有内容时若失败，用轻提示告知（否则页面看不出任何异常，用户会以为数据就是这样）；
 * 没有内容时由 ListState 显示失败原因和「重新加载」。
 */
async function refresh() {
  const hadItems = items.value.length > 0;
  await load();
  if (hadItems && error.value) {
    toast.error(error.value);
  }
}

onShow(() => {
  refresh();
});

useLiveUpdates((event) => {
  if (!event || event.type === "NOTICE") {
    refresh();
  }
});
</script>

<template>
  <view class="page">
    <ListState
      :loading="loading"
      :error="error"
      :empty="items.length === 0"
      empty-text="暂时没有进行中的代课"
      @retry="load"
    >
      <OngoingTasks :items="items" />
    </ListState>
    <wd-toast />
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: var(--kean-bg);
  padding-top: 12px;
}
</style>
