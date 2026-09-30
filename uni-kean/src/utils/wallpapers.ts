import meteorCyan from "@/static/wallpapers/meteor-cyan.png";
import meteorViolet from "@/static/wallpapers/meteor-violet.png";
import type { WallpaperId } from "@/utils/prefs";

export const WALLPAPER_OPTIONS: { id: WallpaperId; label: string; src: string }[] = [
  { id: "default", label: "默认", src: "" },
  { id: "meteor-cyan", label: "青白流星", src: meteorCyan },
  { id: "meteor-violet", label: "紫金流星", src: meteorViolet }
];

export function wallpaperSrc(id: WallpaperId): string {
  if (id === "meteor-cyan") {
    return meteorCyan;
  }
  if (id === "meteor-violet") {
    return meteorViolet;
  }
  return "";
}
