export type CropMode = "avatar" | "cover";

type Waiter = {
  resolve: (path: string) => void;
  reject: (error: Error) => void;
};

let waiter: Waiter | null = null;

const JOB_KEY = "kean_crop_job";

export interface CropJob {
  src: string;
  mode: CropMode;
}

export function readCropJob(): CropJob | null {
  try {
    const raw = uni.getStorageSync(JOB_KEY) as CropJob | "";
    if (!raw || typeof raw !== "object" || !raw.src) {
      return null;
    }
    return raw;
  } catch {
    return null;
  }
}

export function startCrop(src: string, mode: CropMode): Promise<string> {
  uni.setStorageSync(JOB_KEY, { src, mode });
  return new Promise((resolve, reject) => {
    waiter = { resolve, reject };
    uni.navigateTo({
      url: "/pages/mine/crop",
      fail: (err) => {
        waiter = null;
        reject(new Error(err.errMsg || "无法打开裁剪页"));
      }
    });
  });
}

export function finishCrop(path: string) {
  uni.removeStorageSync(JOB_KEY);
  const current = waiter;
  waiter = null;
  current?.resolve(path);
}

export function cancelCrop() {
  uni.removeStorageSync(JOB_KEY);
  const current = waiter;
  waiter = null;
  current?.reject(new Error("cancel"));
}

export function chooseAndCrop(mode: CropMode): Promise<string | null> {
  return new Promise((resolve) => {
    uni.chooseImage({
      count: 1,
      sizeType: ["original"],
      sourceType: ["album", "camera"],
      success: async (res) => {
        const src = res.tempFilePaths?.[0];
        if (!src) {
          resolve(null);
          return;
        }
        try {
          resolve(await startCrop(src, mode));
        } catch {
          resolve(null);
        }
      },
      fail: () => resolve(null)
    });
  });
}
