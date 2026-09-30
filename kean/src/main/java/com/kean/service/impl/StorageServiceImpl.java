package com.kean.service.impl;

import com.kean.common.ErrorCode;
import com.kean.config.MinioProperties;
import com.kean.exception.BizException;
import com.kean.security.SecurityUtils;
import com.kean.service.StorageService;
import com.kean.service.SysConfigService;
import com.kean.utils.FileUrls;
import com.kean.utils.ImageMagic;
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
        String ext = ImageMagic.extensionOf(data);
        if (ext == null) {
            throw new BizException(ErrorCode.FILE_TYPE_INVALID);
        }
        String contentType = switch (ext) {
            case "png" -> "image/png";
            case "webp" -> "image/webp";
            case "gif" -> "image/gif";
            default -> "image/jpeg";
        };
        String folder = switch (normalized) {
            case "AVATAR" -> "avatar";
            case "COVER" -> "cover";
            case "REPORT" -> "report";
            case "APPEAL" -> "appeal";
            case "FULFILL" -> "fulfill";
            default -> "chat";
        };
        String objectKey = folder + "/" + userId + "/" + UUID.randomUUID() + "." + ext;
        try (InputStream input = new ByteArrayInputStream(data)) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(objectKey)
                    .stream(input, data.length, -1)
                    .contentType(contentType)
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
