package com.kean.utils;

import com.kean.common.ErrorCode;
import com.kean.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 上传图片归一化：缩放、像素上限、元数据剥离、以及两个已知格式缺口。
 *
 * <p>不需要 Spring、不需要数据库。像素上限用参数化的包内重载驱动，
 * 因此不必真的构造 50MP 图片。</p>
 */
class ImageNormalizerTest {

    @Test
    @DisplayName("长边超限时按比例缩小（走精确缩放路径）")
    void scalesDownPreservingAspectRatio() throws Exception {
        byte[] original = pngOf(2000, 1000, false);

        ImageNormalizer.Result result = ImageNormalizer.normalize(original, "png");

        BufferedImage decoded = decode(result.data());
        assertThat(decoded.getWidth()).isEqualTo(ImageNormalizer.MAX_EDGE);
        assertThat(decoded.getHeight()).isEqualTo(800);
    }

    @Test
    @DisplayName("超大图走抽样解码路径，也不会留下整幅位图")
    void hugeImageIsDecodedWithSubsampling() throws Exception {
        byte[] original = pngOf(3200, 800, false);

        ImageNormalizer.Result result = ImageNormalizer.normalize(original, "png");

        BufferedImage decoded = decode(result.data());
        assertThat(decoded.getWidth()).isEqualTo(ImageNormalizer.MAX_EDGE);
        assertThat(decoded.getHeight()).isEqualTo(400);
    }

    @Test
    @DisplayName("小图不会被放大")
    void smallImageIsNotUpscaled() throws Exception {
        byte[] original = pngOf(200, 100, false);

        ImageNormalizer.Result result = ImageNormalizer.normalize(original, "png");

        BufferedImage decoded = decode(result.data());
        assertThat(decoded.getWidth()).isEqualTo(200);
        assertThat(decoded.getHeight()).isEqualTo(100);
    }

