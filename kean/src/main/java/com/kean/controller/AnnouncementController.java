package com.kean.controller;

import com.kean.common.Result;
import com.kean.service.UserAnnouncementService;
import com.kean.vo.AnnouncementVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/announcements")
public class AnnouncementController {

    private final UserAnnouncementService userAnnouncementService;

    public AnnouncementController(UserAnnouncementService userAnnouncementService) {
        this.userAnnouncementService = userAnnouncementService;
    }

    @GetMapping("/active")
    public Result<List<AnnouncementVO>> active() {
        return Result.ok(userAnnouncementService.active());
    }
}
