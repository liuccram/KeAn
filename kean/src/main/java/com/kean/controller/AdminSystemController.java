package com.kean.controller;

import com.kean.common.PageResult;
import com.kean.common.Result;
import com.kean.dto.AdminChangePasswordRequest;
import com.kean.dto.AdminUserStatusRequest;
import com.kean.dto.CreateAdminRequest;
import com.kean.dto.UpdateConfigRequest;
import com.kean.service.AdminSystemService;
import com.kean.vo.AdminAccountVO;
import com.kean.vo.OperationLogVO;
import com.kean.vo.SysConfigVO;
import jakarta.validation.Valid;
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
@RequestMapping("/api/admin")
public class AdminSystemController {

    private final AdminSystemService adminSystemService;

    public AdminSystemController(AdminSystemService adminSystemService) {
        this.adminSystemService = adminSystemService;
    }

    @GetMapping("/admins")
    public Result<List<AdminAccountVO>> admins() {
        return Result.ok(adminSystemService.listAdmins());
    }

    @PostMapping("/admins")
    public Result<AdminAccountVO> createAdmin(@Valid @RequestBody CreateAdminRequest request) {
        return Result.ok(adminSystemService.createAdmin(request));
    }

    @PutMapping("/me/password")
    public Result<Void> changeOwnPassword(@Valid @RequestBody AdminChangePasswordRequest request) {
        adminSystemService.changeOwnPassword(request);
        return Result.ok();
    }

    @PutMapping("/admins/{id}/status")
    public Result<AdminAccountVO> adminStatus(@PathVariable Long id, @Valid @RequestBody AdminUserStatusRequest request) {
        return Result.ok(adminSystemService.updateAdminStatus(id, request));
    }

    @GetMapping("/logs")
    public Result<PageResult<OperationLogVO>> logs(
            @RequestParam(required = false) Long adminId,
            @RequestParam(required = false) String operationType,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Long page,
            @RequestParam(required = false) Long size
    ) {
        return Result.ok(adminSystemService.listLogs(adminId, operationType, from, to, page, size));
    }

    @GetMapping("/config")
    public Result<List<SysConfigVO>> config() {
        return Result.ok(adminSystemService.listConfig());
    }

    @PutMapping("/config")
    public Result<List<SysConfigVO>> updateConfig(@Valid @RequestBody UpdateConfigRequest request) {
        return Result.ok(adminSystemService.updateConfig(request));
    }
}
