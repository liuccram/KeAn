<script setup lang="ts">
import DisplayOptionPage from "@/components/DisplayOptionPage.vue";
import { useDisplayPrefs } from "@/composables/useDisplayPrefs";
import type { FontSize } from "@/utils/prefs";
import { computed } from "vue";

const { prefs, theme } = useDisplayPrefs();

const copy = computed(() =>
  prefs.lang === "en"
    ? {
        kicker: "Preview",
        heading: "Font size",
        body: "Small, standard and large only apply on this page for now."
      }
    : {
        kicker: "显示预览",
        heading: "字体大小",
        body: "较小、标准和较大目前先作用在本页，方便你确认效果。"
      }
);

const options = computed(() =>
  prefs.lang === "en"
    ? [
        { value: "s", label: "Small" },
        { value: "m", label: "Standard" },
        { value: "l", label: "Large" }
      ]
    : [
        { value: "s", label: "较小" },
        { value: "m", label: "标准" },
        { value: "l", label: "较大" }
      ]
);

function setFont(value: string) {
  prefs.fontSize = value as FontSize;
}
</script>

<template>
  <DisplayOptionPage
    :kicker="copy.kicker"
    :heading="copy.heading"
    :body="copy.body"
    :theme="theme"
    :font-size="prefs.fontSize"
    :options="options"
    :model-value="prefs.fontSize"
    @update:model-value="setFont"
  />
</template>
