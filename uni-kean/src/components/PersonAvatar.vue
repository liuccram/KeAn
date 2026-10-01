<script setup lang="ts">
import FallbackImage from "@/components/FallbackImage.vue";
import { computed } from "vue";

const props = withDefaults(
  defineProps<{
    size?: number;
    color?: string;
  }>(),
  { size: 72, color: "#4d80f0" }
);

const src = computed(() => {
  const fill = props.color;
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 48 48">
    <circle cx="24" cy="24" r="24" fill="${fill}" fill-opacity="0.12"/>
    <circle cx="24" cy="17.2" r="7" fill="${fill}"/>
    <path d="M10 38.8c1.5-7.6 6.8-11.6 14-11.6s12.5 4 14 11.6" fill="${fill}"/>
  </svg>`;
  return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`;
});
</script>

<template>
  <FallbackImage class="pa" :src="src" :style="{ width: size + 'px', height: size + 'px' }" mode="aspectFit" />
</template>

<style scoped>
.pa {
  display: block;
  flex-shrink: 0;
  border-radius: 50%;
}
</style>
