<script setup lang="ts">
import { useUserStore } from "@/store/user";
import { useDisplayPrefs } from "@/composables/useDisplayPrefs";
import { t } from "@/utils/i18n";
import { wallpaperLabel } from "@/utils/prefs";
import PageIntro from "@/components/PageIntro.vue";
import { onShow } from "@dcloudio/uni-app";
import { computed } from "vue";

const userStore = useUserStore();
const { prefs, reload } = useDisplayPrefs();

const copy = computed(() => ({
  displayTitle: t("displayTitle", prefs.lang),
  font: t("font", prefs.lang),
  lang: t("lang", prefs.lang),
  wallpaper: t("wallpaper", prefs.lang),
  cover: t("cover", prefs.lang)
}));

const coverLabel = computed(() =>
  userStore.state.user?.coverUrl ? t("customCover", prefs.lang) : t("defaultCover", prefs.lang)
);

// 深色模式已整体关闭，主题项连同 themeColumns 一并移除；字号 / 语言仍是"点击后下滑选择"的内联 picker。
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
    <PageIntro eyebrow="YOUR EXPERIENCE" :title="copy.displayTitle" description="调整阅读体验、语言和页面外观" marker="显示" />
    <view class="display-group">
      <view class="display-heading">外观与阅读</view>
      <wd-cell-group border>
      <wd-picker v-model="prefs.fontSize" :label="copy.font" :columns="fontColumns" />
      <wd-picker v-model="prefs.lang" :label="copy.lang" :columns="langColumns" />
      <wd-cell :title="copy.wallpaper" :value="wallpaperLabel(prefs.wallpaper, prefs.lang)" is-link @click="open('wallpaper')" />
      <wd-cell :title="copy.cover" :value="coverLabel" is-link @click="open('cover')" />
      </wd-cell-group>
    </view>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: var(--kean-bg);
}
.display-group { margin: 12px 16px 0; padding: 14px; border: 1px solid rgba(224,232,243,.9); border-radius: 16px; background: var(--kean-card); box-shadow: 0 5px 16px rgba(34,59,97,.035); }.display-heading { padding: 2px 2px 10px; color: var(--kean-muted); font-size: 11px; font-weight: 650; }.display-group :deep(.wd-cell-group) { overflow: hidden; border-radius: 11px; }
</style>
