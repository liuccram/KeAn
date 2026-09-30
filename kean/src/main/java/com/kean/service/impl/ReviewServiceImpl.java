package com.kean.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kean.common.ErrorCode;
import com.kean.common.PageResult;
import com.kean.entity.Review;
import com.kean.entity.SubstituteApplication;
import com.kean.entity.SubstituteTask;
import com.kean.entity.SysUser;
import com.kean.enums.TrustRole;
import com.kean.enums.ApplicationStatus;
import com.kean.enums.TaskStatus;
import com.kean.exception.BizException;
import com.kean.mapper.ReviewMapper;
import com.kean.mapper.SubstituteApplicationMapper;
import com.kean.mapper.SubstituteTaskMapper;
import com.kean.mapper.SysUserMapper;
import com.kean.security.SecurityUtils;
import com.kean.service.ReviewService;
import com.kean.utils.FileUrls;
import com.kean.vo.MyReviewsVO;
import com.kean.vo.ReviewItemVO;
import com.kean.vo.ReviewPendingVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
public class ReviewServiceImpl implements ReviewService {

    public static final List<String> PRESET_TAGS = List.of(
            "准时到达",
            "沟通及时",
            "认真负责",
            "态度友好",
            "完成顺利",
            "讲解清楚",
            "临时放鸽子",
            "联系不上"
    );

    private static final long DEFAULT_PAGE = 1L;
    private static final long DEFAULT_SIZE = 20L;
    private static final long MAX_SIZE = 50L;
    private static final TypeReference<List<String>> TAG_TYPE = new TypeReference<>() {
    };

    private final ReviewMapper reviewMapper;
    private final SubstituteTaskMapper taskMapper;
    private final SubstituteApplicationMapper applicationMapper;
    private final SysUserMapper sysUserMapper;
    private final ObjectMapper objectMapper;

    public ReviewServiceImpl(
            ReviewMapper reviewMapper,
            SubstituteTaskMapper taskMapper,
            SubstituteApplicationMapper applicationMapper,
            SysUserMapper sysUserMapper,
            ObjectMapper objectMapper
    ) {
        this.reviewMapper = reviewMapper;
        this.taskMapper = taskMapper;
        this.applicationMapper = applicationMapper;
        this.sysUserMapper = sysUserMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<String> tags() {
        return PRESET_TAGS;
    }

    @Override
    @Transactional
    public ReviewItemVO create(Long taskId, Integer rating, List<String> tags, String content) {
        Long userId = SecurityUtils.currentUserId();
        SubstituteTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BizException(ErrorCode.TASK_NOT_FOUND);
        }
        if (!TaskStatus.COMPLETED.name().equals(task.getStatus())) {
            throw new BizException(ErrorCode.REVIEW_NOT_ALLOWED, "代课完成后才能评价");
        }
        Long peerId = resolvePeer(task, userId);
        if (peerId == null) {
            throw new BizException(ErrorCode.REVIEW_NOT_ALLOWED);
        }
        Review existed = reviewMapper.selectOne(new LambdaQueryWrapper<Review>()
                .eq(Review::getTaskId, task.getId())
                .eq(Review::getFromUserId, userId));
        if (existed != null) {
            throw new BizException(ErrorCode.ALREADY_REVIEWED);
        }
        List<String> safeTags = sanitizeTags(tags);
        boolean raterIsPublisher = Objects.equals(task.getPublisherId(), userId);
        Review review = new Review();
        review.setTaskId(task.getId());
        review.setFromUserId(userId);
        review.setToUserId(peerId);
        review.setTargetRole(raterIsPublisher ? TrustRole.APPLICANT.name() : TrustRole.PUBLISHER.name());
        review.setCompletedFlag(1);
        review.setRating(rating);
        review.setTagsJson(writeTags(safeTags));
        review.setContent(StringUtils.hasText(content) ? content.trim() : null);
        reviewMapper.insert(review);
        refreshRating(peerId);
        return toVo(review);
    }

    @Override
    public MyReviewsVO mine(Long page, Long size) {
        Long userId = SecurityUtils.currentUserId();
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        long pageNo = page == null || page < 1 ? DEFAULT_PAGE : page;
        long pageSize = size == null || size < 1 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        Page<Review> result = reviewMapper.selectPage(
                new Page<>(pageNo, pageSize),
                new LambdaQueryWrapper<Review>()
                        .eq(Review::getToUserId, userId)
                        .orderByDesc(Review::getCreatedAt)
        );
        List<ReviewItemVO> list = result.getRecords().stream().map(this::toVo).toList();
        return new MyReviewsVO(
                user.getPublishRatingAvg(),
                user.getPublishRatingCount() == null ? 0 : user.getPublishRatingCount(),
                user.getPublishCompletedCount() == null ? 0 : user.getPublishCompletedCount(),
                user.getApplyRatingAvg(),
                user.getApplyRatingCount() == null ? 0 : user.getApplyRatingCount(),
                user.getCompletedCount() == null ? 0 : user.getCompletedCount(),
                new PageResult<>(list, result.getTotal(), pageNo, pageSize)
        );
    }

