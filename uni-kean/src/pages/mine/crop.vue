<script setup lang="ts">
import { cancelCrop, finishCrop, readCropJob, type CropMode } from "@/utils/imageCrop";
import { onLoad, onUnload } from "@dcloudio/uni-app";
import { computed, getCurrentInstance, reactive, ref } from "vue";

const instance = getCurrentInstance();
const sys = uni.getSystemInfoSync();
const winW = sys.windowWidth || 375;
const winH = sys.windowHeight || 667;

const src = ref("");
const mode = ref<CropMode>("cover");
const ready = ref(false);
const exporting = ref(false);
const settled = ref(false);
const nw = ref(1);
const nh = ref(1);
const scale = ref(1);
const imgX = ref(0);
const imgY = ref(0);

const drag = reactive({
  active: false,
  startX: 0,
  startY: 0,
  originX: 0,
  originY: 0,
  pinch: false,
  startDist: 0,
  startScale: 1
});

const frame = computed(() => {
  if (mode.value === "avatar") {
    const size = Math.min(winW - 56, 300);
    return {
      width: size,
      height: size,
      left: (winW - size) / 2,
      top: Math.max(72, (winH - size) / 2 - 48)
    };
  }
  const width = winW - 32;
  const height = Math.round(width / 2.2);
  return {
    width,
    height,
    left: 16,
    top: Math.max(64, (winH - height) / 2 - 80)
  };
});

const minScale = computed(() => Math.max(frame.value.width / nw.value, frame.value.height / nh.value));
const maxScale = computed(() => minScale.value * 4);

const imageStyle = computed(() => ({
  left: `${imgX.value}px`,
  top: `${imgY.value}px`,
  width: `${nw.value * scale.value}px`,
  height: `${nh.value * scale.value}px`
}));

const frameStyle = computed(() => ({
  left: `${frame.value.left}px`,
  top: `${frame.value.top}px`,
  width: `${frame.value.width}px`,
  height: `${frame.value.height}px`,
  borderRadius: mode.value === "avatar" ? "50%" : "8px"
}));

function clampPos() {
  const box = frame.value;
  const displayW = nw.value * scale.value;
  const displayH = nh.value * scale.value;
  const minX = box.left + box.width - displayW;
  const minY = box.top + box.height - displayH;
  imgX.value = Math.min(box.left, Math.max(minX, imgX.value));
  imgY.value = Math.min(box.top, Math.max(minY, imgY.value));
}

function setScale(next: number, aroundX?: number, aroundY?: number) {
  const boxed = Math.min(maxScale.value, Math.max(minScale.value, next));
  const cx = aroundX ?? frame.value.left + frame.value.width / 2;
  const cy = aroundY ?? frame.value.top + frame.value.height / 2;
  const relX = (cx - imgX.value) / scale.value;
  const relY = (cy - imgY.value) / scale.value;
  scale.value = boxed;
  imgX.value = cx - relX * scale.value;
  imgY.value = cy - relY * scale.value;
  clampPos();
}

function fitImage() {
  scale.value = minScale.value;
  imgX.value = frame.value.left - (nw.value * scale.value - frame.value.width) / 2;
  imgY.value = frame.value.top - (nh.value * scale.value - frame.value.height) / 2;
  clampPos();
}

function loadImage(path: string) {
  uni.getImageInfo({
    src: path,
    success: (info) => {
      const localPath = (info as { path?: string }).path;
      const width = Number(info.width) || 0;
      const height = Number(info.height) || 0;
      if (width <= 0 || height <= 0) {
        // 旧写法是 Math.max(1, info.width || 1)：宽高读不出来时会被静默变成 1x1，
        // 最终把 1 个像素拉成整张图 —— 若那个像素偏暗，看起来就是"全黑"。
        console.warn("[crop] getImageInfo 返回 0 宽高，拒绝裁剪", info);
        uni.showToast({ title: "无法读取图片尺寸", icon: "none" });
        goBack();
        return;
      }
      nw.value = width;
      nh.value = height;
      // getImageInfo 返回的 path 是本地（应用沙箱内）路径，优先用它。
      // 原因：<image> 渲染在 WebView 里，读不到沙箱外的 file:// 路径（Android 会 404）。
      src.value = localPath && localPath !== path ? localPath : path;
      ready.value = true;
      fitImage();
    },
    fail: () => {
      uni.showToast({ title: "图片读取失败", icon: "none" });
      goBack();
    }
  });
}

onLoad(() => {
  const job = readCropJob();
  if (!job?.src) {
    goBack();
    return;
  }
  mode.value = job.mode === "avatar" ? "avatar" : "cover";
  loadImage(job.src);
});

onUnload(() => {
  if (!settled.value) {
    cancelCrop();
  }
});

function touchPoint(touch: { clientX?: number; pageX?: number; clientY?: number; pageY?: number }) {
  return {
    x: touch.clientX ?? touch.pageX ?? 0,
    y: touch.clientY ?? touch.pageY ?? 0
  };
}

