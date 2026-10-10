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

    /**
     * 「仅允许一台设备在线」开关（保留接口，为将来恢复单设备限制留口子）。
     *
     * <p>⚠️ <b>当前默认不生效</b>：全局配置 {@code kean.security.single-device.enabled}
     * 默认 {@code false}，此时本接口只把值写进 {@code sys_user.single_device} 列、
     * <b>不顶掉任何设备</b>（顶号判定集中在 {@code LoginDeviceServiceImpl.enforceSingleDevice}，
     * 那里第一行就按全局开关返回）。</p>
     *
     * <p>客户端 {@code uni-kean} 的开关入口已随本次改动下线，所以正常情况下没有调用方；
     * 之所以不删接口/不删列：将来要恢复单设备限制时只需把环境变量打开即可，
     * 不必再改代码、也不必做数据库迁移。详见 {@code docs/ops/security-hardening.md}。</p>
     */
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