    @Override
    public List<ReviewPendingVO> pending() {
        Long userId = SecurityUtils.currentUserId();
        List<SubstituteTask> related = new ArrayList<>(taskMapper.selectList(new LambdaQueryWrapper<SubstituteTask>()
                .eq(SubstituteTask::getStatus, TaskStatus.COMPLETED.name())
                .eq(SubstituteTask::getPublisherId, userId)
                .orderByDesc(SubstituteTask::getUpdatedAt)));
        List<SubstituteApplication> mineApps = applicationMapper.selectList(new LambdaQueryWrapper<SubstituteApplication>()
                .eq(SubstituteApplication::getApplicantId, userId)
                .eq(SubstituteApplication::getStatus, ApplicationStatus.ACCEPTED.name()));
        List<Long> appliedIds = mineApps.stream().map(SubstituteApplication::getTaskId).distinct().toList();
        if (!appliedIds.isEmpty()) {
            related.addAll(taskMapper.selectList(new LambdaQueryWrapper<SubstituteTask>()
                    .eq(SubstituteTask::getStatus, TaskStatus.COMPLETED.name())
                    .in(SubstituteTask::getId, appliedIds)
                    .orderByDesc(SubstituteTask::getUpdatedAt)));
        }
        List<ReviewPendingVO> result = new ArrayList<>();
        for (SubstituteTask task : related) {
            Long peerId = resolvePeer(task, userId);
            if (peerId == null) {
                continue;
            }
            Long reviewed = reviewMapper.selectCount(new LambdaQueryWrapper<Review>()
                    .eq(Review::getTaskId, task.getId())
                    .eq(Review::getFromUserId, userId));
            if (reviewed != null && reviewed > 0) {
                continue;
            }
            if (result.stream().anyMatch(item -> Objects.equals(item.taskId(), task.getId()))) {
                continue;
            }
            SysUser peer = sysUserMapper.selectById(peerId);
            boolean publisher = Objects.equals(task.getPublisherId(), userId);
            result.add(new ReviewPendingVO(
                    task.getId(),
                    task.getCourseNameSnapshot(),
                    peer == null ? "同学" : peer.getNickname(),
                    publisher ? TrustRole.APPLICANT.name() : TrustRole.PUBLISHER.name()
            ));
        }
        return result;
    }

    private Long resolvePeer(SubstituteTask task, Long userId) {
        Long applicantId = acceptedApplicantId(task);
        if (applicantId == null) {
            return null;
        }
        if (Objects.equals(task.getPublisherId(), userId)) {
            return applicantId;
        }
        if (Objects.equals(applicantId, userId)) {
            return task.getPublisherId();
        }
        return null;
    }

    private Long acceptedApplicantId(SubstituteTask task) {
        if (task.getAcceptedApplicationId() == null) {
            return null;
        }
        SubstituteApplication application = applicationMapper.selectById(task.getAcceptedApplicationId());
        if (application == null || !ApplicationStatus.ACCEPTED.name().equals(application.getStatus())) {
            return null;
        }
        return application.getApplicantId();
    }

    private void refreshRating(Long userId) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            return;
        }
        applyRoleRating(user, TrustRole.PUBLISHER);
        applyRoleRating(user, TrustRole.APPLICANT);
        List<Review> all = reviewMapper.selectList(new LambdaQueryWrapper<Review>()
                .eq(Review::getToUserId, userId));
        int count = all.size();
        user.setRatingCount(count);
        if (count == 0) {
            user.setRatingAvg(null);
        } else {
            int sum = all.stream().mapToInt(item -> item.getRating() == null ? 0 : item.getRating()).sum();
            user.setRatingAvg(BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP));
        }
        sysUserMapper.updateById(user);
    }

    private void applyRoleRating(SysUser user, TrustRole role) {
        List<Review> reviews = reviewMapper.selectList(new LambdaQueryWrapper<Review>()
                .eq(Review::getToUserId, user.getId())
                .eq(Review::getTargetRole, role.name()));
        int count = reviews.size();
        BigDecimal avg = null;
        if (count > 0) {
            int sum = reviews.stream().mapToInt(item -> item.getRating() == null ? 0 : item.getRating()).sum();
            avg = BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
        }
        if (role == TrustRole.PUBLISHER) {
            user.setPublishRatingCount(count);
            user.setPublishRatingAvg(avg);
        } else {
            user.setApplyRatingCount(count);
            user.setApplyRatingAvg(avg);
        }
    }

    private List<String> sanitizeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        return tags.stream().filter(PRESET_TAGS::contains).distinct().limit(5).toList();
    }

    private String writeTags(List<String> tags) {
        try {
            return objectMapper.writeValueAsString(tags == null ? List.of() : tags);
        } catch (Exception ex) {
            return "[]";
        }
    }

    private List<String> readTags(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            List<String> tags = objectMapper.readValue(json, TAG_TYPE);
            return tags == null ? List.of() : tags;
        } catch (Exception ex) {
            return List.of();
        }
    }

    private ReviewItemVO toVo(Review review) {
        SubstituteTask task = taskMapper.selectById(review.getTaskId());
        SysUser from = sysUserMapper.selectById(review.getFromUserId());
        return new ReviewItemVO(
                review.getId(),
                review.getTaskId(),
                task == null ? "代课任务" : task.getCourseNameSnapshot(),
                from == null ? "同学" : from.getNickname(),
                from == null ? null : FileUrls.of(from.getAvatarUrl()),
                review.getRating(),
                readTags(review.getTagsJson()),
                review.getContent(),
                review.getTargetRole(),
                review.getCreatedAt()
        );
    }
}
