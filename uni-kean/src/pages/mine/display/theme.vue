<script setup lang="ts">
import DisplayOptionPage from "@/components/DisplayOptionPage.vue";
import { useDisplayPrefs } from "@/composables/useDisplayPrefs";
import type { ThemeMode } from "@/utils/prefs";
import { computed } from "vue";

const { prefs, theme } = useDisplayPrefs();

const copy = computed(() =>
  prefs.lang === "en"
    ? {
        kicker: "Preview",
        heading: "Appearance",
        body: "Applies to the whole app. Wallpaper pages stay transparent so the sky still shows."
      }
    : {
        kicker: "显示预览",
        heading: "深浅色模式",
        body: "作用于全站。开了流星壁纸的页面仍透出星空，其它页面使用深色底。"
      }
);

const options = computed(() =>
  prefs.lang === "en"
    ? [
        { value: "light", label: "Light" },
        { value: "dark", label: "Dark" },
        { value: "system", label: "System" }
      ]
    : [
        { value: "light", label: "浅色" },
        { value: "dark", label: "深色" },
        { value: "system", label: "跟随系统" }
      ]
);

function setTheme(value: string) {
  prefs.theme = value as ThemeMode;
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
    :model-value="prefs.theme"
    @update:model-value="setTheme"
  />
</template>
