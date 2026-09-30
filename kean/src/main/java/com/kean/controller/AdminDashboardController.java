package com.kean.controller;

import com.kean.common.Result;
import com.kean.security.AdminGuard;
import com.kean.service.AdminDashboardService;
import com.kean.service.AdminUserService;
import com.kean.service.PresenceService;
import com.kean.vo.AdminDashboardVO;
import com.kean.vo.AdminStatsVO;
import com.kean.vo.UserVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
public class AdminDashboardController {

    private final AdminDashboardService adminDashboardService;
    private final PresenceService presenceService;
    private final AdminUserService adminUserService;

    public AdminDashboardController(
            AdminDashboardService adminDashboardService,
            PresenceService presenceService,
            AdminUserService adminUserService
    ) {
        this.adminDashboardService = adminDashboardService;
        this.presenceService = presenceService;
        this.adminUserService = adminUserService;
    }

    @GetMapping("/dashboard")
    public Result<AdminDashboardVO> dashboard() {
        return Result.ok(adminDashboardService.dashboard());
    }

    @GetMapping("/online")
    public Result<Long> online() {
        AdminGuard.require();
        return Result.ok(presenceService.onlineCount());
    }

    @GetMapping("/online/users")
    public Result<List<UserVO>> onlineUsers() {
        return Result.ok(adminUserService.listOnline());
    }

    @GetMapping("/stats")
    public Result<AdminStatsVO> stats(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to
    ) {
        return Result.ok(adminDashboardService.stats(from, to));
    }
}
