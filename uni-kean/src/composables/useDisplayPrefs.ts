import { applyDisplayAppearance, loadDisplayPrefs, resolveTheme, saveDisplayPrefs, type DisplayPrefs } from "@/utils/prefs";
import { computed, reactive, watch } from "vue";

export function useDisplayPrefs() {
  const prefs = reactive<DisplayPrefs>(loadDisplayPrefs());
  const theme = computed(() => resolveTheme(prefs.theme));

  watch(
    prefs,
    (value) => {
      saveDisplayPrefs({
        theme: value.theme,
        fontSize: value.fontSize,
        lang: value.lang,
        wallpaper: value.wallpaper || "default"
      });
      applyDisplayAppearance(value.wallpaper || "default");
    },
    { deep: true, immediate: true }
  );

  function reload() {
    Object.assign(prefs, loadDisplayPrefs());
  }

  return { prefs, theme, reload };
}
