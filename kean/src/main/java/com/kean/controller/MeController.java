package com.kean.controller;

import com.kean.common.PageResult;
import com.kean.common.Result;
import com.kean.dto.ChangeEmailRequest;
import com.kean.dto.DeleteAccountRequest;
import com.kean.dto.UpdateAvatarRequest;
import com.kean.dto.UpdateCoverRequest;
import com.kean.dto.UpdatePrivacyRequest;
import com.kean.dto.UpdateProfileRequest;
import com.kean.dto.UpdateSingleDeviceRequest;
import com.kean.service.AuthService;
import com.kean.service.LoginDeviceService;
import com.kean.service.PresenceService;
import com.kean.service.TaskService;
import com.kean.security.SecurityUtils;
import com.kean.vo.LoginDeviceVO;
import com.kean.vo.TaskVO;
import com.kean.vo.UserVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/me")
public class MeController {

    private final TaskService taskService;
    private final AuthService authService;
    private final PresenceService presenceService;
    private final LoginDeviceService loginDeviceService;

    public MeController(
            TaskService taskService,
            AuthService authService,
            PresenceService presenceService,
            LoginDeviceService loginDeviceService
    ) {
        this.taskService = taskService;
        this.authService = authService;
        this.presenceService = presenceService;
        this.loginDeviceService = loginDeviceService;
    }

    @PostMapping("/heartbeat")
    public Result<Void> heartbeat(HttpServletRequest request) {
        var user = SecurityUtils.currentUser();
        if ("USER".equals(user.role())) {
            presenceService.heartbeat(user.userId());
        }
        loginDeviceService.touchCurrent(request);
        return Result.ok();
    }

    @GetMapping("/devices")
    public Result<List<LoginDeviceVO>> devices(HttpServletRequest request) {
        loginDeviceService.touchCurrent(request);
        return Result.ok(loginDeviceService.listMine());
    }

    @DeleteMapping("/devices/{id}")
    public Result<Void> kickDevice(@PathVariable Long id) {
        loginDeviceService.kick(id);
        return Result.ok();
    }

    @PutMapping("/profile")
    public Result<UserVO> updateProfile(@Valid @RequestBody UpdateProfileRequest request) {
        return Result.ok(authService.updateProfile(request));
    }

    @PutMapping("/avatar")
    public Result<UserVO> updateAvatar(@Valid @RequestBody UpdateAvatarRequest request) {
        return Result.ok(authService.updateAvatar(request.objectKey()));
    }

    @PutMapping("/cover")
    public Result<UserVO> updateCover(@RequestBody UpdateCoverRequest request) {
        return Result.ok(authService.updateCover(request == null ? null : request.objectKey()));
    }

    @PutMapping("/email")
    public Result<UserVO> changeEmail(@Valid @RequestBody ChangeEmailRequest request) {
        return Result.ok(authService.changeEmail(request));
    }

    @PutMapping("/privacy")
    public Result<UserVO> updatePrivacy(@Valid @RequestBody UpdatePrivacyRequest request) {
        return Result.ok(authService.updatePrivacy(request.privateAccount()));
    }

    @PutMapping("/single-device")
    public Result<UserVO> updateSingleDevice(@Valid @RequestBody UpdateSingleDeviceRequest request) {
        return Result.ok(authService.updateSingleDevice(request.singleDevice()));
    }

    /**
     * 注销账号（不可逆）。必须提交当前密码，服务端用 PasswordEncoder 校验。
     * 成功后该账号被匿名化 + 逻辑删除（deleted = 1），全部设备登录态立刻失效。
     * 完整语义与执行顺序见 docs/api/auth.md 与本文件对应的接口文档。
     */
    @PostMapping("/delete-account")
    public Result<Void> deleteAccount(@Valid @RequestBody DeleteAccountRequest request) {
        authService.deleteAccount(request);
        return Result.ok();
    }

    @GetMapping("/published")
    public Result<PageResult<TaskVO>> published(
            @RequestParam(required = false) Long page,
            @RequestParam(required = false) Long size,
            @RequestParam(required = false) Boolean excludeCancelled
    ) {
        return Result.ok(taskService.listMyPublished(page, size, excludeCancelled));
    }

    @GetMapping("/applied")
    public Result<PageResult<TaskVO>> applied(
            @RequestParam(required = false) Long page,
            @RequestParam(required = false) Long size,
            @RequestParam(required = false) Boolean excludeCancelled
    ) {
        return Result.ok(taskService.listMyApplied(page, size, excludeCancelled));
    }
}
