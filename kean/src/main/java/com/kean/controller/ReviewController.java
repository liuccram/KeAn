package com.kean.controller;

import com.kean.common.Result;
import com.kean.dto.CreateReviewRequest;
import com.kean.service.ReviewService;
import com.kean.vo.MyReviewsVO;
import com.kean.vo.ReviewItemVO;
import com.kean.vo.ReviewPendingVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/reviews")
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @GetMapping("/tags")
    public Result<List<String>> tags() {
        return Result.ok(reviewService.tags());
    }

    @GetMapping("/pending")
    public Result<List<ReviewPendingVO>> pending() {
        return Result.ok(reviewService.pending());
    }

    @PostMapping
    public Result<ReviewItemVO> create(@Valid @RequestBody CreateReviewRequest request) {
        return Result.ok(reviewService.create(request.taskId(), request.rating(), request.tags(), request.content()));
    }

    @GetMapping("/me")
    public Result<MyReviewsVO> mine(
            @RequestParam(required = false) Long page,
            @RequestParam(required = false) Long size
    ) {
        return Result.ok(reviewService.mine(page, size));
    }
}
