<script setup lang="ts">
import { computed, ref } from "vue";

const props = withDefaults(
  defineProps<{
    modelValue: string;
    disabled?: boolean;
  }>(),
  { disabled: false }
);

const emit = defineEmits<{
  "update:modelValue": [value: string];
}>();

const focusing = ref(false);

const digits = computed(() => {
  const value = String(props.modelValue || "")
    .replace(/\D/g, "")
    .slice(0, 6);
  return Array.from({ length: 6 }, (_, index) => value[index] || "");
});

const cursor = computed(() => Math.min(digits.value.filter(Boolean).length, 5));

function readValue(event: unknown): string {
  const payload = event as { detail?: { value?: string }; target?: { value?: string } };
  return String(payload.detail?.value ?? payload.target?.value ?? "");
}

function onInput(event: unknown) {
  emit("update:modelValue", readValue(event).replace(/\D/g, "").slice(0, 6));
}
</script>

<template>
  <view class="code-wrap">
    <view class="boxes">
      <view
        v-for="(digit, index) in digits"
        :key="index"
        class="box"
        :class="{ filled: digit, active: focusing && cursor === index }"
      >
        {{ digit }}
      </view>
    </view>
    <input
      class="ghost"
      type="tel"
      :value="modelValue"
      maxlength="6"
      :disabled="disabled"
      :adjust-position="true"
      @input="onInput"
      @focus="focusing = true"
      @blur="focusing = false"
    />
  </view>
</template>

<style scoped>
.code-wrap {
  position: relative;
}
.boxes {
  display: flex;
  gap: 8px;
  justify-content: space-between;
  max-width: 360px;
}
.box {
  flex: 1;
  max-width: 48px;
  height: 44px;
  border-radius: 8px;
  border: 1px solid #d9e1ec;
  background: #fff;
  color: #1d2129;
  font-size: 20px;
  font-weight: 650;
  display: flex;
  align-items: center;
  justify-content: center;
}
.box.filled {
  border-color: #4d80f0;
}
.box.active {
  border-color: #4d80f0;
  box-shadow: 0 0 0 2px rgba(77, 128, 240, 0.16);
}
.ghost {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  opacity: 0;
  z-index: 2;
  color: transparent;
  background: transparent;
}
</style>
