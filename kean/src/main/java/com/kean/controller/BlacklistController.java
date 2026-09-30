package com.kean.controller;

import com.kean.common.Result;
import com.kean.dto.BlockUserRequest;
import com.kean.service.BlacklistService;
import com.kean.vo.BlacklistItemVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/blacklist")
public class BlacklistController {

    private final BlacklistService blacklistService;

    public BlacklistController(BlacklistService blacklistService) {
        this.blacklistService = blacklistService;
    }

    @GetMapping
    public Result<List<BlacklistItemVO>> list() {
        return Result.ok(blacklistService.listMine());
    }

    @PostMapping
    public Result<Void> block(@Valid @RequestBody BlockUserRequest request) {
        blacklistService.block(request.blockedUserId());
        return Result.ok();
    }

    @DeleteMapping("/{userId}")
    public Result<Void> unblock(@PathVariable Long userId) {
        blacklistService.unblock(userId);
        return Result.ok();
    }
}
