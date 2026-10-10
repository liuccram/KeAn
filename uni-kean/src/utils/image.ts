const COMPRESS_OVER_BYTES = 300 * 1024;
const HEAVY_OVER_BYTES = 2 * 1024 * 1024;

/**
 * 服务端 ImageMagic 按【文件头】判断类型，只认这几种；其余一律 40014「仅支持 jpg/png/webp/gif 图片」。
 */
const SERVER_ACCEPTED_TYPES = ["jpg", "jpeg", "png", "gif", "webp"];

function fileSize(filePath: string): Promise<number> {
  return new Promise((resolve) => {
    uni.getFileInfo({
      filePath,
      success: (res) => resolve(Number(res.size) || 0),
      fail: () => resolve(0)
    });
  });
}

/** 读取真实图片类型（注意：不是扩展名）。 */
function imageType(filePath: string): Promise<string> {
  return new Promise((resolve) => {
    uni.getImageInfo({
      src: filePath,
      success: (info) => resolve(String((info as { type?: string }).type || "").toLowerCase()),
      fail: () => resolve("")
    });
  });
}

function compress(filePath: string, quality: number): Promise<string> {
  return new Promise((resolve) => {
    // ⚠️ uni.compressImage 是【App / 小程序】专有 API，H5 上根本不存在 ✗
    // 直接调用会抛 "uni.compressImage is not a function" ✗ 导致图片发不出去 ✓
    // （线上真实报错：私聊里发图片直接失败）
    // H5 没有压缩能力 → 跳过压缩、原样上传 ✓（服务端 multipart 限 5MB ✓ 相册图多半够 ✓；
    // 真的过大时由上传接口报错，用户会看到明确提示 ✓ 而不是一个 JS 异常 ✗）
    const compressImage = (uni as unknown as { compressImage?: unknown }).compressImage;
    if (typeof compressImage !== "function") {
      resolve(filePath);
      return;
    }
    uni.compressImage({
      src: filePath,
      quality,
      compressedWidth: 1600,
      success: (res) => resolve(res.tempFilePath || filePath),
      fail: () => resolve(filePath)
    });
  });
}

/**
 * 上传前的预处理。
 *
 * 原实现只看体积：小于 300KB 就原样上传。但 Android 相册里很常见 **HEIF** 格式
 * （抖音等应用还会把它存成 .png / .jpg 扩展名），服务端按文件头认不出，会返回
 * 「仅支持 jpg/png/webp/gif 图片」—— 而用户看到文件名是 .png，完全无法理解。
 *
 * 所以判定条件改成【体积小 **且** 格式服务端认识】才原样上传；否则走 compressImage，
 * 它会用原生解码把 HEIF 之类统一转成服务端能接受的格式（顺带压缩）。
 */
export async function prepareImageForUpload(filePath: string): Promise<string> {
  const [size, type] = await Promise.all([fileSize(filePath), imageType(filePath)]);

  // 类型读不出来时按“兼容”处理，保持原有行为，不因为读不到元数据就改变上传结果。
  const serverCompatible = type === "" || SERVER_ACCEPTED_TYPES.includes(type);

  if (size <= COMPRESS_OVER_BYTES && serverCompatible) {
    return filePath;
  }

  // 纯粹为了转格式时尽量保画质，只有确实偏大才降质量。
  let quality = 90;
  if (size > HEAVY_OVER_BYTES) {
    quality = 50;
  } else if (size > COMPRESS_OVER_BYTES) {
    quality = 70;
  }

  if (!serverCompatible) {
    console.warn("[upload] 图片真实类型服务端不接受，转码后再上传", { type, size });
  }

  return compress(filePath, quality);
}
