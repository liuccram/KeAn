package com.kean.controller;

import com.kean.common.PageResult;
import com.kean.common.Result;
import com.kean.service.NotificationService;
import com.kean.vo.NotificationVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public Result<PageResult<NotificationVO>> list(
            @RequestParam(required = false) Long page,
            @RequestParam(required = false) Long size,
            @RequestParam(required = false) String scope
    ) {
        return Result.ok(notificationService.listMine(page, size, scope));
    }

    @GetMapping("/unread-count")
    public Result<Long> unreadCount(@RequestParam(required = false) String scope) {
        return Result.ok(notificationService.unreadCount(scope));
    }

    @PostMapping("/{id}/read")
    public Result<Void> markRead(@PathVariable Long id) {
        notificationService.markRead(id);
        return Result.ok();
    }

    @PostMapping("/read-all")
    public Result<Void> markAllRead(@RequestParam(required = false) String scope) {
        notificationService.markAllRead(scope);
        return Result.ok();
    }
}
