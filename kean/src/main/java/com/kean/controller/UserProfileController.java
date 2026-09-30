package com.kean.controller;

import com.kean.common.Result;
import com.kean.service.UserProfileService;
import com.kean.vo.PublicProfileVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserProfileController {

    private final UserProfileService userProfileService;

    public UserProfileController(UserProfileService userProfileService) {
        this.userProfileService = userProfileService;
    }

    @GetMapping("/{id}")
    public Result<PublicProfileVO> profile(@PathVariable Long id) {
        return Result.ok(userProfileService.publicProfile(id));
    }
}
