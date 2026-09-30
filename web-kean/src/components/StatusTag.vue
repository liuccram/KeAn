<script setup lang="ts">
defineProps<{
  text: string;
  tone?: "success" | "warning" | "danger" | "info" | "teal" | "purple" | "muted";
}>();
</script>

<template>
  <span class="status-pill" :class="tone || 'muted'">{{ text }}</span>
</template>
