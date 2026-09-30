package com.kean.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kean.common.ErrorCode;
import com.kean.entity.Campus;
import com.kean.entity.Review;
import com.kean.entity.School;
import com.kean.entity.SubstituteTask;
import com.kean.entity.SysUser;
import com.kean.enums.UserRole;
import com.kean.exception.BizException;
import com.kean.mapper.CampusMapper;
import com.kean.mapper.ReviewMapper;
import com.kean.mapper.SchoolMapper;
import com.kean.mapper.SubstituteTaskMapper;
import com.kean.mapper.SysUserMapper;
import com.kean.security.SecurityUtils;
import com.kean.service.UserProfileService;
import com.kean.utils.FileUrls;
import com.kean.vo.PublicProfileVO;
import com.kean.vo.ReviewItemVO;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;

@Service
public class UserProfileServiceImpl implements UserProfileService {

    private static final TypeReference<List<String>> TAG_TYPE = new TypeReference<>() {
    };

    private final SysUserMapper sysUserMapper;
    private final SchoolMapper schoolMapper;
    private final CampusMapper campusMapper;
    private final ReviewMapper reviewMapper;
    private final SubstituteTaskMapper taskMapper;
    private final ObjectMapper objectMapper;

    public UserProfileServiceImpl(
            SysUserMapper sysUserMapper,
            SchoolMapper schoolMapper,
            CampusMapper campusMapper,
            ReviewMapper reviewMapper,
            SubstituteTaskMapper taskMapper,
            ObjectMapper objectMapper
    ) {
        this.sysUserMapper = sysUserMapper;
        this.schoolMapper = schoolMapper;
        this.campusMapper = campusMapper;
        this.reviewMapper = reviewMapper;
        this.taskMapper = taskMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public PublicProfileVO publicProfile(Long userId) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null || !UserRole.USER.name().equals(user.getRole())) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        Long viewerId = SecurityUtils.currentUserId();
        boolean mine = Objects.equals(viewerId, user.getId());
        boolean privateOn = user.getPrivateAccount() != null && user.getPrivateAccount() == 1;
        boolean limited = privateOn && !mine;
        String schoolName = schoolName(user.getSchoolId());
        if (limited) {
            return new PublicProfileVO(
                    user.getId(),
                    user.getNickname(),
                    FileUrls.of(user.getAvatarUrl()),
                    null,
                    schoolName,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    1,
                    true,
                    mine,
                    List.of()
            );
        }
        return new PublicProfileVO(
                user.getId(),
                user.getNickname(),
                FileUrls.of(user.getAvatarUrl()),
                user.getGender(),
                schoolName,
                campusName(user.getCampusId()),
                user.getCompletedCount(),
                user.getRatingAvg(),
                user.getRatingCount(),
                user.getPublishCompletedCount() == null ? 0 : user.getPublishCompletedCount(),
                user.getPublishRatingAvg(),
                user.getPublishRatingCount() == null ? 0 : user.getPublishRatingCount(),
                user.getApplyRatingAvg(),
                user.getApplyRatingCount() == null ? 0 : user.getApplyRatingCount(),
                privateOn ? 1 : 0,
                false,
                mine,
                recentReviews(user.getId())
        );
    }

    private List<ReviewItemVO> recentReviews(Long userId) {
        List<Review> rows = reviewMapper.selectList(new LambdaQueryWrapper<Review>()
                .eq(Review::getToUserId, userId)
                .orderByDesc(Review::getId)
                .last("LIMIT 8"));
        return rows.stream().map(this::toReview).toList();
    }

    private ReviewItemVO toReview(Review review) {
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

    private String schoolName(Long schoolId) {
        if (schoolId == null) {
            return null;
        }
        School school = schoolMapper.selectById(schoolId);
        return school == null ? null : school.getName();
    }

    private String campusName(Long campusId) {
        if (campusId == null) {
            return null;
        }
        Campus campus = campusMapper.selectById(campusId);
        return campus == null ? null : campus.getName();
    }
}
