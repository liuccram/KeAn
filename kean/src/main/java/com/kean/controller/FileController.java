package com.kean.controller;

import com.kean.common.ErrorCode;
import com.kean.common.Result;
import com.kean.enums.UserRole;
import com.kean.security.FileAccessGuard;
import com.kean.security.FileUrlSigner;
import com.kean.security.LoginUser;
import com.kean.security.SecurityUtils;
import com.kean.service.FileRateLimitService;
import com.kean.service.StorageService;
import com.kean.utils.FileUrls;
import com.kean.utils.IpUtils;
import com.kean.vo.FileVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/files")
public class FileController {

    /** 独立日志名，可用 logging.level.kean.file.audit 单独控制级别。 */
    private static final Logger auditLog = LoggerFactory.getLogger("kean.file.audit");

    private final StorageService storageService;
    private final FileUrlSigner fileUrlSigner;
    private final FileAccessGuard fileAccessGuard;
    private final FileRateLimitService fileRateLimitService;

    public FileController(
            StorageService storageService,
            FileUrlSigner fileUrlSigner,
            FileAccessGuard fileAccessGuard,
            FileRateLimitService fileRateLimitService
    ) {
        this.storageService = storageService;
        this.fileUrlSigner = fileUrlSigner;
        this.fileAccessGuard = fileAccessGuard;
        this.fileRateLimitService = fileRateLimitService;
    }

    @PostMapping
    public Result<FileVO> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("scene") String scene
    ) {
        return Result.ok(storageService.upload(scene, file));
    }

    @GetMapping("/{*objectKey}")
    public void download(
            @PathVariable("objectKey") String objectKey,
            @RequestParam(value = "exp", required = false) String exp,
            @RequestParam(value = "sig", required = false) String sig,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws java.io.IOException {
        String key = FileUrls.objectKey(URLDecoder.decode(objectKey == null ? "" : objectKey, StandardCharsets.UTF_8));
        String ip = IpUtils.clientIp(request);
        LoginUser loginUser = SecurityUtils.currentUserOrNull();
        Long userId = loginUser == null ? null : loginUser.userId();

        if (!FileUrls.safeKey(key)) {
            auditLog.warn("拒绝文件读取：非法对象键 ip={} userId={} raw={}", ip, userId, objectKey);
            writeError(response, ErrorCode.FILE_NOT_FOUND);
            return;
        }

        boolean sensitive = fileUrlSigner.isSensitive(key);
        if (sensitive && !fileRateLimitService.allowSensitiveRead(userId, ip)) {
            auditLog.warn("拒绝文件读取：超出频率限制 ip={} userId={} key={}", ip, userId, key);
            writeError(response, ErrorCode.FILE_RATE_LIMITED);
            return;
        }

        boolean allowed = fileUrlSigner.verify(key, exp, sig)
                || fileAccessGuard.canRead(key, userId, isAdmin(loginUser));
        if (!allowed) {
            auditLog.warn("拒绝文件读取：无权限 ip={} userId={} 已登录={} key={}", ip, userId, loginUser != null, key);
            writeError(response, ErrorCode.FORBIDDEN);
            return;
        }

        try (InputStream input = storageService.open(key)) {
            response.setStatus(200);
            response.setContentType(storageService.contentType(key));
            if (sensitive) {
                response.setHeader("Cache-Control", "private, max-age=300");
                auditLog.info("读取敏感文件 ip={} userId={} key={}", ip, userId, key);
            } else {
                response.setHeader("Cache-Control", "public, max-age=86400");
            }
            StreamUtils.copy(input, response.getOutputStream());
        } catch (Exception ex) {
            auditLog.warn("文件读取失败 ip={} userId={} key={} 原因={}", ip, userId, key, ex.getMessage());
            writeError(response, ErrorCode.FILE_NOT_FOUND);
        }
    }

    private static boolean isAdmin(LoginUser loginUser) {
        return loginUser != null && UserRole.ADMIN.name().equals(loginUser.role());
    }

    private void writeError(HttpServletResponse response, ErrorCode errorCode) throws java.io.IOException {
        response.setStatus(errorCode.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":" + errorCode.getCode() + ",\"message\":\"" + errorCode.getMessage() + "\"}");
    }
}
