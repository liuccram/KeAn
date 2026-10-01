<script setup lang="ts">
import FallbackImage from "@/components/FallbackImage.vue";
import { useDisplayPrefs } from "@/composables/useDisplayPrefs";
import { applyDisplayAppearance, type WallpaperId } from "@/utils/prefs";
import { WALLPAPER_OPTIONS } from "@/utils/wallpapers";
import { nextTick } from "vue";

const { prefs } = useDisplayPrefs();

function select(id: WallpaperId) {
  prefs.wallpaper = id;
  nextTick(() => {
    applyDisplayAppearance(id);
  });
}
</script>

<template>
  <view class="page">
    <view class="hero">
      <image
        v-if="prefs.wallpaper !== 'default'"
        class="hero-img"
        :src="WALLPAPER_OPTIONS.find((item) => item.id === prefs.wallpaper)?.src"
        mode="aspectFill"
      />
      <view v-else class="hero-default">当前为默认浅灰背景</view>
    </view>
    <view class="grid">
      <view
        v-for="item in WALLPAPER_OPTIONS"
        :key="item.id"
        class="card"
        :class="{ on: prefs.wallpaper === item.id }"
        @click="select(item.id)"
      >
        <FallbackImage v-if="item.src" class="thumb" :src="item.src" mode="aspectFill" />
        <view v-else class="thumb plain" />
        <view class="name">
          <text>{{ item.label }}</text>
          <text v-if="prefs.wallpaper === item.id" class="mark">✓</text>
        </view>
      </view>
    </view>
    <view class="tip">作用于首页、发布、消息三个模块。点「默认」即可切回原来的浅灰背景。「我的」页仍用上面的「我的页背景」。</view>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: var(--kean-bg);
  padding: 16px 16px 32px;
}
.hero {
  height: 168px;
  border-radius: 14px;
  overflow: hidden;
  background: #e8edf3;
  margin-bottom: 16px;
}
.hero-img {
  width: 100%;
  height: 100%;
}
.hero-default {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--kean-muted);
  font-size: 14px;
}
.grid {
  display: flex;
  gap: 10px;
}
.card {
  flex: 1;
  background: var(--kean-card);
  border-radius: 12px;
  overflow: hidden;
  border: 2px solid transparent;
}
.card.on {
  border-color: var(--kean-primary-active);
}
.thumb {
  width: 100%;
  height: 96px;
  display: block;
}
.thumb.plain {
  background: var(--kean-bg);
}
.name {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 10px 10px;
  font-size: 12px;
  color: var(--kean-text);
}
.mark {
  color: var(--kean-primary-active);
  font-weight: 700;
}
.tip {
  margin-top: 14px;
  color: var(--kean-muted);
  font-size: 12px;
  line-height: 1.6;
}
</style>
