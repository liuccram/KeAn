package com.kean.vo;

import com.kean.common.PageResult;

import java.math.BigDecimal;

public record MyReviewsVO(
        BigDecimal publishRatingAvg,
        Integer publishRatingCount,
        Integer publishCompletedCount,
        BigDecimal applyRatingAvg,
        Integer applyRatingCount,
        Integer applyCompletedCount,
        PageResult<ReviewItemVO> page
) {
}
