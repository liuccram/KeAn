<script setup lang="ts">
import { computed } from "vue";

/**
 * 列表的「加载中 / 加载失败 / 空」三态容器。
 *
 * 在此之前，页面普遍只有「有数据」和「没数据」两种分支：请求失败时列表仍是空的，
 * 于是界面显示成「还没有发布过代课」这类空态 —— 用户看到的是错误的信息
 * （设备列表失败还会显示成「暂无登录设备」，那是安全场景下的危险假阴性）。
 *
 * 三条设计规则：
 * 1. 只有「没有内容可显示」时本组件才接管渲染。已有数据的情况下后台刷新失败，
 *    仍然显示旧数据（由调用方决定要不要再加一个轻提示），绝不能把列表顶掉。
 * 2. 加载态同理：只有首次加载、还没有内容时才显示，避免刷新时页面闪烁。
 * 3. 失败时给出明确原因和「重新加载」入口 —— 此前全应用没有任何重试入口。
 */
const props = withDefaults(defineProps<{
  loading?: boolean;
  error?: string;
  /** 没有内容：调用方传 !list.length */
  empty?: boolean;
  emptyText?: string;
  loadingText?: string;
}>(), {
  loading: false,
  error: "",
  empty: false,
  emptyText: "暂无数据",
  loadingText: "加载中"
});

const emit = defineEmits<{ retry: [] }>();

const state = computed(() => {
  if (!props.empty) {
    return "ready";
  }
  if (props.loading) {
    return "loading";
  }
  if (props.error) {
    return "error";
  }
  return "empty";
});
</script>

<template>
  <view v-if="state === 'loading'" class="state">
    <wd-loading />
    <view class="text">{{ loadingText }}</view>
  </view>

  <view v-else-if="state === 'error'" class="state">
    <view class="title">加载失败</view>
    <view class="text">{{ error }}</view>
    <wd-button size="small" plain @click="emit('retry')">重新加载</wd-button>
  </view>

  <wd-status-tip v-else-if="state === 'empty'" image="content" :tip="emptyText" />

  <slot v-else />
</template>

<style scoped>
.state {
  padding: 56px 24px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
}
.title {
  color: var(--kean-text);
  font-size: 15px;
  font-weight: 600;
}
.text {
  color: var(--kean-muted);
  font-size: 13px;
  line-height: 1.6;
  text-align: center;
}
</style>
