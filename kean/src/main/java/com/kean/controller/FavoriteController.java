package com.kean.controller;

import com.kean.common.PageResult;
import com.kean.common.Result;
import com.kean.dto.FavoriteRequest;
import com.kean.service.FavoriteService;
import com.kean.vo.TaskVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/favorites")
public class FavoriteController {

    private final FavoriteService favoriteService;

    public FavoriteController(FavoriteService favoriteService) {
        this.favoriteService = favoriteService;
    }

    @GetMapping
    public Result<PageResult<TaskVO>> list(
            @RequestParam(required = false) Long page,
            @RequestParam(required = false) Long size
    ) {
        return Result.ok(favoriteService.listMine(page, size));
    }

    @PostMapping
    public Result<Void> add(@Valid @RequestBody FavoriteRequest request) {
        favoriteService.add(request.taskId());
        return Result.ok();
    }

    @DeleteMapping("/{taskId}")
    public Result<Void> remove(@PathVariable Long taskId) {
        favoriteService.remove(taskId);
        return Result.ok();
    }
}
