package com.kean.controller;

import com.kean.common.Result;
import com.kean.dto.ChangePasswordRequest;
import com.kean.dto.LoginRequest;
import com.kean.dto.RegisterRequest;
import com.kean.dto.ResetPasswordRequest;
import com.kean.dto.SendSmsRequest;
import com.kean.service.AuthService;
import com.kean.service.TurnstileService;
import com.kean.vo.LoginVO;
import com.kean.vo.TurnstileConfigVO;
import com.kean.vo.SmsSendVO;
import com.kean.vo.UserVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final TurnstileService turnstileService;

    public AuthController(AuthService authService, TurnstileService turnstileService) {
        this.authService = authService;
        this.turnstileService = turnstileService;
    }

    @GetMapping("/turnstile")
    public Result<TurnstileConfigVO> turnstile() {
        return Result.ok(turnstileService.publicConfig());
    }

    @PostMapping("/register")
    public Result<UserVO> register(@Valid @RequestBody RegisterRequest request, HttpServletRequest httpRequest) {
        return Result.ok(authService.register(request, httpRequest));
    }

    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        return Result.ok(authService.login(request, httpRequest));
    }

    @PostMapping("/sms")
    public Result<SmsSendVO> sendSms(@Valid @RequestBody SendSmsRequest request, HttpServletRequest httpRequest) {
        return Result.ok(authService.sendSms(request, httpRequest));
    }

    @PostMapping("/password")
    public Result<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(request);
        return Result.ok();
    }

    @PostMapping("/password/reset")
    public Result<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request, HttpServletRequest httpRequest) {
        authService.resetPassword(request, httpRequest);
        return Result.ok();
    }

    @GetMapping("/me")
    public Result<UserVO> me() {
        return Result.ok(authService.currentUser());
    }

    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest httpRequest) {
        authService.logout(httpRequest);
        return Result.ok();
    }
}
