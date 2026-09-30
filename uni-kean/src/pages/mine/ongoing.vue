<script setup lang="ts">
import OngoingTasks from "@/components/OngoingTasks.vue";
import { useLiveUpdates } from "@/composables/useLiveUpdates";
import { useOngoingTasks } from "@/composables/useOngoingTasks";
import { onShow } from "@dcloudio/uni-app";

const { items, loading, load } = useOngoingTasks();

onShow(() => {
  load();
});

useLiveUpdates((event) => {
  if (!event || event.type === "NOTICE") {
    load();
  }
});
</script>

<template>
  <view class="page">
    <OngoingTasks :items="items" />
    <wd-status-tip v-if="!loading && !items.length" image="content" tip="暂时没有进行中的代课" />
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: #f5f6f8;
  padding-top: 12px;
}
</style>
