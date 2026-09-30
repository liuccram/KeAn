<script setup lang="ts">
import { computed } from "vue";

const props = defineProps<{
  src?: string;
}>();

const show = computed(() => Boolean(props.src));
</script>

<template>
  <view v-if="show" class="backdrop">
    <image class="backdrop-img" :src="src" mode="aspectFill" />
  </view>
</template>

<style scoped>
.backdrop {
  position: absolute;
  left: 0;
  top: 0;
  right: 0;
  bottom: 0;
  z-index: 0;
  overflow: hidden;
  pointer-events: none;
}
.backdrop-img {
  width: 100%;
  height: 100%;
  display: block;
}
</style>
