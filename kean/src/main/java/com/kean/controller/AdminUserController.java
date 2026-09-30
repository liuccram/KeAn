package com.kean.controller;

import com.kean.common.Result;
import com.kean.dto.AdminResetPasswordRequest;
import com.kean.dto.AdminUserRestrictionsRequest;
import com.kean.dto.AdminUserStatusRequest;
import com.kean.service.AdminUserService;
import com.kean.vo.AdminUserDetailVO;
import com.kean.vo.AdminUserPageVO;
import com.kean.vo.UserVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private final AdminUserService adminUserService;

    public AdminUserController(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
    }

    @GetMapping
    public Result<AdminUserPageVO> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long provinceId,
            @RequestParam(required = false) Long schoolId,
            @RequestParam(required = false) Long campusId,
            @RequestParam(required = false) String gender,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long page,
            @RequestParam(required = false) Long size
    ) {
        return Result.ok(adminUserService.list(keyword, provinceId, schoolId, campusId, gender, status, page, size));
    }

    @GetMapping("/{id}")
    public Result<AdminUserDetailVO> detail(@PathVariable Long id) {
        return Result.ok(adminUserService.detail(id));
    }

    @PutMapping("/{id}/status")
    public Result<UserVO> status(@PathVariable Long id, @Valid @RequestBody AdminUserStatusRequest request) {
        return Result.ok(adminUserService.updateStatus(id, request));
    }

    @PutMapping("/{id}/restrictions")
    public Result<UserVO> restrictions(@PathVariable Long id, @Valid @RequestBody AdminUserRestrictionsRequest request) {
        return Result.ok(adminUserService.updateRestrictions(id, request));
    }

    @PutMapping("/{id}/password")
    public Result<Void> password(@PathVariable Long id, @Valid @RequestBody AdminResetPasswordRequest request) {
        adminUserService.resetPassword(id, request);
        return Result.ok();
    }
}
