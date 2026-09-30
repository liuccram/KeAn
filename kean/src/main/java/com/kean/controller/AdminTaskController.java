package com.kean.controller;

import com.kean.common.PageResult;
import com.kean.common.Result;
import com.kean.dto.CancelTaskRequest;
import com.kean.service.AdminTaskService;
import com.kean.vo.AdminApplicationVO;
import com.kean.vo.AdminTaskDetailVO;
import com.kean.vo.AdminTaskItemVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/tasks")
public class AdminTaskController {

    private final AdminTaskService adminTaskService;

    public AdminTaskController(AdminTaskService adminTaskService) {
        this.adminTaskService = adminTaskService;
    }

    @GetMapping
    public Result<PageResult<AdminTaskItemVO>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long schoolId,
            @RequestParam(required = false) Long campusId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String taskDate,
            @RequestParam(required = false) Long page,
            @RequestParam(required = false) Long size
    ) {
        return Result.ok(adminTaskService.list(keyword, schoolId, campusId, status, taskDate, page, size));
    }

    @GetMapping("/{id}")
    public Result<AdminTaskDetailVO> detail(@PathVariable Long id) {
        return Result.ok(adminTaskService.detail(id));
    }

    @GetMapping("/{id}/applications")
    public Result<List<AdminApplicationVO>> applications(@PathVariable Long id) {
        return Result.ok(adminTaskService.applications(id));
    }

    @PostMapping("/{id}/cancel")
    public Result<AdminTaskDetailVO> cancel(@PathVariable Long id, @Valid @RequestBody CancelTaskRequest request) {
        return Result.ok(adminTaskService.cancel(id, request));
    }
}