function pinchDist(touches: { clientX?: number; pageX?: number; clientY?: number; pageY?: number }[]) {
  const a = touchPoint(touches[0]);
  const b = touchPoint(touches[1]);
  return Math.hypot(a.x - b.x, a.y - b.y);
}

function onTouchStart(event: { touches?: { clientX?: number; pageX?: number; clientY?: number; pageY?: number }[] }) {
  const touches = event.touches || [];
  if (touches.length >= 2) {
    drag.pinch = true;
    drag.active = false;
    drag.startDist = pinchDist(touches);
    drag.startScale = scale.value;
    return;
  }
  if (!touches.length) {
    return;
  }
  const point = touchPoint(touches[0]);
  drag.pinch = false;
  drag.active = true;
  drag.startX = point.x;
  drag.startY = point.y;
  drag.originX = imgX.value;
  drag.originY = imgY.value;
}

function onTouchMove(event: { touches?: { clientX?: number; pageX?: number; clientY?: number; pageY?: number }[] }) {
  const touches = event.touches || [];
  if (drag.pinch && touches.length >= 2) {
    const dist = pinchDist(touches);
    if (drag.startDist > 0) {
      setScale(drag.startScale * (dist / drag.startDist));
    }
    return;
  }
  if (!drag.active || !touches.length) {
    return;
  }
  const point = touchPoint(touches[0]);
  imgX.value = drag.originX + point.x - drag.startX;
  imgY.value = drag.originY + point.y - drag.startY;
  clampPos();
}

function onTouchEnd() {
  drag.active = false;
  drag.pinch = false;
}

function onMouseDown(event: { clientX?: number; pageX?: number; clientY?: number; pageY?: number }) {
  onTouchStart({ touches: [event] });
}

function onMouseMove(event: { clientX?: number; pageX?: number; clientY?: number; pageY?: number }) {
  if (!drag.active) {
    return;
  }
  onTouchMove({ touches: [event] });
}

function onWheel(event: { deltaY?: number }) {
  const delta = (event.deltaY || 0) > 0 ? 0.92 : 1.08;
  zoom(delta);
}

function zoom(delta: number) {
  setScale(scale.value * delta);
}

function sourceRect() {
  const box = frame.value;
  const sx = (box.left - imgX.value) / scale.value;
  const sy = (box.top - imgY.value) / scale.value;
  const sw = box.width / scale.value;
  const sh = box.height / scale.value;
  return {
    sx: Math.max(0, sx),
    sy: Math.max(0, sy),
    sw: Math.min(nw.value, sw),
    sh: Math.min(nh.value, sh)
  };
}

function isH5() {
  return typeof document !== "undefined" && typeof document.createElement === "function";
}

function loadHtmlImage(path: string) {
  return new Promise<HTMLImageElement>((resolve, reject) => {
    const image = new Image();
    image.crossOrigin = "anonymous";
    image.onload = () => resolve(image);
    image.onerror = () => reject(new Error("图片加载失败"));
    image.src = path;
  });
}

async function exportH5() {
  const { sx, sy, sw, sh } = sourceRect();
  const outW = mode.value === "avatar" ? 600 : 1080;
  const outH = mode.value === "avatar" ? 600 : Math.round(1080 / 2.2);
  const canvas = document.createElement("canvas");
  canvas.width = outW;
  canvas.height = outH;
  const ctx = canvas.getContext("2d");
  if (!ctx) {
    throw new Error("无法裁剪");
  }
  const image = await loadHtmlImage(src.value);
  ctx.drawImage(image, sx, sy, sw, sh, 0, 0, outW, outH);
  const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, "image/jpeg", 0.92));
  if (!blob) {
    throw new Error("导出失败");
  }
  return URL.createObjectURL(blob);
}

function exportUni() {
  return new Promise<string>((resolve, reject) => {
    const { sx, sy, sw, sh } = sourceRect();
    const outW = mode.value === "avatar" ? 600 : 1080;
    const outH = mode.value === "avatar" ? 600 : Math.round(1080 / 2.2);
    const ctx = uni.createCanvasContext("crop-export");
    ctx.clearRect(0, 0, outW, outH);
    ctx.drawImage(src.value, sx, sy, sw, sh, 0, 0, outW, outH);
    ctx.draw(false, () => {
      uni.canvasToTempFilePath(
        {
          canvasId: "crop-export",
          x: 0,
          y: 0,
          width: outW,
          height: outH,
          destWidth: outW,
          destHeight: outH,
          fileType: "jpg",
          quality: 0.92,
          success: (res) => resolve(res.tempFilePath),
          fail: () => reject(new Error("导出失败"))
        },
        instance
      );
    });
  });
}

