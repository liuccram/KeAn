<script setup lang="ts">
import { useUserStore } from "@/store/user";
import { useDisplayPrefs } from "@/composables/useDisplayPrefs";
import { t } from "@/utils/i18n";
import { wallpaperLabel } from "@/utils/prefs";
import { onShow } from "@dcloudio/uni-app";
import { computed } from "vue";

const userStore = useUserStore();
const { prefs, reload } = useDisplayPrefs();

const copy = computed(() => ({
  font: t("font", prefs.lang),
  lang: t("lang", prefs.lang),
  wallpaper: t("wallpaper", prefs.lang),
  cover: t("cover", prefs.lang)
}));

const coverLabel = computed(() =>
  userStore.state.user?.coverUrl ? t("customCover", prefs.lang) : t("defaultCover", prefs.lang)
);

const fontColumns = computed(() =>
  prefs.lang === "en"
    ? [
        { label: "Small", value: "s" },
        { label: "Standard", value: "m" },
        { label: "Large", value: "l" }
      ]
    : [
        { label: "较小", value: "s" },
        { label: "标准", value: "m" },
        { label: "较大", value: "l" }
      ]
);

const langColumns = [
  { label: "简体中文", value: "zh" },
  { label: "English", value: "en" }
];

onShow(() => {
  reload();
  uni.setNavigationBarTitle({ title: t("displayTitle", prefs.lang) });
});

function open(path: string) {
  uni.navigateTo({ url: `/pages/mine/display/${path}` });
}
</script>

<template>
  <view class="page">
    <wd-cell-group border>
      <wd-picker v-model="prefs.fontSize" :label="copy.font" :columns="fontColumns" />
      <wd-picker v-model="prefs.lang" :label="copy.lang" :columns="langColumns" />
      <wd-cell :title="copy.wallpaper" :value="wallpaperLabel(prefs.wallpaper, prefs.lang)" is-link @click="open('wallpaper')" />
      <wd-cell :title="copy.cover" :value="coverLabel" is-link @click="open('cover')" />
    </wd-cell-group>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: #f5f6f8;
  padding-top: 12px;
}
</style>
