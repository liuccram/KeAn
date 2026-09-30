<script setup lang="ts">
import DisplayOptionPage from "@/components/DisplayOptionPage.vue";
import { useDisplayPrefs } from "@/composables/useDisplayPrefs";
import type { Lang } from "@/utils/prefs";
import { computed } from "vue";

const { prefs, theme } = useDisplayPrefs();

const copy = computed(() =>
  prefs.lang === "en"
    ? {
        kicker: "Preview",
        heading: "Language",
        body: "Language only applies on this page for now."
      }
    : {
        kicker: "显示预览",
        heading: "语言",
        body: "语言目前先作用在本页，方便你确认效果。"
      }
);

const options = [
  { value: "zh", label: "简体中文" },
  { value: "en", label: "English" }
];

function setLang(value: string) {
  prefs.lang = value as Lang;
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
    :model-value="prefs.lang"
    @update:model-value="setLang"
  />
</template>
