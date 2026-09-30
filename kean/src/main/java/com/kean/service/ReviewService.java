package com.kean.service;

import com.kean.vo.MyReviewsVO;
import com.kean.vo.ReviewItemVO;
import com.kean.vo.ReviewPendingVO;

import java.util.List;

public interface ReviewService {

    List<String> tags();

    ReviewItemVO create(Long taskId, Integer rating, List<String> tags, String content);

    MyReviewsVO mine(Long page, Long size);

    List<ReviewPendingVO> pending();
}
