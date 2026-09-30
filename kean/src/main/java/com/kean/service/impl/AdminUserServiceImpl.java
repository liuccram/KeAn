package com.kean.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kean.common.ErrorCode;
import com.kean.common.PageResult;
import com.kean.common.Pages;
import com.kean.dto.AdminResetPasswordRequest;
import com.kean.dto.AdminUserRestrictionsRequest;
import com.kean.dto.AdminUserStatusRequest;
import com.kean.entity.Campus;
import com.kean.entity.Review;
import com.kean.entity.School;
import com.kean.entity.SubstituteApplication;
import com.kean.entity.SubstituteTask;
import com.kean.entity.SysUser;
import com.kean.enums.ApplicationStatus;
import com.kean.enums.UserRole;
import com.kean.enums.UserStatus;
import com.kean.exception.BizException;
import com.kean.mapper.AdminStatsMapper;
import com.kean.mapper.CampusMapper;
import com.kean.mapper.ReviewMapper;
import com.kean.mapper.SchoolMapper;
import com.kean.mapper.SubstituteApplicationMapper;
import com.kean.mapper.SubstituteTaskMapper;
import com.kean.mapper.SysUserMapper;
import com.kean.security.AdminGuard;
import com.kean.security.TokenRevokeService;
import com.kean.service.AccountBanService;
import com.kean.service.AdminUserService;
import com.kean.service.NotificationService;
import com.kean.service.OperationLogService;
import com.kean.service.PresenceService;
import com.kean.utils.FileUrls;
import com.kean.utils.UserRestrictions;
import com.kean.vo.AdminGenderGroupVO;
import com.kean.vo.AdminTaskItemVO;
import com.kean.vo.AdminUserDetailVO;
import com.kean.vo.AdminUserPageVO;
import com.kean.vo.AdminUserStatusVO;
import com.kean.vo.AdminUserSummaryVO;
import com.kean.vo.ReviewItemVO;
import com.kean.vo.UserConverter;
import com.kean.vo.UserVO;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AdminUserServiceImpl implements AdminUserService {

    private static final TypeReference<List<String>> TAG_TYPE = new TypeReference<>() {
    };

    private final SysUserMapper sysUserMapper;
    private final SchoolMapper schoolMapper;
    private final CampusMapper campusMapper;
    private final SubstituteTaskMapper taskMapper;
    private final SubstituteApplicationMapper applicationMapper;
    private final ReviewMapper reviewMapper;
    private final PasswordEncoder passwordEncoder;
    private final TokenRevokeService tokenRevokeService;
    private final AccountBanService accountBanService;
    private final NotificationService notificationService;
    private final OperationLogService operationLogService;
    private final ObjectMapper objectMapper;
    private final AdminStatsMapper adminStatsMapper;
    private final PresenceService presenceService;

    public AdminUserServiceImpl(
            SysUserMapper sysUserMapper,
            SchoolMapper schoolMapper,
            CampusMapper campusMapper,
            SubstituteTaskMapper taskMapper,
            SubstituteApplicationMapper applicationMapper,
            ReviewMapper reviewMapper,
            PasswordEncoder passwordEncoder,
            TokenRevokeService tokenRevokeService,
            AccountBanService accountBanService,
            NotificationService notificationService,
            OperationLogService operationLogService,
            ObjectMapper objectMapper,
            AdminStatsMapper adminStatsMapper,
            PresenceService presenceService
    ) {
        this.sysUserMapper = sysUserMapper;
        this.schoolMapper = schoolMapper;
        this.campusMapper = campusMapper;
        this.taskMapper = taskMapper;
        this.applicationMapper = applicationMapper;
        this.reviewMapper = reviewMapper;
        this.passwordEncoder = passwordEncoder;
        this.tokenRevokeService = tokenRevokeService;
        this.accountBanService = accountBanService;
        this.notificationService = notificationService;
        this.operationLogService = operationLogService;
        this.objectMapper = objectMapper;
        this.adminStatsMapper = adminStatsMapper;
        this.presenceService = presenceService;
    }

    @Override
    public AdminUserPageVO list(String keyword, Long provinceId, Long schoolId, Long campusId, String gender, String status, Long page, Long size) {
        AdminGuard.require();
        long pageNo = Pages.page(page);
        long pageSize = Pages.size(size);
        LambdaQueryWrapper<SysUser> wrapper = baseFilter(keyword, provinceId, schoolId, campusId, gender, status)
                .orderByDesc(SysUser::getId);
        Page<SysUser> result = sysUserMapper.selectPage(new Page<>(pageNo, pageSize), wrapper);
        AdminUserSummaryVO summary = summary(keyword, provinceId, schoolId, campusId, gender, status);
        Set<Long> onlineIds = presenceService.onlineUserIds();
        List<UserVO> list = result.getRecords().stream()
                .map(user -> toVo(user, onlineIds.contains(user.getId())))
                .toList();
        return new AdminUserPageVO(list, result.getTotal(), pageNo, pageSize, summary, genderGroups(), statusStats());
    }

    @Override
    public AdminUserDetailVO detail(Long id) {
        AdminGuard.require();
        SysUser user = requireStudent(id);
        List<SubstituteTask> published = taskMapper.selectList(new LambdaQueryWrapper<SubstituteTask>()
                .eq(SubstituteTask::getPublisherId, user.getId())
                .orderByDesc(SubstituteTask::getId)
                .last("LIMIT 10"));
        List<SubstituteApplication> apps = applicationMapper.selectList(new LambdaQueryWrapper<SubstituteApplication>()
                .eq(SubstituteApplication::getApplicantId, user.getId())
                .orderByDesc(SubstituteApplication::getId)
                .last("LIMIT 10"));
        List<Long> appliedIds = apps.stream().map(SubstituteApplication::getTaskId).distinct().toList();
        List<SubstituteTask> applied = appliedIds.isEmpty()
                ? List.of()
                : taskMapper.selectList(new LambdaQueryWrapper<SubstituteTask>()
                .in(SubstituteTask::getId, appliedIds)
                .orderByDesc(SubstituteTask::getId));
        long publishedTotal = taskMapper.selectCount(new LambdaQueryWrapper<SubstituteTask>()
                .eq(SubstituteTask::getPublisherId, user.getId()));
        long appliedTotal = applicationMapper.selectCount(new LambdaQueryWrapper<SubstituteApplication>()
                .eq(SubstituteApplication::getApplicantId, user.getId()));
        List<Review> given = reviewMapper.selectList(new LambdaQueryWrapper<Review>()
                .eq(Review::getFromUserId, user.getId())
                .orderByDesc(Review::getId)
                .last("LIMIT 10"));
        List<Review> received = reviewMapper.selectList(new LambdaQueryWrapper<Review>()
                .eq(Review::getToUserId, user.getId())
                .orderByDesc(Review::getId)
                .last("LIMIT 10"));
        return new AdminUserDetailVO(
                toVo(user, presenceService.isOnline(user.getId())),
                new PageResult<>(toTaskItems(published), publishedTotal, 1, 10),
                new PageResult<>(toTaskItems(applied), appliedTotal, 1, 10),
                given.stream().map(this::toReview).toList(),
                received.stream().map(this::toReview).toList()
        );
    }

    @Override
    @Transactional
    public UserVO updateStatus(Long id, AdminUserStatusRequest request) {
        AdminGuard.require();
        SysUser user = requireStudent(id);
        String status = request.status().trim().toUpperCase();
        if (UserStatus.BANNED.name().equals(status)) {
            user.setStatus(UserStatus.BANNED.name());
            user.setForbidPublish(1);
            user.setForbidApply(1);
            user.setMuted(1);
            sysUserMapper.updateById(user);
            accountBanService.onBanned(user, request.remark());
            operationLogService.record("USER_BAN", "USER", user.getId(), request.remark());
        } else {
            sysUserMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                    .eq(SysUser::getId, user.getId())
                    .set(SysUser::getStatus, UserStatus.NORMAL.name())
                    .set(SysUser::getForbidPublish, 0)
                    .set(SysUser::getForbidApply, 0)
                    .set(SysUser::getMuted, 0)
                    .set(SysUser::getForbidPublishUntil, null)
                    .set(SysUser::getForbidApplyUntil, null)
                    .set(SysUser::getMutedUntil, null));
            user = requireStudent(id);
            accountBanService.onUnbanned(user.getId());
            notificationService.notifyUser(user.getId(), "SYSTEM", "账号已解封",
                    remarkOr(request.remark(), "你的账号已恢复正常。"),
                    "USER", user.getId());
            operationLogService.record("USER_UNBAN", "USER", user.getId(), request.remark());
        }
        return toVo(user);
    }

    @Override
    @Transactional
    public UserVO updateRestrictions(Long id, AdminUserRestrictionsRequest request) {
        AdminGuard.require();
        SysUser user = requireStudent(id);
        if (UserStatus.BANNED.name().equals(user.getStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "封禁用户请先解封再调整限制");
        }
        if (request.forbidPublish() == null && request.forbidApply() == null && request.muted() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请至少选择一项限制");
        }
        LambdaUpdateWrapper<SysUser> wrapper = new LambdaUpdateWrapper<SysUser>().eq(SysUser::getId, user.getId());
        if (request.forbidPublish() != null) {
            int on = flag(request.forbidPublish());
            user.setForbidPublish(on);
            user.setForbidPublishUntil(on == 1 ? UserRestrictions.untilOf(request.days()) : null);
            wrapper.set(SysUser::getForbidPublish, on).set(SysUser::getForbidPublishUntil, user.getForbidPublishUntil());
        }
        if (request.forbidApply() != null) {
            int on = flag(request.forbidApply());
            user.setForbidApply(on);
            user.setForbidApplyUntil(on == 1 ? UserRestrictions.untilOf(request.days()) : null);
            wrapper.set(SysUser::getForbidApply, on).set(SysUser::getForbidApplyUntil, user.getForbidApplyUntil());
        }
        if (request.muted() != null) {
            int on = flag(request.muted());
            user.setMuted(on);
            user.setMutedUntil(on == 1 ? UserRestrictions.untilOf(request.days()) : null);
            wrapper.set(SysUser::getMuted, on).set(SysUser::getMutedUntil, user.getMutedUntil());
        }
        sysUserMapper.update(null, wrapper);
        notificationService.notifyUser(user.getId(), "SYSTEM", "账号权限已调整",
                restrictionText(user, request.remark()),
                "USER", user.getId());
        operationLogService.record("USER_RESTRICT", "USER", user.getId(), request.remark());
        return toVo(user);
    }

    @Override
    @Transactional
    public void resetPassword(Long id, AdminResetPasswordRequest request) {
        AdminGuard.require();
        SysUser user = requireStudent(id);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        sysUserMapper.updateById(user);
        tokenRevokeService.revoke(user.getId());
        notificationService.notifyUser(user.getId(), "SYSTEM", "密码已被重置",
                "管理员已重置你的登录密码，请使用新密码登录。",
                "USER", user.getId());
        operationLogService.record("USER_RESET_PASSWORD", "USER", user.getId(), null);
    }

    @Override
    public List<UserVO> listOnline() {
        AdminGuard.require();
        Set<Long> ids = presenceService.onlineUserIds();
        if (ids.isEmpty()) {
            return List.of();
        }
        List<SysUser> users = sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getRole, UserRole.USER.name())
                .in(SysUser::getId, ids)
                .orderByDesc(SysUser::getLastLoginAt)
                .orderByDesc(SysUser::getId));
        return users.stream().map(user -> toVo(user, true)).toList();
    }

    private LambdaQueryWrapper<SysUser> baseFilter(String keyword, Long provinceId, Long schoolId, Long campusId, String gender, String status) {
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getRole, UserRole.USER.name());
        if (provinceId != null && schoolId == null) {
            List<Long> schoolIds = schoolMapper.selectList(new LambdaQueryWrapper<School>().eq(School::getProvinceId, provinceId))
                    .stream()
                    .map(School::getId)
                    .toList();
            if (schoolIds.isEmpty()) {
                wrapper.eq(SysUser::getId, -1L);
            } else {
                wrapper.in(SysUser::getSchoolId, schoolIds);
            }
        }
        if (schoolId != null) {
            wrapper.eq(SysUser::getSchoolId, schoolId);
        }
        if (campusId != null) {
            wrapper.eq(SysUser::getCampusId, campusId);
        }
        if (StringUtils.hasText(gender)) {
            wrapper.eq(SysUser::getGender, gender.trim().toUpperCase());
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(SysUser::getStatus, status.trim().toUpperCase());
        }
        if (StringUtils.hasText(keyword)) {
            String key = keyword.trim();
            wrapper.and(w -> w.like(SysUser::getUsername, key)
                    .or().like(SysUser::getNickname, key)
                    .or().like(SysUser::getPhone, key)
                    .or().like(SysUser::getEmail, key));
        }
        return wrapper;
    }

    private AdminUserSummaryVO summary(String keyword, Long provinceId, Long schoolId, Long campusId, String gender, String status) {
        LambdaQueryWrapper<SysUser> base = baseFilter(keyword, provinceId, schoolId, campusId, gender, status);
        long total = nz(sysUserMapper.selectCount(base));
        long male = nz(sysUserMapper.selectCount(baseFilter(keyword, provinceId, schoolId, campusId, gender, status).eq(SysUser::getGender, "MALE")));
        long female = nz(sysUserMapper.selectCount(baseFilter(keyword, provinceId, schoolId, campusId, gender, status).eq(SysUser::getGender, "FEMALE")));
        long banned = nz(sysUserMapper.selectCount(baseFilter(keyword, provinceId, schoolId, campusId, gender, status).eq(SysUser::getStatus, UserStatus.BANNED.name())));
        return new AdminUserSummaryVO(total, male, female, banned);
    }

    private List<AdminGenderGroupVO> genderGroups() {
        List<AdminStatsMapper.GenderCountRow> rows = adminStatsMapper.genderBySchoolCampus(null, null, null);
        Map<Long, long[]> buckets = new LinkedHashMap<>();
        for (AdminStatsMapper.GenderCountRow row : rows) {
            Long sid = row.getSchoolId() == null ? 0L : row.getSchoolId();
            long[] counts = buckets.computeIfAbsent(sid, ignored -> new long[3]);
            String gender = row.getGender() == null ? "" : row.getGender();
            long n = row.getCnt() == null ? 0 : row.getCnt();
            if ("MALE".equals(gender)) {
                counts[0] += n;
            } else if ("FEMALE".equals(gender)) {
                counts[1] += n;
            } else {
                counts[2] += n;
            }
        }
        List<AdminGenderGroupVO> groups = new ArrayList<>();
        for (Map.Entry<Long, long[]> entry : buckets.entrySet()) {
            Long schoolId = entry.getKey() == 0L ? null : entry.getKey();
            long[] counts = entry.getValue();
            School school = schoolId == null ? null : schoolMapper.selectById(schoolId);
            groups.add(new AdminGenderGroupVO(
                    schoolId,
                    school == null ? "未填写学校" : school.getName(),
                    null,
                    null,
                    counts[0],
                    counts[1],
                    counts[2],
                    counts[0] + counts[1] + counts[2]
            ));
        }
        groups.sort((a, b) -> Long.compare(b.total(), a.total()));
        return groups;
    }

    private AdminUserStatusVO statusStats() {
        long normal = 0;
        long banned = 0;
        long restricted = 0;
        long forbidPublish = 0;
        long forbidApply = 0;
        long muted = 0;
        for (AdminStatsMapper.UserFlagCountRow row : adminStatsMapper.userStatusCounts()) {
            long n = row.getCnt() == null ? 0 : row.getCnt();
            if (UserStatus.BANNED.name().equals(row.getStatus())) {
                banned += n;
                continue;
            }
            boolean publish = on(row.getForbidPublish());
            boolean apply = on(row.getForbidApply());
            boolean mute = on(row.getMuted());
            if (publish || apply || mute) {
                restricted += n;
                if (publish) {
                    forbidPublish += n;
                }
                if (apply) {
                    forbidApply += n;
                }
                if (mute) {
                    muted += n;
                }
            } else {
                normal += n;
            }
        }
        return new AdminUserStatusVO(normal, banned, restricted, forbidPublish, forbidApply, muted);
    }

    private boolean on(Integer flag) {
        return flag != null && flag == 1;
    }

    private SysUser requireStudent(Long id) {
        SysUser user = sysUserMapper.selectById(id);
        if (user == null || !UserRole.USER.name().equals(user.getRole())) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        return user;
    }

    private UserVO toVo(SysUser user) {
        return toVo(user, presenceService.isOnline(user.getId()));
    }

    private UserVO toVo(SysUser user, boolean online) {
        String schoolName = null;
        String campusName = null;
        if (user.getSchoolId() != null) {
            School school = schoolMapper.selectById(user.getSchoolId());
            if (school != null) {
                schoolName = school.getName();
            }
        }
        if (user.getCampusId() != null) {
            Campus campus = campusMapper.selectById(user.getCampusId());
            if (campus != null) {
                campusName = campus.getName();
            }
        }
        return UserConverter.toVo(user, schoolName, campusName, online);
    }

    private List<AdminTaskItemVO> toTaskItems(List<SubstituteTask> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return List.of();
        }
        Set<Long> schoolIds = tasks.stream().map(SubstituteTask::getSchoolId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> campusIds = tasks.stream().map(SubstituteTask::getCampusId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> publisherIds = tasks.stream().map(SubstituteTask::getPublisherId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> acceptedIds = tasks.stream().map(SubstituteTask::getAcceptedApplicationId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> schools = schoolIds.isEmpty() ? Map.of() : schoolMapper.selectByIds(schoolIds).stream()
                .collect(Collectors.toMap(School::getId, School::getName, (a, b) -> a));
        Map<Long, String> campuses = campusIds.isEmpty() ? Map.of() : campusMapper.selectByIds(campusIds).stream()
                .collect(Collectors.toMap(Campus::getId, Campus::getName, (a, b) -> a));
        Map<Long, String> publishers = publisherIds.isEmpty() ? Map.of() : sysUserMapper.selectByIds(publisherIds).stream()
                .collect(Collectors.toMap(SysUser::getId, SysUser::getNickname, (a, b) -> a));
        Map<Long, Long> applicantByApp = new HashMap<>();
        if (!acceptedIds.isEmpty()) {
            applicationMapper.selectByIds(acceptedIds).forEach(app -> applicantByApp.put(app.getId(), app.getApplicantId()));
        }
        Set<Long> applicantIds = applicantByApp.values().stream().filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> applicants = applicantIds.isEmpty() ? Map.of() : sysUserMapper.selectByIds(applicantIds).stream()
                .collect(Collectors.toMap(SysUser::getId, SysUser::getNickname, (a, b) -> a));
        return tasks.stream().map(task -> {
            Long applicantId = applicantByApp.get(task.getAcceptedApplicationId());
            return new AdminTaskItemVO(
                    task.getId(),
                    task.getCourseNameSnapshot(),
                    task.getPublisherId(),
                    publishers.get(task.getPublisherId()),
                    applicantId,
                    applicantId == null ? null : applicants.get(applicantId),
                    task.getTaskDate(),
                    task.getStartTime(),
                    task.getEndTime(),
                    task.getStartAt(),
                    task.getSchoolId(),
                    schools.get(task.getSchoolId()),
                    task.getCampusId(),
                    campuses.get(task.getCampusId()),
                    task.getBuilding(),
                    task.getClassroom(),
                    task.getStatus(),
                    task.getApplyCount(),
                    task.getReward(),
                    task.getCreatedAt()
            );
        }).toList();
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

    private String restrictionText(SysUser user, String remark) {
        StringBuilder builder = new StringBuilder("当前限制：");
        boolean any = false;
        if (UserRestrictions.forbidPublish(user)) {
            builder.append("禁止发布");
            appendUntil(builder, user.getForbidPublishUntil());
            builder.append(" ");
            any = true;
        }
        if (UserRestrictions.forbidApply(user)) {
            builder.append("禁止申请");
            appendUntil(builder, user.getForbidApplyUntil());
            builder.append(" ");
            any = true;
        }
        if (UserRestrictions.muted(user)) {
            builder.append("禁言");
            appendUntil(builder, user.getMutedUntil());
            builder.append(" ");
            any = true;
        }
        if (!any) {
            builder.append("已全部解除。");
        }
        if (StringUtils.hasText(remark)) {
            builder.append(" 说明：").append(remark.trim());
        }
        return builder.toString().trim();
    }

    private void appendUntil(StringBuilder builder, java.time.LocalDateTime until) {
        if (until != null) {
            builder.append("至").append(until.toLocalDate());
        }
    }

    private int flag(Integer value) {
        return value != null && value != 0 ? 1 : 0;
    }

    private String remarkOr(String remark, String fallback) {
        return StringUtils.hasText(remark) ? remark.trim() : fallback;
    }

    private long nz(Long value) {
        return value == null ? 0 : value;
    }
}
