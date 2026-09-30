const COMPRESS_OVER_BYTES = 300 * 1024;
const HEAVY_OVER_BYTES = 2 * 1024 * 1024;

function fileSize(filePath: string): Promise<number> {
  return new Promise((resolve) => {
    uni.getFileInfo({
      filePath,
      success: (res) => resolve(Number(res.size) || 0),
      fail: () => resolve(0)
    });
  });
}

function compress(filePath: string, quality: number): Promise<string> {
  return new Promise((resolve) => {
    uni.compressImage({
      src: filePath,
      quality,
      compressedWidth: 1600,
      success: (res) => resolve(res.tempFilePath || filePath),
      fail: () => resolve(filePath)
    });
  });
}

export async function prepareImageForUpload(filePath: string): Promise<string> {
  const size = await fileSize(filePath);
  if (size <= COMPRESS_OVER_BYTES) {
    return filePath;
  }
  const quality = size > HEAVY_OVER_BYTES ? 50 : 70;
  return compress(filePath, quality);
}