    @Test
    @DisplayName("像素超过上限直接拒绝，且错误码明确")
    void tooManyPixelsIsRejected() throws Exception {
        byte[] original = pngOf(1000, 1000, false);

        assertThatThrownBy(() -> ImageNormalizer.normalize(original, "png", 1600, 500_000L))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.IMAGE_DIMENSION_TOO_LARGE));
    }

    @Test
    @DisplayName("重新编码会剥掉 PNG 的 tEXt 元数据（EXIF 剥离的等价验证）")
    void metadataIsStrippedByReencoding() throws Exception {
        byte[] original = pngWithTextChunk(pngOf(200, 200, false), "GPSLatitude", "36.6682N 117.0000E");
        // 前置条件：注入的元数据确实在里面
        assertThat(asLatin1(original)).contains("GPSLatitude");

        ImageNormalizer.Result result = ImageNormalizer.normalize(original, "png");

        assertThat(asLatin1(result.data())).doesNotContain("GPSLatitude");
        assertThat(asLatin1(result.data())).doesNotContain("36.6682N");
    }

    @Test
    @DisplayName("PNG 透明通道在重编码后保留")
    void pngAlphaIsPreserved() throws Exception {
        byte[] original = pngOf(200, 200, true);

        ImageNormalizer.Result result = ImageNormalizer.normalize(original, "png");

        assertThat(result.extension()).isEqualTo("png");
        assertThat(result.contentType()).isEqualTo("image/png");
        assertThat(decode(result.data()).getColorModel().hasAlpha()).isTrue();
    }

    @Test
    @DisplayName("JPEG 重编码后仍可解码，且扩展名与 Content-Type 正确")
    void jpegIsReencoded() throws Exception {
        byte[] original = jpegOf(1200, 900);

        ImageNormalizer.Result result = ImageNormalizer.normalize(original, "jpg");

        assertThat(result.extension()).isEqualTo("jpg");
        assertThat(result.contentType()).isEqualTo("image/jpeg");
        assertThat(decode(result.data())).isNotNull();
    }

    @Test
    @DisplayName("JPEG 的 EXIF（含 GPS 坐标）在重编码后被剥掉")
    void jpegExifIsStripped() throws Exception {
        byte[] original = jpegWithExif(jpegOf(800, 600), "GPSLatitude=36.6682N;GPSLongitude=117.0000E");
        // 前置条件：EXIF 段确实被注入进去了
        assertThat(asLatin1(original)).contains("GPSLatitude");

        ImageNormalizer.Result result = ImageNormalizer.normalize(original, "jpg");

        assertThat(asLatin1(result.data())).doesNotContain("GPSLatitude");
        assertThat(asLatin1(result.data())).doesNotContain("Exif");
        // 剥掉元数据后仍然是一张可解码的 JPEG
        assertThat(decode(result.data())).isNotNull();
    }

    @Test
    @DisplayName("GIF 原样放行，不重编码（否则动图会被压成一帧）")
    void gifIsPassedThroughUnchanged() throws Exception {
        byte[] original = gifOf(300, 200);

        ImageNormalizer.Result result = ImageNormalizer.normalize(original, "gif");

        assertThat(result.data()).isEqualTo(original);
        assertThat(result.extension()).isEqualTo("gif");
        assertThat(result.contentType()).isEqualTo("image/gif");
    }

    @Test
    @DisplayName("GIF 超像素上限时同样拒绝")
    void oversizedGifIsRejected() throws Exception {
        byte[] original = gifOf(300, 200);

        assertThatThrownBy(() -> ImageNormalizer.normalize(original, "gif", 1600, 1_000L))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.IMAGE_DIMENSION_TOO_LARGE));
    }

    @Test
    @DisplayName("WebP 原样放行（JDK 无解码器，属已知缺口）")
    void webpIsPassedThroughUnchanged() throws Exception {
        // ImageNormalizer 对 webp 不解析内容，因此任意字节即可
        byte[] original = "RIFF____WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1);

        ImageNormalizer.Result result = ImageNormalizer.normalize(original, "webp");

        assertThat(result.data()).isEqualTo(original);
        assertThat(result.extension()).isEqualTo("webp");
        assertThat(result.contentType()).isEqualTo("image/webp");
    }

    // ---------- 构造测试数据 ----------

    private static BufferedImage decode(byte[] data) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(data));
    }

    private static String asLatin1(byte[] data) {
        return new String(data, StandardCharsets.ISO_8859_1);
    }

    private static byte[] pngOf(int width, int height, boolean alpha) throws Exception {
        return encodeImage(width, height, alpha, "png");
    }

    private static byte[] gifOf(int width, int height) throws Exception {
        return encodeImage(width, height, false, "gif");
    }

    private static byte[] jpegOf(int width, int height) throws Exception {
        return encodeImage(width, height, false, "jpeg");
    }

    private static byte[] encodeImage(int width, int height, boolean alpha, String format) throws Exception {
        int type = alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage image = new BufferedImage(width, height, type);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(alpha ? new Color(0, 0, 255, 128) : Color.BLUE);
            graphics.fillRect(0, 0, width, height);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }

    /** 在 SOI 之后插入一个 APP1/Exif 段，用来验证 EXIF 是否真的被剥掉。 */
    private static byte[] jpegWithExif(byte[] jpeg, String payload) {
        byte[] exif = ("Exif\u0000\u0000" + payload).getBytes(StandardCharsets.ISO_8859_1);
        int length = exif.length + 2; // 长度字段本身占 2 字节
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);                                        // SOI (FFD8)
        out.write(new byte[] {(byte) 0xFF, (byte) 0xE1}, 0, 2);      // APP1
        out.write(new byte[] {(byte) (length >> 8), (byte) length}, 0, 2);
        out.write(exif, 0, exif.length);
        out.write(jpeg, 2, jpeg.length - 2);                          // 其余原样
        return out.toByteArray();
    }

    /** 在 IHDR 之后插入一个 tEXt chunk，用来验证元数据是否被剥掉。 */
    private static byte[] pngWithTextChunk(byte[] png, String keyword, String text) throws Exception {
        // PNG：8 字节签名 + IHDR（4 长度 + 4 类型 + 13 数据 + 4 CRC）= 前 33 字节
        int insertAt = 33;
        ByteArrayOutputStream textData = new ByteArrayOutputStream();
        textData.write(keyword.getBytes(StandardCharsets.ISO_8859_1));
        textData.write(0);
        textData.write(text.getBytes(StandardCharsets.ISO_8859_1));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(png, 0, insertAt);
        out.write(pngChunk("tEXt", textData.toByteArray()));
        out.write(png, insertAt, png.length - insertAt);
        return out.toByteArray();
    }

    private static byte[] pngChunk(String type, byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(intToBytes(data.length), 0, 4);
        byte[] typeBytes = type.getBytes(StandardCharsets.ISO_8859_1);
        out.write(typeBytes, 0, typeBytes.length);
        out.write(data, 0, data.length);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        out.write(intToBytes((int) crc.getValue()), 0, 4);
        return out.toByteArray();
    }

    private static byte[] intToBytes(int value) {
        return new byte[] {
                (byte) (value >>> 24),
                (byte) (value >>> 16),
                (byte) (value >>> 8),
                (byte) value
        };
    }
}