/**
 * Android 上 chooseImage 可能返回【应用沙箱外】的 file:// 路径
 * （例如 /storage/emulated/0/Pictures/抖音目录/xxx.png），而 <image> 渲染在 WebView 里，
 * 读不到沙箱外路径 —— 报 "GET file:///... 404 (Not Found)"，页面就只剩背景色。
 * 这类文件还常常是 HEIF 却被存成 .png/.jpg，WebView 同样不解码。
 *
 * 兜底：用 compressImage 把图落进应用沙箱并完成格式转码，再重新加载一次。
 * compressImage 走原生解码，能读到沙箱外路径（这也是裁剪导出一直正常的原因）。
 */
const reMaterializing = ref(false);

function onImageError() {
  if (reMaterializing.value) {
    uni.showToast({ title: "图片无法显示", icon: "none" });
    return;
  }
  reMaterializing.value = true;
  console.warn("[crop] <image> 渲染失败，改用 compressImage 转存到应用沙箱后重试");
  uni.compressImage({
    src: src.value,
    quality: 100,
    success: (res) => {
      // 重新走一次 loadImage：compressImage 可能改变尺寸，而 nw/nh 同时决定
      // 显示比例和裁剪矩形，必须重新读取而不是只换路径。
      loadImage(res.tempFilePath);
    },
    fail: () => {
      uni.showToast({ title: "图片无法显示", icon: "none" });
    }
  });
}

function goBack() {
  const pages = getCurrentPages();
  if (pages.length > 1) {
    uni.navigateBack();
    return;
  }
  uni.switchTab({ url: "/pages/mine/index" });
}

function onCancel() {
  cancelCrop();
  settled.value = true;
  goBack();
}

async function onConfirm() {
  if (!ready.value || exporting.value) {
    return;
  }
  exporting.value = true;
  try {
    const path = isH5() ? await exportH5() : await exportUni();
    finishCrop(path);
    settled.value = true;
    goBack();
  } catch (error) {
    uni.showToast({ title: (error as Error).message || "裁剪失败", icon: "none" });
  } finally {
    exporting.value = false;
  }
}
</script>

<template>
  <view class="page">
    <view
      class="stage"
      @touchstart="onTouchStart"
      @touchmove.stop.prevent="onTouchMove"
      @touchend="onTouchEnd"
      @touchcancel="onTouchEnd"
      @mousedown="onMouseDown"
      @mousemove="onMouseMove"
      @mouseup="onTouchEnd"
      @mouseleave="onTouchEnd"
      @wheel.prevent="onWheel"
    >
      <image
        v-if="src"
        class="photo"
        :src="src"
        :style="imageStyle"
        mode="scaleToFill"
        @error="onImageError"
      />
      <view class="frame" :class="{ round: mode === 'avatar' }" :style="frameStyle" />
    </view>
    <view class="hint">{{ mode === "avatar" ? "拖动或缩放，选择头像展示区域" : "拖动或缩放，选择背景对外展示的部分" }}</view>
    <view class="zoom">
      <view class="zoom-btn" @click="zoom(0.85)">缩小</view>
      <view class="zoom-btn" @click="zoom(1.18)">放大</view>
    </view>
    <view class="bar">
      <view class="bar-btn" @click="onCancel">取消</view>
      <view class="bar-btn primary" @click="onConfirm">{{ exporting ? "处理中" : "完成" }}</view>
    </view>
    <canvas canvas-id="crop-export" class="export-canvas" :style="{ width: '1080px', height: '600px' }" />
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: #111113;
  overflow: hidden;
}
.stage {
  position: relative;
  width: 100vw;
  height: calc(100vh - 168px);
  overflow: hidden;
  /* 原来 #000：导致"图没渲染出来"与"图本身是黑的"外观完全一致，无法排查 */
  background: #2a2a2e;
}
.photo {
  position: absolute;
  /* 图尚未绘制时的可见占位，避免误判为全黑 */
  background: #3d3d42;
}
.frame {
  position: absolute;
  box-sizing: border-box;
  border: 2px solid rgba(255, 255, 255, 0.92);
  box-shadow: 0 0 0 9999px rgba(0, 0, 0, 0.55);
  pointer-events: none;
}
.frame.round {
  border-radius: 50%;
}
.hint {
  padding: 12px 16px 0;
  text-align: center;
  color: rgba(255, 255, 255, 0.78);
  font-size: 13px;
}
.zoom {
  display: flex;
  justify-content: center;
  gap: 12px;
  padding: 12px 16px 0;
}
.zoom-btn {
  min-width: 88px;
  height: 36px;
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.12);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
}
.bar {
  display: flex;
  justify-content: space-between;
  padding: 16px 24px 28px;
}
.bar-btn {
  color: #fff;
  font-size: 16px;
  padding: 8px 12px;
}
.bar-btn.primary {
  color: #7eb6ff;
  font-weight: 600;
}
.export-canvas {
  position: fixed;
  left: -9999px;
  top: 0;
  width: 1080px;
  height: 600px;
}
</style>
