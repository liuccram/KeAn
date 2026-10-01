package com.kean.utils;

import com.kean.common.ErrorCode;
import com.kean.exception.BizException;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Iterator;

/**
 * 上传图片的归一化处理：重编码、剥元数据、限像素、限边长。
 *
 * <p>解决三个问题：</p>
 * <ol>
 *   <li><b>元数据泄露</b>：手机照片的 EXIF 含 GPS 坐标、设备型号与拍摄时间。
 *       解码后重新编码即可丢掉这些信息。注意客户端只在文件大于 300KB 时才压缩，
 *       所以小图是原样上传的，服务端必须自己处理。</li>
 *   <li><b>解压炸弹</b>：字节数上限（5MB）挡不住"几百 KB 解出上亿像素"。
 *       这里只读文件头拿边长，超过 {@link #MAX_SOURCE_PIXELS} 直接拒绝；
 *       解码时按比例抽样，保证不会为超大图分配整幅位图。</li>
 *   <li><b>边长失控</b>：长边压到 {@link #MAX_EDGE}，与客户端
 *       {@code uni.compressImage({ compressedWidth: 1600 })} 保持一致。</li>
 * </ol>
 *
 * <p><b>两个有意保留、且未静默忽略的缺口：</b></p>
 * <ul>
 *   <li><b>GIF</b>：能读边长做上限校验，但<b>不重编码</b> —— JDK ImageIO 重编码只会写出
 *       第一帧，会把动图压平。</li>
 *   <li><b>WebP</b>：JDK ImageIO <b>没有 WebP 解码器</b>，既不能重编码也读不出边长，
 *       只能原样放行，仅受字节数上限约束。若要补上，需引入 TwelveMonkeys ImageIO 插件
 *       或直接不再接受 WebP。</li>
 * </ul>
 */
public final class ImageNormalizer {

    /** 长边上限，与 uni-kean 的 compressedWidth=1600 对齐。 */
    static final int MAX_EDGE = 1600;

    /** 源图像素硬上限。50MP 远高于任何手机拍照，只用来拦炸弹。 */
    static final long MAX_SOURCE_PIXELS = 50_000_000L;

    /** JPEG 输出质量。 */
    private static final float JPEG_QUALITY = 0.85f;

    /** 归一化结果：新的字节、扩展名与 Content-Type。 */
    public record Result(byte[] data, String extension, String contentType) {
    }

    private ImageNormalizer() {
    }

    /**
     * @param data               已通过魔数校验的原始字节
     * @param detectedExtension  {@link ImageMagic#extensionOf(byte[])} 的结论
     */
    public static Result normalize(byte[] data, String detectedExtension) {
        return normalize(data, detectedExtension, MAX_EDGE, MAX_SOURCE_PIXELS);
    }

    /** 参数化的重载，便于测试用较小的阈值驱动同一条代码路径。 */
    static Result normalize(byte[] data, String detectedExtension, int maxEdge, long maxPixels) {
        return switch (detectedExtension) {
            case "jpg", "png" -> normalizeRaster(data, detectedExtension, maxEdge, maxPixels);
            // GIF：能读边长，但不重编码，避免把动图压成一帧
            case "gif" -> passThrough(data, "gif", "image/gif", maxPixels);
            // WebP：JDK 无解码器，连边长都读不出来，只能原样放行（见类注释）
            case "webp" -> new Result(data, "webp", "image/webp");
            default -> throw new BizException(ErrorCode.FILE_TYPE_INVALID);
        };
    }

    private static Result normalizeRaster(byte[] data, String extension, int maxEdge, long maxPixels) {
        Dimensions source = readDimensions(data);
        if (source.pixels() > maxPixels) {
            throw new BizException(ErrorCode.IMAGE_DIMENSION_TOO_LARGE);
        }
        BufferedImage image = decodeSubsampled(data, source, maxEdge);
        image = scaleDown(image, maxEdge);
        byte[] encoded = encode(image, extension);
        return new Result(encoded, extension, contentTypeOf(extension));
    }

