<script setup lang="ts">
/**
 * 校区高亮标签：全站唯一的「校区」醒目红色样式。
 * 校区是用户手输的文本（选填），所以 props 只吃文本，不需要 campusId。
 * 任务列表（首页 / 进行中的代课）、任务详情、发布页都复用这一个组件，
 * 不要再各页面各写一套；没有校区时组件不渲染（调用方无需再判断颜色）。
 */
defineProps<{
  /** 校区文本（发布者手输，或旧数据 campus_id 关联出的旧校区名）。为空时不渲染任何内容 */
  name?: string | null;
}>();
</script>

<template>
  <text v-if="name" class="campus-highlight">{{ name }}</text>
</template>

<style scoped>
.campus-highlight {
  display: inline-block;
  margin-right: 4px;
  padding: 1px 6px;
  border-radius: 4px;
  background: #e5342f;
  color: #ffffff;
  font-size: 12px;
  font-weight: 600;
  line-height: 1.5;
  vertical-align: middle;
}
</style>
