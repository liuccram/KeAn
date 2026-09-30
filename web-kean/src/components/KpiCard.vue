<script setup lang="ts">
defineProps<{
  label: string;
  value: number | string;
  accent?: "blue" | "teal" | "orange" | "purple";
  delta?: string;
  hint?: string;
  compact?: boolean;
}>();
</script>

<template>
  <div class="kpi" :class="[accent || 'blue', { compact }]">
    <div class="meta">
      <div class="label">{{ label }}</div>
      <div class="value">{{ value }}</div>
      <div v-if="delta || hint" class="foot">
        <span v-if="delta" class="delta">{{ delta }}</span>
        <span v-if="hint" class="hint">{{ hint }}</span>
      </div>
    </div>
    <div class="spark" aria-hidden="true" />
  </div>
</template>

<style scoped>
.kpi {
  background: #fff;
  border: 1px solid var(--ka-border);
  border-radius: 12px;
  padding: 16px 18px;
  display: flex;
  justify-content: space-between;
  min-height: 108px;
  position: relative;
  overflow: hidden;
}
.meta {
  position: relative;
  z-index: 1;
}
.label {
  font-size: 13px;
  color: var(--ka-muted);
}
.value {
  margin-top: 8px;
  font-size: 30px;
  font-weight: 700;
  letter-spacing: -0.5px;
  line-height: 1.1;
}
.foot {
  margin-top: 8px;
  font-size: 12px;
  display: flex;
  gap: 8px;
  align-items: center;
}
.delta {
  color: #16a34a;
  font-weight: 600;
}
.hint {
  color: var(--ka-muted);
}
.spark {
  width: 72px;
  opacity: 0.35;
  background: radial-gradient(circle at 70% 40%, currentColor 0 6px, transparent 7px),
    linear-gradient(120deg, transparent 20%, currentColor 20% 22%, transparent 22% 40%, currentColor 40% 42%, transparent 42%);
  background-size: 100% 100%, 100% 28px;
  background-repeat: no-repeat;
  background-position: right center, right 70%;
}
.blue .value,
.blue .spark {
  color: #3b82f6;
}
.teal .value,
.teal .spark {
  color: #14b8a6;
}
.orange .value,
.orange .spark {
  color: #f59e0b;
}
.purple .value,
.purple .spark {
  color: #8b5cf6;
}
.compact {
  min-height: 84px;
  padding: 12px 16px;
}
.compact .value {
  font-size: 24px;
  margin-top: 4px;
}
</style>
