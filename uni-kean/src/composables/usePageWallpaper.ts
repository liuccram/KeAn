import { useDisplayPrefs } from "@/composables/useDisplayPrefs";
import { applyDisplayAppearance, isMeteorWallpaper } from "@/utils/prefs";
import { wallpaperSrc } from "@/utils/wallpapers";
import { onShow } from "@dcloudio/uni-app";
import { computed } from "vue";

export function usePageWallpaper() {
  const { prefs, reload } = useDisplayPrefs();
  const wallpaperOn = computed(() => isMeteorWallpaper(prefs.wallpaper));
  const wallpaperImage = computed(() => wallpaperSrc(prefs.wallpaper));

  onShow(() => {
    reload();
    applyDisplayAppearance(prefs.wallpaper);
  });

  return { prefs, wallpaperOn, wallpaperImage };
}
