package com.kean.service.impl;

import com.kean.common.ErrorCode;
import com.kean.config.MinioProperties;
import com.kean.exception.BizException;
import com.kean.security.SecurityUtils;
import com.kean.service.StorageService;
import com.kean.service.SysConfigService;
import com.kean.utils.FileUrls;
import com.kean.utils.ImageMagic;
import com.kean.utils.ImageNormalizer;
import com.kean.vo.FileVO;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class StorageServiceImpl implements StorageService {

    private static final long MAX_BYTES = 5 * 1024 * 1024;
    private static final Set<String> SCENES = Set.of("AVATAR", "CHAT", "REPORT", "APPEAL", "FULFILL", "COVER");
    private static final Set<String> OPTIONAL_IMAGE_SCENES = Set.of("AVATAR", "CHAT", "COVER", "REPORT", "APPEAL");

    private final MinioClient minioClient;
    private final MinioProperties properties;
    private final SysConfigService sysConfigService;

    public StorageServiceImpl(MinioClient minioClient, MinioProperties properties, SysConfigService sysConfigService) {
        this.minioClient = minioClient;
        this.properties = properties;
        this.sysConfigService = sysConfigService;
    }

    @Override
    public FileVO upload(String scene, MultipartFile file) {
        Long userId = SecurityUtils.currentUserId();
        String normalized = scene == null ? "" : scene.trim().toUpperCase(Locale.ROOT);
        if (!SCENES.contains(normalized)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "上传场景不正确");
        }
        if (OPTIONAL_IMAGE_SCENES.contains(normalized) && !sysConfigService.imageUploadEnabled()) {
            throw new BizException(ErrorCode.UPLOAD_CLOSED);
        }
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择图片");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new BizException(ErrorCode.FILE_TOO_LARGE);
        }
        byte[] data;
        try {
            data = file.getBytes();
        } catch (Exception ex) {
            throw new BizException(ErrorCode.FILE_UPLOAD_FAILED);
        }
        if (data.length > MAX_BYTES) {
            throw new BizException(ErrorCode.FILE_TOO_LARGE);
        }
        String detected = ImageMagic.extensionOf(data);
        if (detected == null) {
            throw new BizException(ErrorCode.FILE_TYPE_INVALID);
        }
        // 重编码以剥离 EXIF（GPS / 设备 / 时间），同时限制像素与边长。
        // GIF 与 WebP 两个已知缺口见 ImageNormalizer 的类注释。
        ImageNormalizer.Result stored = ImageNormalizer.normalize(data, detected);
        byte[] content = stored.data();
        if (content.length > MAX_BYTES) {
            // 归一化后仍超限：重编码可能变大（例如大幅面 PNG）
            throw new BizException(ErrorCode.FILE_TOO_LARGE);
        }
        String folder = switch (normalized) {
            case "AVATAR" -> "avatar";
            case "COVER" -> "cover";
            case "REPORT" -> "report";
            case "APPEAL" -> "appeal";
            case "FULFILL" -> "fulfill";
            default -> "chat";
        };
        String objectKey = folder + "/" + userId + "/" + UUID.randomUUID() + "." + stored.extension();
        try (InputStream input = new ByteArrayInputStream(content)) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(objectKey)
                    .stream(input, content.length, -1)
                    .contentType(stored.contentType())
                    .build());
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BizException(ErrorCode.FILE_UPLOAD_FAILED);
        }
        return new FileVO(objectKey, FileUrls.of(objectKey));
    }

    @Override
    public boolean exists(String objectKey) {
        String key = FileUrls.objectKey(objectKey);
        if (!FileUrls.safeKey(key)) {
            return false;
        }
        try {
            minioClient.statObject(StatObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(key)
                    .build());
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    @Override
    public InputStream open(String objectKey) {
        String key = FileUrls.objectKey(objectKey);
        if (!FileUrls.safeKey(key)) {
            throw new BizException(ErrorCode.FILE_NOT_FOUND);
        }
        try {
            return minioClient.getObject(GetObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(key)
                    .build());
        } catch (Exception ex) {
            throw new BizException(ErrorCode.FILE_NOT_FOUND);
        }
    }

    @Override
    public String contentType(String objectKey) {
        String key = FileUrls.objectKey(objectKey);
        if (!FileUrls.safeKey(key)) {
            return "application/octet-stream";
        }
        try {
            StatObjectResponse stat = minioClient.statObject(StatObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(key)
                    .build());
            return StringUtils.hasText(stat.contentType()) ? stat.contentType() : "application/octet-stream";
        } catch (Exception ex) {
            return "application/octet-stream";
        }
    }
}
