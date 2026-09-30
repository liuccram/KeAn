package com.kean.controller;

import com.kean.common.PageResult;
import com.kean.common.Result;
import com.kean.dto.CreateAnnouncementRequest;
import com.kean.service.AdminAnnouncementService;
import com.kean.vo.AnnouncementVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/announcements")
public class AdminAnnouncementController {

    private final AdminAnnouncementService adminAnnouncementService;

    public AdminAnnouncementController(AdminAnnouncementService adminAnnouncementService) {
        this.adminAnnouncementService = adminAnnouncementService;
    }

    @GetMapping
    public Result<PageResult<AnnouncementVO>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long page,
            @RequestParam(required = false) Long size
    ) {
        return Result.ok(adminAnnouncementService.list(status, keyword, page, size));
    }

    @PostMapping
    public Result<AnnouncementVO> create(@Valid @RequestBody CreateAnnouncementRequest request) {
        return Result.ok(adminAnnouncementService.create(request));
    }

    @PutMapping("/{id}")
    public Result<AnnouncementVO> update(@PathVariable Long id, @Valid @RequestBody CreateAnnouncementRequest request) {
        return Result.ok(adminAnnouncementService.update(id, request));
    }

    @PostMapping("/{id}/publish")
    public Result<AnnouncementVO> publish(@PathVariable Long id) {
        return Result.ok(adminAnnouncementService.publish(id));
    }

    @PostMapping("/{id}/offline")
    public Result<AnnouncementVO> offline(@PathVariable Long id) {
        return Result.ok(adminAnnouncementService.offline(id));
    }
}
