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
  theme: t("theme", prefs.lang),
  font: t("font", prefs.lang),
  lang: t("lang", prefs.lang),
  wallpaper: t("wallpaper", prefs.lang),
  cover: t("cover", prefs.lang)
}));

const coverLabel = computed(() =>
  userStore.state.user?.coverUrl ? t("customCover", prefs.lang) : t("defaultCover", prefs.lang)
);

// 深浅色 / 字体大小 / 语言都是"点击后下滑选择"的内联 picker，不跳子页面。
const themeColumns = computed(() =>
  prefs.lang === "en"
    ? [
        { label: "Light", value: "light" },
        { label: "Dark", value: "dark" },
        { label: "Follow system", value: "system" }
      ]
    : [
        { label: "浅色", value: "light" },
        { label: "深色", value: "dark" },
        { label: "跟随系统", value: "system" }
      ]
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
      <wd-picker v-model="prefs.theme" :label="copy.theme" :columns="themeColumns" />
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
  background: var(--kean-bg);
  padding-top: 12px;
}
</style>
