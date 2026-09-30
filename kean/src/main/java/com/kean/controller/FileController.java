package com.kean.controller;

import com.kean.common.ErrorCode;
import com.kean.common.Result;
import com.kean.enums.UserRole;
import com.kean.security.FileAccessGuard;
import com.kean.security.FileUrlSigner;
import com.kean.security.LoginUser;
import com.kean.security.SecurityUtils;
import com.kean.service.StorageService;
import com.kean.utils.FileUrls;
import com.kean.vo.FileVO;
import jakarta.servlet.http.HttpServletResponse;
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

    private final StorageService storageService;
    private final FileUrlSigner fileUrlSigner;
    private final FileAccessGuard fileAccessGuard;

    public FileController(StorageService storageService, FileUrlSigner fileUrlSigner, FileAccessGuard fileAccessGuard) {
        this.storageService = storageService;
        this.fileUrlSigner = fileUrlSigner;
        this.fileAccessGuard = fileAccessGuard;
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
            HttpServletResponse response
    ) throws java.io.IOException {
        String key = FileUrls.objectKey(URLDecoder.decode(objectKey == null ? "" : objectKey, StandardCharsets.UTF_8));
        if (!FileUrls.safeKey(key)) {
            writeError(response, ErrorCode.FILE_NOT_FOUND);
            return;
        }
        LoginUser loginUser = SecurityUtils.currentUserOrNull();
        boolean allowed = fileUrlSigner.verify(key, exp, sig)
                || fileAccessGuard.canRead(key, loginUser == null ? null : loginUser.userId(), isAdmin(loginUser));
        if (!allowed) {
            writeError(response, ErrorCode.FORBIDDEN);
            return;
        }
        try (InputStream input = storageService.open(key)) {
            response.setStatus(200);
            response.setContentType(storageService.contentType(key));
            if (fileUrlSigner.isSensitive(key)) {
                response.setHeader("Cache-Control", "private, max-age=300");
            } else {
                response.setHeader("Cache-Control", "public, max-age=86400");
            }
            StreamUtils.copy(input, response.getOutputStream());
        } catch (Exception ex) {
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
