<script setup lang="ts">
import { getCurrentInstance, onMounted, onUnmounted, ref } from "vue";

const emit = defineEmits<{ complete: [points: number[]] }>();
const instance = getCurrentInstance();
const dots = Array.from({ length: 9 }, (_, i) => i);
const path = ref<number[]>([]);
const drawing = ref(false);
const centers = ref<{ x: number; y: number }[]>([]);

onMounted(() => {
  measure();
});

onUnmounted(() => {
  detachMouse();
});

function measure() {
  uni.createSelectorQuery()
    .in(instance)
    .selectAll(".dot")
    .boundingClientRect((rects) => {
      const list = Array.isArray(rects) ? rects : [];
      centers.value = list.map((item) => ({
        x: (item.left || 0) + (item.width || 0) / 2,
        y: (item.top || 0) + (item.height || 0) / 2
      }));
    })
    .exec();
}

function pointFromEvent(e: {
  touches?: { clientX: number; clientY: number }[];
  changedTouches?: { clientX: number; clientY: number }[];
  clientX?: number;
  clientY?: number;
}) {
  const touch = e.touches?.[0] || e.changedTouches?.[0];
  if (touch) {
    return { x: touch.clientX, y: touch.clientY };
  }
  if (typeof e.clientX === "number" && typeof e.clientY === "number") {
    return { x: e.clientX, y: e.clientY };
  }
  return null;
}

function hit(x: number, y: number) {
  const threshold = 28;
  centers.value.forEach((center, index) => {
    const dx = center.x - x;
    const dy = center.y - y;
    if (dx * dx + dy * dy <= threshold * threshold && !path.value.includes(index)) {
      path.value = [...path.value, index];
    }
  });
}

function start(e: { touches?: { clientX: number; clientY: number }[]; clientX?: number; clientY?: number }) {
  measure();
  drawing.value = true;
  path.value = [];
  const p = pointFromEvent(e);
  if (p) {
    hit(p.x, p.y);
  }
}

function move(e: { touches?: { clientX: number; clientY: number }[]; clientX?: number; clientY?: number }) {
  if (!drawing.value) {
    return;
  }
  const p = pointFromEvent(e);
  if (p) {
    hit(p.x, p.y);
  }
}

function finish() {
  if (!drawing.value) {
    return;
  }
  drawing.value = false;
  detachMouse();
  if (path.value.length >= 4) {
    emit("complete", path.value.slice());
  }
  path.value = [];
}

function onMouseMove(event: MouseEvent) {
  move(event);
}

function onMouseUp() {
  finish();
}

function attachMouse() {
  if (typeof window === "undefined") {
    return;
  }
  window.addEventListener("mousemove", onMouseMove);
  window.addEventListener("mouseup", onMouseUp);
}

function detachMouse() {
  if (typeof window === "undefined") {
    return;
  }
  window.removeEventListener("mousemove", onMouseMove);
  window.removeEventListener("mouseup", onMouseUp);
}

function onMouseDown(event: MouseEvent) {
  attachMouse();
  start(event);
}

function polyline() {
  return path.value
    .map((index) => {
      const c = centers.value[index];
      return c ? `${c.x},${c.y}` : "";
    })
    .filter(Boolean)
    .join(" ");
}
</script>

<template>
  <view
    class="pad"
    @touchstart.stop.prevent="start"
    @touchmove.stop.prevent="move"
    @touchend.stop.prevent="finish"
    @mousedown.stop.prevent="onMouseDown"
  >
    <svg v-if="path.length > 1" class="lines">
      <polyline :points="polyline()" fill="none" stroke="rgba(77,128,240,0.85)" stroke-width="4" stroke-linecap="round" />
    </svg>
    <view v-for="dot in dots" :key="dot" class="dot" :class="{ on: path.includes(dot) }" />
  </view>
</template>

<style scoped>
.pad {
  position: relative;
  width: 276px;
  height: 276px;
  margin: 0 auto;
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  place-items: center;
}
.lines {
  position: fixed;
  inset: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
  z-index: 2;
}
.dot {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: rgba(77, 128, 240, 0.22);
  border: 2px solid #4d80f0;
  z-index: 3;
}
.dot.on {
  background: #4d80f0;
}
</style>
