<script setup lang="ts">
import TaskForm from "@/components/TaskForm.vue";
import { useUserStore } from "@/store/user";
import { useToast } from "wot-design-uni";
import { ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";

const toast = useToast();
const userStore = useUserStore();
const taskId = ref(0);

onLoad((query) => {
  if (!userStore.isLoggedIn.value) {
    uni.redirectTo({ url: "/pages/auth/login?redirect=publish" });
    return;
  }
  taskId.value = Number(query?.id || 0);
  if (!taskId.value) {
    toast.error("任务不存在");
  }
});

function onSuccess() {
  uni.navigateBack();
}
</script>

<template>
  <view class="page">
    <TaskForm v-if="taskId" :task-id="taskId" @success="onSuccess" />
    <wd-toast />
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: var(--kean-bg);
}
</style>