    /** 原样放行，但仍尽量做一次像素上限校验。 */
    private static Result passThrough(byte[] data, String extension, String contentType, long maxPixels) {
        Dimensions dimensions = tryReadDimensions(data);
        if (dimensions != null && dimensions.pixels() > maxPixels) {
            throw new BizException(ErrorCode.IMAGE_DIMENSION_TOO_LARGE);
        }
        return new Result(data, extension, contentType);
    }

    /** 只读文件头取边长，不解码像素。 */
    private static Dimensions readDimensions(byte[] data) {
        ImageReader reader = null;
        try (ImageInputStream input = openStream(data)) {
            reader = firstReader(input);
            reader.setInput(input, true, true);
            return new Dimensions(reader.getWidth(0), reader.getHeight(0));
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BizException(ErrorCode.FILE_UPLOAD_FAILED);
        } finally {
            if (reader != null) {
                reader.dispose();
            }
        }
    }

    private static Dimensions tryReadDimensions(byte[] data) {
        try {
            return readDimensions(data);
        } catch (BizException ex) {
            return null;
        }
    }

    /**
     * 按抽样比例解码：超大图直接解码成小位图，不会先分配整幅原始位图。
     */
    private static BufferedImage decodeSubsampled(byte[] data, Dimensions source, int maxEdge) {
        ImageReader reader = null;
        try (ImageInputStream input = openStream(data)) {
            reader = firstReader(input);
            reader.setInput(input, true, true);
            int longest = Math.max(source.width(), source.height());
            int step = Math.max(1, longest / Math.max(1, maxEdge));
            ImageReadParam param = reader.getDefaultReadParam();
            if (step > 1) {
                param.setSourceSubsampling(step, step, 0, 0);
            }
            BufferedImage image = reader.read(0, param);
            if (image == null) {
                throw new BizException(ErrorCode.FILE_UPLOAD_FAILED);
            }
            return image;
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BizException(ErrorCode.FILE_UPLOAD_FAILED);
        } finally {
            if (reader != null) {
                reader.dispose();
            }
        }
    }

    /** 只缩小，不放大；保持宽高比。 */
    private static BufferedImage scaleDown(BufferedImage image, int maxEdge) {
        int width = image.getWidth();
        int height = image.getHeight();
        int longest = Math.max(width, height);
        if (longest <= maxEdge) {
            return image;
        }
        double ratio = (double) maxEdge / longest;
        int targetWidth = Math.max(1, (int) Math.round(width * ratio));
        int targetHeight = Math.max(1, (int) Math.round(height * ratio));
        int type = image.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage target = new BufferedImage(targetWidth, targetHeight, type);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(image, 0, 0, targetWidth, targetHeight, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    /**
     * 重新编码。ImageIO 写出时不会带上源图的 EXIF / tEXt 等元数据，
     * 这正是"剥元数据"的实现方式。
     */
    private static byte[] encode(BufferedImage image, String extension) {
        String format = "jpg".equals(extension) ? "jpeg" : "png";
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName(format);
        if (!writers.hasNext()) {
            throw new BizException(ErrorCode.FILE_UPLOAD_FAILED);
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(buffer)) {
            writer.setOutput(output);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if ("jpeg".equals(format) && param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(JPEG_QUALITY);
            }
            writer.write(null, new IIOImage(image, null, null), param);
            output.flush();
        } catch (Exception ex) {
            throw new BizException(ErrorCode.FILE_UPLOAD_FAILED);
        } finally {
            writer.dispose();
        }
        return buffer.toByteArray();
    }

    private static ImageInputStream openStream(byte[] data) {
        try {
            ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(data));
            if (input == null) {
                throw new BizException(ErrorCode.FILE_TYPE_INVALID);
            }
            return input;
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BizException(ErrorCode.FILE_UPLOAD_FAILED);
        }
    }

    private static ImageReader firstReader(ImageInputStream input) {
        Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
        if (!readers.hasNext()) {
            throw new BizException(ErrorCode.FILE_TYPE_INVALID);
        }
        return readers.next();
    }

    private static String contentTypeOf(String extension) {
        return "png".equals(extension) ? "image/png" : "image/jpeg";
    }

    private record Dimensions(int width, int height) {
        long pixels() {
            return (long) width * (long) height;
        }
    }
}
