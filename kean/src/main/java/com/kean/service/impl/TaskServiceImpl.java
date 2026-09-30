package com.kean.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kean.common.ErrorCode;
import com.kean.common.PageResult;
import com.kean.dto.CancelTaskRequest;
import com.kean.dto.CreateTaskRequest;
import com.kean.dto.TaskQuery;
import com.kean.entity.Campus;
import com.kean.entity.Review;
import com.kean.entity.School;
import com.kean.entity.SubstituteApplication;
import com.kean.entity.SubstituteTask;
import com.kean.entity.SysUser;
import com.kean.enums.ApplicationStatus;
import com.kean.enums.TaskStatus;
import com.kean.enums.TrustRole;
import com.kean.enums.UserStatus;
import com.kean.exception.BizException;
import com.kean.mapper.CampusMapper;
import com.kean.mapper.ReviewMapper;
import com.kean.mapper.SchoolMapper;
import com.kean.mapper.SubstituteApplicationMapper;
import com.kean.mapper.SubstituteTaskMapper;
import com.kean.mapper.SysUserMapper;
import com.kean.security.LoginUser;
import com.kean.security.SecurityUtils;
import com.kean.service.BlacklistService;
import com.kean.service.FavoriteService;
import com.kean.service.NotificationService;
import com.kean.service.StorageService;
import com.kean.service.TaskService;
import com.kean.service.TaskStatusService;
import com.kean.utils.FileUrls;
import com.kean.utils.UserRestrictions;
import com.kean.vo.PublisherBriefVO;
import com.kean.vo.TaskVO;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class TaskServiceImpl implements TaskService {

    private static final long DEFAULT_SCHOOL_ID = 1L;
    private static final long DEFAULT_PAGE = 1L;
    private static final long DEFAULT_SIZE = 10L;
    private static final long MAX_SIZE = 50L;

    private final SubstituteTaskMapper taskMapper;
    private final CampusMapper campusMapper;
    private final SchoolMapper schoolMapper;
    private final SysUserMapper sysUserMapper;
    private final SubstituteApplicationMapper applicationMapper;
    private final ReviewMapper reviewMapper;
    private final TaskStatusService taskStatusService;
    private final NotificationService notificationService;
    private final BlacklistService blacklistService;
    private final FavoriteService favoriteService;
    private final StorageService storageService;

    public TaskServiceImpl(
            SubstituteTaskMapper taskMapper,
            CampusMapper campusMapper,
            SchoolMapper schoolMapper,
            SysUserMapper sysUserMapper,
            SubstituteApplicationMapper applicationMapper,
            ReviewMapper reviewMapper,
            TaskStatusService taskStatusService,
            NotificationService notificationService,
            BlacklistService blacklistService,
            FavoriteService favoriteService,
            StorageService storageService
    ) {
        this.taskMapper = taskMapper;
        this.campusMapper = campusMapper;
        this.schoolMapper = schoolMapper;
        this.sysUserMapper = sysUserMapper;
        this.applicationMapper = applicationMapper;
        this.reviewMapper = reviewMapper;
        this.taskStatusService = taskStatusService;
        this.notificationService = notificationService;
        this.blacklistService = blacklistService;
        this.favoriteService = favoriteService;
        this.storageService = storageService;
    }

    @Override
    public PageResult<TaskVO> list(TaskQuery query) {
        long pageNo = query.page() == null || query.page() < 1 ? DEFAULT_PAGE : query.page();
        long size = query.size() == null || query.size() < 1 ? DEFAULT_SIZE : Math.min(query.size(), MAX_SIZE);
        Long schoolId = resolveViewerSchoolId(query.schoolId());

        LambdaQueryWrapper<SubstituteTask> wrapper = new LambdaQueryWrapper<SubstituteTask>()
                .eq(SubstituteTask::getSchoolId, schoolId);
        String status = query.status() == null ? "" : query.status().trim().toUpperCase();
        LocalDateTime now = LocalDateTime.now();
        LoginUser viewer = SecurityUtils.currentUserOrNull();
        if ("MINE".equals(status)) {
            if (viewer == null) {
                return new PageResult<>(List.of(), 0, pageNo, size);
            }
            wrapper.eq(SubstituteTask::getPublisherId, viewer.userId())
                    .in(SubstituteTask::getStatus,
                            TaskStatus.WAITING.name(),
                            TaskStatus.APPLYING.name(),
                            TaskStatus.MATCHED.name(),
                            TaskStatus.CONFIRMED.name(),
                            TaskStatus.IN_PROGRESS.name());
        } else if (!StringUtils.hasText(status) || "OPEN".equals(status)) {
            wrapper.in(SubstituteTask::getStatus, TaskStatus.WAITING.name(), TaskStatus.APPLYING.name())
                    .ge(SubstituteTask::getStartAt, now);
        } else if ("ALL".equals(status)) {
            wrapper.in(SubstituteTask::getStatus,
                            TaskStatus.WAITING.name(),
                            TaskStatus.APPLYING.name(),
                            TaskStatus.MATCHED.name(),
                            TaskStatus.CONFIRMED.name(),
                            TaskStatus.IN_PROGRESS.name())
                    .and(w -> w.ge(SubstituteTask::getStartAt, now)
                            .or()
                            .eq(SubstituteTask::getStatus, TaskStatus.IN_PROGRESS.name()));
        } else {
            wrapper.eq(SubstituteTask::getStatus, status);
            if (TaskStatus.WAITING.name().equals(status) || TaskStatus.APPLYING.name().equals(status)) {
                wrapper.ge(SubstituteTask::getStartAt, now);
            }
        }
        if (query.courseId() != null) {
            wrapper.eq(SubstituteTask::getCourseId, query.courseId());
        }
        if (query.campusId() != null) {
            wrapper.eq(SubstituteTask::getCampusId, query.campusId());
        }
        if (StringUtils.hasText(query.taskDate())) {
            wrapper.eq(SubstituteTask::getTaskDate, parseDate(query.taskDate()));
        }
        LocalTime[] slot = parseTimeSlot(query.timeSlot());
        if (slot != null) {
            wrapper.ge(SubstituteTask::getStartTime, slot[0]).lt(SubstituteTask::getStartTime, slot[1]);
        }
        if (StringUtils.hasText(query.keyword())) {
            String keyword = query.keyword().trim();
            wrapper.and(w -> w.like(SubstituteTask::getCourseNameSnapshot, keyword)
                    .or().like(SubstituteTask::getBuilding, keyword)
                    .or().like(SubstituteTask::getClassroom, keyword));
        }
        if (viewer != null && !"MINE".equals(status)) {
            Set<Long> blocked = blacklistService.relatedUserIds(viewer.userId());
            if (!blocked.isEmpty()) {
                wrapper.notIn(SubstituteTask::getPublisherId, blocked);
            }
        }
        wrapper.orderByAsc(SubstituteTask::getStartAt).orderByDesc(SubstituteTask::getId);

        Page<SubstituteTask> page = taskMapper.selectPage(new Page<>(pageNo, size), wrapper);
        Map<Long, String> campusNames = loadCampusNames(page.getRecords());
        Set<Long> favs = favoriteIds(page.getRecords());
        List<TaskVO> list = page.getRecords().stream()
                .map(task -> toVo(task, campusNames.get(task.getCampusId()), null, null, false, null, null, false, null, null, false, null, favs.contains(task.getId())))
                .toList();
        return new PageResult<>(list, page.getTotal(), pageNo, size);
    }

    @Override
    public TaskVO detail(Long id) {
        SubstituteTask task = requireVisibleTask(id);
        Campus campus = campusMapper.selectById(task.getCampusId());
        LoginUser loginUser = SecurityUtils.currentUserOrNull();
        boolean mine = loginUser != null && Objects.equals(loginUser.userId(), task.getPublisherId());
        String myApplicationStatus = null;
        Long myApplicationId = null;
        boolean matchedApplicant = false;
        if (loginUser != null) {
            SubstituteApplication mineApp = applicationMapper.selectOne(new LambdaQueryWrapper<SubstituteApplication>()
                    .eq(SubstituteApplication::getTaskId, task.getId())
                    .eq(SubstituteApplication::getApplicantId, loginUser.userId()));
            if (mineApp != null) {
                myApplicationStatus = mineApp.getStatus();
                myApplicationId = mineApp.getId();
            }
            matchedApplicant = isAcceptedApplicant(task, loginUser.userId());
        }
        String applicantNickname = null;
        Long applicantId = acceptedApplicantId(task);
        SysUser applicantUser = null;
        if (applicantId != null) {
            applicantUser = sysUserMapper.selectById(applicantId);
            if (applicantUser != null) {
                applicantNickname = applicantUser.getNickname();
            }
        }
        PublisherBriefVO brief = toUserBrief(sysUserMapper.selectById(task.getPublisherId()), TrustRole.PUBLISHER);
        PublisherBriefVO applicantBrief = null;
        if (applicantUser != null && (mine || matchedApplicant)) {
            applicantBrief = toUserBrief(applicantUser, TrustRole.APPLICANT);
        }
        Integer myReviewRating = null;
        boolean canReview = false;
        boolean party = mine || matchedApplicant;
        if (loginUser != null && TaskStatus.COMPLETED.name().equals(task.getStatus()) && party) {
            Review myReview = reviewMapper.selectOne(new LambdaQueryWrapper<Review>()
                    .eq(Review::getTaskId, task.getId())
                    .eq(Review::getFromUserId, loginUser.userId()));
            if (myReview != null) {
                myReviewRating = myReview.getRating();
            } else {
                canReview = true;
            }
        }
        return toVo(
                task,
                campus == null ? null : campus.getName(),
                brief,
                applicantBrief,
                mine,
                myApplicationStatus,
                myApplicationId,
                matchedApplicant,
                applicantNickname,
                applicantId,
                canReview,
                myReviewRating,
                loginUser != null && favoriteService.favorited(loginUser.userId(), task.getId())
        );
    }

    @Override
    @Transactional
    public TaskVO create(CreateTaskRequest request) {
        SysUser user = requirePublisher();
        SubstituteTask task = new SubstituteTask();
        task.setPublisherId(user.getId());
        task.setSchoolId(user.getSchoolId());
        task.setApplyCount(0);
        task.setPublisherConfirmed(0);
        task.setApplicantConfirmed(0);
        task.setPublisherCompleted(0);
        task.setApplicantCompleted(0);
        fillContent(task, request, user.getSchoolId());
        taskStatusService.initWaiting(task);
        taskMapper.insert(task);
        return detail(task.getId());
    }

    @Override
    @Transactional
    public TaskVO update(Long id, CreateTaskRequest request) {
        SysUser user = requirePublisher();
        SubstituteTask task = requireOwnedTask(id, user.getId());
        if (taskStatusService.isFullyEditable(task)) {
            fillContent(task, request, user.getSchoolId());
            taskMapper.updateById(task);
            return detail(task.getId());
        }
        taskStatusService.assertLocationEditable(task);
        assertCoreFieldsUnchanged(task, request);
        if (taskStatusService.isLocationLocked(task)) {
            assertLocationUnchanged(task, request);
        }
        List<String> changes = describeLocationChanges(task, request);
        applyLocationAndRemark(task, request, user.getSchoolId());
        taskMapper.updateById(task);
        notifyTaskUpdated(task, changes);
        return detail(task.getId());
    }

    @Override
    @Transactional
    public void delete(Long id) {
        SysUser user = requirePublisher();
        SubstituteTask task = requireOwnedTask(id, user.getId());
        taskStatusService.assertEditable(task);
        taskMapper.deleteById(task.getId());
    }

    @Override
    @Transactional
    public TaskVO confirm(Long id, String objectKey) {
        Long userId = SecurityUtils.currentUserId();
        SubstituteTask task = requireVisibleTask(id);
        if (Objects.equals(task.getPublisherId(), userId)) {
            throw new BizException(ErrorCode.TASK_STATUS_INVALID, "发布者无需确认履约，请下课后再确认完成");
        }
        if (!isAcceptedApplicant(task, userId)) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        String key = FileUrls.objectKey(objectKey);
        if (key == null || !key.startsWith("fulfill/" + userId + "/")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "履约照片无效");
        }
        if (!storageService.exists(key)) {
            throw new BizException(ErrorCode.FILE_NOT_FOUND, "履约照片未上传成功，请重新上传");
        }
        taskStatusService.assertCanUploadFulfillPhoto(task);
        String before = task.getStatus();
        task.setFulfillPhotoKey(key);
        taskStatusService.onApplicantConfirm(task);
        taskMapper.updateById(task);
        notifyFulfillment(task, before);
        return detail(id);
    }

    @Override
    @Transactional
    public TaskVO complete(Long id) {
        Long userId = SecurityUtils.currentUserId();
        SubstituteTask task = requireVisibleTask(id);
        if (Objects.equals(task.getPublisherId(), userId)) {
            taskStatusService.onPublisherComplete(task);
        } else if (isAcceptedApplicant(task, userId)) {
            taskStatusService.onApplicantComplete(task);
        } else {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        taskMapper.updateById(task);
        if (TaskStatus.COMPLETED.name().equals(task.getStatus())) {
            Long applicantId = acceptedApplicantId(task);
            bumpCompleted(applicantId);
            bumpPublishCompleted(task.getPublisherId());
            notifyCompletedForReview(task, userId);
        }
        return detail(id);
    }

    @Override
    @Transactional
    public TaskVO cancel(Long id, CancelTaskRequest request) {
        Long userId = SecurityUtils.currentUserId();
        SubstituteTask task = requireVisibleTask(id);
        boolean publisher = Objects.equals(task.getPublisherId(), userId);
        boolean acceptedApplicant = isAcceptedApplicant(task, userId);
        String reason = request == null ? null : request.reason();
        taskStatusService.cancelByUser(task, trimToNull(reason), publisher, acceptedApplicant);
        taskMapper.updateById(task);
        SysUser user = sysUserMapper.selectById(userId);
        if (user != null) {
            int cancelled = user.getCancelledCount() == null ? 0 : user.getCancelledCount();
            user.setCancelledCount(cancelled + 1);
            sysUserMapper.updateById(user);
        }
        notifyCancelled(task, publisher, trimToNull(reason));
        return detail(id);
    }

    @Override
    public PageResult<TaskVO> listMyPublished(Long page, Long size, Boolean excludeCancelled) {
        Long userId = SecurityUtils.currentUserId();
        LambdaQueryWrapper<SubstituteTask> wrapper = new LambdaQueryWrapper<SubstituteTask>()
                .eq(SubstituteTask::getPublisherId, userId);
        if (Boolean.TRUE.equals(excludeCancelled)) {
            wrapper.ne(SubstituteTask::getStatus, TaskStatus.CANCELLED.name());
        }
        return pageTasks(wrapper.orderByDesc(SubstituteTask::getCreatedAt), page, size);
    }

    @Override
    public PageResult<TaskVO> listMyApplied(Long page, Long size, Boolean excludeCancelled) {
        Long userId = SecurityUtils.currentUserId();
        LambdaQueryWrapper<SubstituteApplication> appWrapper = new LambdaQueryWrapper<SubstituteApplication>()
                .eq(SubstituteApplication::getApplicantId, userId);
        if (Boolean.TRUE.equals(excludeCancelled)) {
            appWrapper.in(SubstituteApplication::getStatus,
                    ApplicationStatus.PENDING.name(),
                    ApplicationStatus.ACCEPTED.name(),
                    ApplicationStatus.REJECTED.name());
        } else {
            appWrapper.in(SubstituteApplication::getStatus,
                    ApplicationStatus.PENDING.name(),
                    ApplicationStatus.ACCEPTED.name(),
                    ApplicationStatus.REJECTED.name(),
                    ApplicationStatus.CANCELLED.name());
        }
        List<SubstituteApplication> applications = applicationMapper.selectList(
                appWrapper.orderByDesc(SubstituteApplication::getCreatedAt)
        );
        List<Long> taskIds = applications.stream().map(SubstituteApplication::getTaskId).distinct().toList();
        if (taskIds.isEmpty()) {
            long pageNo = page == null || page < 1 ? DEFAULT_PAGE : page;
            long pageSize = size == null || size < 1 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
            return new PageResult<>(List.of(), 0, pageNo, pageSize);
        }
        return pageTasks(new LambdaQueryWrapper<SubstituteTask>()
                .in(SubstituteTask::getId, taskIds)
                .orderByDesc(SubstituteTask::getCreatedAt), page, size);
    }

    private void fillContent(SubstituteTask task, CreateTaskRequest request, Long schoolId) {
        Campus campus = campusMapper.selectById(request.campusId());
        if (campus == null || !Objects.equals(campus.getSchoolId(), schoolId) || campus.getStatus() == null || campus.getStatus() != 1) {
            throw new BizException(ErrorCode.SCHOOL_INVALID);
        }
        LocalDateTime startAt = LocalDateTime.of(request.taskDate(), request.startTime());
        LocalDateTime endAt = LocalDateTime.of(request.taskDate(), request.endTime());
        if (!endAt.isAfter(startAt)) {
            throw new BizException(ErrorCode.TIME_INVALID, "结束时间必须晚于开始时间");
        }
        if (!startAt.isAfter(LocalDateTime.now())) {
            throw new BizException(ErrorCode.TIME_INVALID, "上课时间不能早于当前时间");
        }
        task.setCourseId(null);
        task.setCourseNameSnapshot(request.courseName().trim());
        task.setTaskDate(request.taskDate());
        task.setStartTime(request.startTime());
        task.setEndTime(request.endTime());
        task.setStartAt(startAt);
        task.setEndAt(endAt);
        task.setCampusId(campus.getId());
        task.setBuilding(request.building().trim());
        task.setClassroom(request.classroom().trim());
        task.setComputerLab(Boolean.TRUE.equals(request.computerLab()) ? 1 : 0);
        task.setRequirePhoto(Boolean.TRUE.equals(request.requirePhoto()) ? 1 : 0);
        task.setGenderRequirement(request.genderRequirement());
        task.setReward(request.reward());
        task.setReason(trimToNull(request.reason()));
        task.setRequirement(trimToNull(request.requirement()));
        task.setRemark(trimToNull(request.remark()));
    }

    private void assertCoreFieldsUnchanged(SubstituteTask task, CreateTaskRequest request) {
        if (!Objects.equals(task.getCourseNameSnapshot(), request.courseName().trim())
                || !Objects.equals(task.getTaskDate(), request.taskDate())
                || !Objects.equals(minuteOf(task.getStartTime()), minuteOf(request.startTime()))
                || !Objects.equals(minuteOf(task.getEndTime()), minuteOf(request.endTime()))
                || !Objects.equals(task.getGenderRequirement(), request.genderRequirement())
                || photoFlag(task.getRequirePhoto()) != (Boolean.TRUE.equals(request.requirePhoto()) ? 1 : 0)) {
            throw new BizException(ErrorCode.TASK_NOT_EDITABLE, "有人申请或匹配后只能修改地点、备注和酬谢");
        }
    }

    private void assertLocationUnchanged(SubstituteTask task, CreateTaskRequest request) {
        String building = request.building() == null ? "" : request.building().trim();
        String classroom = request.classroom() == null ? "" : request.classroom().trim();
        int lab = Boolean.TRUE.equals(request.computerLab()) ? 1 : 0;
        int oldLab = task.getComputerLab() == null ? 0 : task.getComputerLab();
        if (!Objects.equals(task.getCampusId(), request.campusId())
                || !Objects.equals(nullToEmpty(task.getBuilding()), building)
                || !Objects.equals(nullToEmpty(task.getClassroom()), classroom)
                || oldLab != lab) {
            throw new BizException(ErrorCode.TASK_NOT_EDITABLE, "已有人接代课，开课前 1 小时内不能修改地点");
        }
    }

    private void applyLocationAndRemark(SubstituteTask task, CreateTaskRequest request, Long schoolId) {
        Campus campus = campusMapper.selectById(request.campusId());
        if (campus == null || !Objects.equals(campus.getSchoolId(), schoolId)
                || campus.getStatus() == null || campus.getStatus() != 1) {
            throw new BizException(ErrorCode.SCHOOL_INVALID);
        }
        task.setCampusId(campus.getId());
        task.setBuilding(request.building().trim());
        task.setClassroom(request.classroom().trim());
        task.setComputerLab(Boolean.TRUE.equals(request.computerLab()) ? 1 : 0);
        task.setReward(request.reward());
        task.setReason(trimToNull(request.reason()));
        task.setRequirement(trimToNull(request.requirement()));
        task.setRemark(trimToNull(request.remark()));
    }

    private void notifyTaskUpdated(SubstituteTask task, List<String> changes) {
        String course = task.getCourseNameSnapshot() == null ? "代课任务" : task.getCourseNameSnapshot();
        String publisher = displayName(task.getPublisherId(), "发布者");
        String detail = changes == null || changes.isEmpty()
                ? "地点或备注已更新，请查看最新详情。"
                : "变更如下：" + String.join("；", changes);
        List<SubstituteApplication> applicants = applicationMapper.selectList(
                new LambdaQueryWrapper<SubstituteApplication>()
                        .eq(SubstituteApplication::getTaskId, task.getId())
                        .in(SubstituteApplication::getStatus,
                                ApplicationStatus.ACCEPTED.name(),
                                ApplicationStatus.PENDING.name())
        );
        for (SubstituteApplication application : applicants) {
            notificationService.notifyUser(
                    application.getApplicantId(),
                    "TASK",
                    "代课信息已更新",
                    "发布者 " + publisher + " 修改了代课「" + course + "」。" + detail,
                    "TASK",
                    task.getId()
            );
        }
    }

    private List<String> describeLocationChanges(SubstituteTask task, CreateTaskRequest request) {
        List<String> changes = new ArrayList<>();
        if (!Objects.equals(task.getCampusId(), request.campusId())) {
            changes.add("校区：" + campusName(task.getCampusId()) + " → " + campusName(request.campusId()));
        }
        String newBuilding = request.building() == null ? "" : request.building().trim();
        if (!Objects.equals(nullToEmpty(task.getBuilding()), newBuilding)) {
            changes.add("教学楼：" + displayText(task.getBuilding()) + " → " + displayText(newBuilding));
        }
        String newClassroom = request.classroom() == null ? "" : request.classroom().trim();
        if (!Objects.equals(nullToEmpty(task.getClassroom()), newClassroom)) {
            changes.add("教室：" + displayText(task.getClassroom()) + " → " + displayText(newClassroom));
        }
        int newLab = Boolean.TRUE.equals(request.computerLab()) ? 1 : 0;
        int oldLab = task.getComputerLab() == null ? 0 : task.getComputerLab();
        if (oldLab != newLab) {
            changes.add("上机：" + (oldLab == 1 ? "是" : "否") + " → " + (newLab == 1 ? "是" : "否"));
        }
        if (task.getReward() == null || request.reward() == null || task.getReward().compareTo(request.reward()) != 0) {
            changes.add("酬谢：" + displayText(task.getReward() == null ? null : task.getReward().stripTrailingZeros().toPlainString())
                    + " → " + displayText(request.reward() == null ? null : request.reward().stripTrailingZeros().toPlainString()));
        }
        if (!Objects.equals(nullToEmpty(task.getReason()), nullToEmpty(trimToNull(request.reason())))) {
            changes.add("原因：" + displayText(task.getReason()) + " → " + displayText(trimToNull(request.reason())));
        }
        if (!Objects.equals(nullToEmpty(task.getRequirement()), nullToEmpty(trimToNull(request.requirement())))) {
            changes.add("要求：" + displayText(task.getRequirement()) + " → " + displayText(trimToNull(request.requirement())));
        }
        if (!Objects.equals(nullToEmpty(task.getRemark()), nullToEmpty(trimToNull(request.remark())))) {
            changes.add("备注：" + displayText(task.getRemark()) + " → " + displayText(trimToNull(request.remark())));
        }
        return changes;
    }

    private String campusName(Long campusId) {
        if (campusId == null) {
            return "未填写";
        }
        Campus campus = campusMapper.selectById(campusId);
        if (campus == null || !StringUtils.hasText(campus.getName())) {
            return "未填写";
        }
        return campus.getName();
    }

    private String displayText(String value) {
        return StringUtils.hasText(value) ? value : "未填写";
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private LocalTime minuteOf(LocalTime time) {
        return time == null ? null : time.truncatedTo(ChronoUnit.MINUTES);
    }

    private SysUser requirePublisher() {
        SysUser user = sysUserMapper.selectById(SecurityUtils.currentUserId());
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        if (UserStatus.BANNED.name().equals(user.getStatus())) {
            throw new BizException(ErrorCode.ACCOUNT_BANNED);
        }
        if (UserRestrictions.forbidPublish(user)) {
            throw new BizException(ErrorCode.FORBID_PUBLISH);
        }
        return user;
    }

    private SubstituteTask requireOwnedTask(Long id, Long userId) {
        SubstituteTask task = taskMapper.selectById(id);
        if (task == null) {
            throw new BizException(ErrorCode.TASK_NOT_FOUND);
        }
        if (!Objects.equals(task.getPublisherId(), userId)) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        return task;
    }

    private SubstituteTask requireVisibleTask(Long id) {
        SubstituteTask task = taskMapper.selectById(id);
        if (task == null) {
            throw new BizException(ErrorCode.TASK_NOT_FOUND);
        }
        Long schoolId = resolveViewerSchoolId(null);
        if (Objects.equals(task.getSchoolId(), schoolId)) {
            return task;
        }
        LoginUser loginUser = SecurityUtils.currentUserOrNull();
        if (loginUser != null && Objects.equals(task.getPublisherId(), loginUser.userId())) {
            return task;
        }
        if (loginUser != null) {
            Long mine = applicationMapper.selectCount(new LambdaQueryWrapper<SubstituteApplication>()
                    .eq(SubstituteApplication::getTaskId, task.getId())
                    .eq(SubstituteApplication::getApplicantId, loginUser.userId()));
            if (mine != null && mine > 0) {
                return task;
            }
        }
        throw new BizException(ErrorCode.TASK_NOT_FOUND);
    }

    private Long resolveViewerSchoolId(Long querySchoolId) {
        LoginUser loginUser = SecurityUtils.currentUserOrNull();
        if (loginUser != null) {
            SysUser user = sysUserMapper.selectById(loginUser.userId());
            if (user != null && user.getSchoolId() != null) {
                return user.getSchoolId();
            }
        }
        return querySchoolId == null ? DEFAULT_SCHOOL_ID : querySchoolId;
    }

    private Map<Long, String> loadCampusNames(List<SubstituteTask> tasks) {
        Set<Long> ids = tasks.stream().map(SubstituteTask::getCampusId).filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        campusMapper.selectByIds(ids).forEach(campus -> names.put(campus.getId(), campus.getName()));
        return names;
    }

    private LocalDate parseDate(String raw) {
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException ex) {
            throw new BizException(ErrorCode.BAD_REQUEST, "日期格式应为 yyyy-MM-dd");
        }
    }

    private LocalTime[] parseTimeSlot(String raw) {
        if (!StringUtils.hasText(raw) || "ALL".equalsIgnoreCase(raw.trim())) {
            return null;
        }
        return switch (raw.trim()) {
            case "6-8" -> new LocalTime[] { LocalTime.of(6, 0), LocalTime.of(8, 0) };
            case "8-10" -> new LocalTime[] { LocalTime.of(8, 0), LocalTime.of(10, 0) };
            case "10-12" -> new LocalTime[] { LocalTime.of(10, 0), LocalTime.of(12, 0) };
            case "13-15" -> new LocalTime[] { LocalTime.of(13, 0), LocalTime.of(15, 0) };
            case "15-17" -> new LocalTime[] { LocalTime.of(15, 0), LocalTime.of(17, 0) };
            case "17-20" -> new LocalTime[] { LocalTime.of(17, 0), LocalTime.of(20, 0) };
            case "20-22" -> new LocalTime[] { LocalTime.of(20, 0), LocalTime.of(22, 0) };
            default -> throw new BizException(ErrorCode.BAD_REQUEST, "不支持的时间段");
        };
    }

    private PageResult<TaskVO> pageTasks(LambdaQueryWrapper<SubstituteTask> wrapper, Long page, Long size) {
        long pageNo = page == null || page < 1 ? DEFAULT_PAGE : page;
        long pageSize = size == null || size < 1 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        Page<SubstituteTask> result = taskMapper.selectPage(new Page<>(pageNo, pageSize), wrapper);
        Map<Long, String> campusNames = loadCampusNames(result.getRecords());
        LoginUser loginUser = SecurityUtils.currentUserOrNull();
        Set<Long> favs = favoriteIds(result.getRecords());
        Map<Long, SubstituteApplication> mineApps = loadMineApplications(loginUser, result.getRecords());
        Map<Long, Long> acceptedApplicantIds = loadAcceptedApplicantIds(result.getRecords());
        Map<Long, String> displayNames = loadDisplayNames(result.getRecords(), acceptedApplicantIds);
        List<TaskVO> list = result.getRecords().stream().map(task -> {
            boolean mine = loginUser != null && Objects.equals(loginUser.userId(), task.getPublisherId());
            Long applicantId = acceptedApplicantIds.get(task.getId());
            boolean matched = loginUser != null && applicantId != null && Objects.equals(applicantId, loginUser.userId());
            SubstituteApplication mineApp = mineApps.get(task.getId());
            String myApplicationStatus = mineApp == null ? null : mineApp.getStatus();
            Long myApplicationId = mineApp == null ? null : mineApp.getId();
            return toVo(
                    task,
                    campusNames.get(task.getCampusId()),
                    nameBrief(task.getPublisherId(), displayNames.get(task.getPublisherId())),
                    null,
                    mine,
                    myApplicationStatus,
                    myApplicationId,
                    matched,
                    applicantId == null ? null : displayNames.get(applicantId),
                    applicantId,
                    false,
                    null,
                    favs.contains(task.getId())
            );
        }).toList();
        return new PageResult<>(list, result.getTotal(), pageNo, pageSize);
    }

    private Map<Long, Long> loadAcceptedApplicantIds(List<SubstituteTask> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return Map.of();
        }
        List<Long> applicationIds = tasks.stream()
                .map(SubstituteTask::getAcceptedApplicationId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (applicationIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> map = new HashMap<>();
        applicationMapper.selectByIds(applicationIds).forEach(application -> {
            if (application != null && ApplicationStatus.ACCEPTED.name().equals(application.getStatus())) {
                map.put(application.getTaskId(), application.getApplicantId());
            }
        });
        return map;
    }

    private Map<Long, String> loadDisplayNames(List<SubstituteTask> tasks, Map<Long, Long> acceptedApplicantIds) {
        Set<Long> userIds = new java.util.HashSet<>();
        for (SubstituteTask task : tasks) {
            if (task.getPublisherId() != null) {
                userIds.add(task.getPublisherId());
            }
            Long applicantId = acceptedApplicantIds.get(task.getId());
            if (applicantId != null) {
                userIds.add(applicantId);
            }
        }
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        sysUserMapper.selectByIds(userIds).forEach(user -> names.put(user.getId(), displayNameOf(user)));
        return names;
    }

    private PublisherBriefVO nameBrief(Long userId, String name) {
        if (userId == null || !StringUtils.hasText(name)) {
            return null;
        }
        return new PublisherBriefVO(userId, name, null, null, null, null, 0, null, 0, 0, 0, null, 0, 0, 0);
    }

    private String displayNameOf(SysUser user) {
        if (user == null) {
            return null;
        }
        if (StringUtils.hasText(user.getNickname())) {
            return user.getNickname();
        }
        if (StringUtils.hasText(user.getUsername())) {
            return user.getUsername();
        }
        return null;
    }

    private Map<Long, SubstituteApplication> loadMineApplications(LoginUser loginUser, List<SubstituteTask> tasks) {
        if (loginUser == null || tasks == null || tasks.isEmpty()) {
            return Map.of();
        }
        List<Long> taskIds = tasks.stream().map(SubstituteTask::getId).toList();
        List<SubstituteApplication> rows = applicationMapper.selectList(new LambdaQueryWrapper<SubstituteApplication>()
                .eq(SubstituteApplication::getApplicantId, loginUser.userId())
                .in(SubstituteApplication::getTaskId, taskIds)
                .orderByDesc(SubstituteApplication::getCreatedAt));
        Map<Long, SubstituteApplication> map = new HashMap<>();
        for (SubstituteApplication row : rows) {
            map.putIfAbsent(row.getTaskId(), row);
        }
        return map;
    }

    private boolean isAcceptedApplicant(SubstituteTask task, Long userId) {
        Long applicantId = acceptedApplicantId(task);
        return applicantId != null && Objects.equals(applicantId, userId);
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

    private void notifyFulfillment(SubstituteTask task, String beforeStatus) {
        Long applicantId = acceptedApplicantId(task);
        String course = task.getCourseNameSnapshot() == null ? "代课任务" : task.getCourseNameSnapshot();
        if (!TaskStatus.MATCHED.name().equals(beforeStatus)
                && !TaskStatus.CONFIRMED.name().equals(beforeStatus)
                && !TaskStatus.IN_PROGRESS.name().equals(beforeStatus)) {
            return;
        }
        String applicant = displayName(applicantId, "代课者");
        notificationService.notifyUser(
                task.getPublisherId(),
                "TASK",
                "代课者已上传照片",
                "代课「" + course + "」代课者 " + applicant + " 已上传现场照片，下课后再确认完成。",
                "TASK",
                task.getId()
        );
        if (applicantId != null) {
            notificationService.notifyUser(
                    applicantId,
                    "TASK",
                    "你已上传照片",
                    "代课「" + course + "」你已上传现场照片，下课后再确认完成。",
                    "TASK",
                    task.getId()
            );
        }
    }

    private void notifyCancelled(SubstituteTask task, boolean cancelledByPublisher, String reason) {
        String course = task.getCourseNameSnapshot() == null ? "代课任务" : task.getCourseNameSnapshot();
        String extra = StringUtils.hasText(reason) ? " 原因：" + reason : "";
        if (cancelledByPublisher) {
            String publisher = displayName(task.getPublisherId(), "发布者");
            List<SubstituteApplication> applicants = applicationMapper.selectList(
                    new LambdaQueryWrapper<SubstituteApplication>()
                            .eq(SubstituteApplication::getTaskId, task.getId())
                            .in(SubstituteApplication::getStatus,
                                    ApplicationStatus.ACCEPTED.name(),
                                    ApplicationStatus.PENDING.name())
            );
            for (SubstituteApplication application : applicants) {
                notificationService.notifyUser(
                        application.getApplicantId(),
                        "TASK",
                        "发布者已取消代课",
                        "代课「" + course + "」已被发布者 " + publisher + " 取消。" + extra,
                        "TASK",
                        task.getId()
                );
            }
            return;
        }
        String applicant = displayName(acceptedApplicantId(task), "代课者");
        notificationService.notifyUser(
                task.getPublisherId(),
                "TASK",
                "代课者已取消代课",
                "代课「" + course + "」已被代课者 " + applicant + " 取消。" + extra,
                "TASK",
                task.getId()
        );
    }

    private void notifyCompletedForReview(SubstituteTask task, Long actorId) {
        String course = task.getCourseNameSnapshot() == null ? "代课任务" : task.getCourseNameSnapshot();
        Long applicantId = acceptedApplicantId(task);
        boolean byPublisher = Objects.equals(actorId, task.getPublisherId());
        Long peerId = byPublisher ? applicantId : task.getPublisherId();
        if (peerId == null) {
            return;
        }
        String actorName = byPublisher
                ? "发布者 " + displayName(task.getPublisherId(), "发布者")
                : "代课者 " + displayName(applicantId, "代课者");
        notificationService.notifyUser(
                peerId,
                "TASK",
                "代课已完成",
                "代课「" + course + "」" + actorName + " 已确认完成，请为对方打星。",
                "REVIEW",
                task.getId()
        );
    }

    private String displayName(Long userId, String fallback) {
        if (userId == null) {
            return fallback;
        }
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            return fallback;
        }
        if (StringUtils.hasText(user.getNickname())) {
            return user.getNickname();
        }
        if (StringUtils.hasText(user.getUsername())) {
            return user.getUsername();
        }
        return fallback;
    }

    private PublisherBriefVO toUserBrief(SysUser user, TrustRole role) {
        if (user == null) {
            return null;
        }
        String schoolName = null;
        String campusName = null;
        if (user.getSchoolId() != null) {
            School school = schoolMapper.selectById(user.getSchoolId());
            if (school != null) {
                schoolName = school.getName();
            }
        }
        if (user.getCampusId() != null) {
            Campus userCampus = campusMapper.selectById(user.getCampusId());
            if (userCampus != null) {
                campusName = userCampus.getName();
            }
        }
        boolean publisher = role == TrustRole.PUBLISHER;
        Integer completed = publisher
                ? (user.getPublishCompletedCount() == null ? 0 : user.getPublishCompletedCount())
                : (user.getCompletedCount() == null ? 0 : user.getCompletedCount());
        BigDecimal ratingAvg = publisher ? user.getPublishRatingAvg() : user.getApplyRatingAvg();
        Integer ratingCount = publisher
                ? (user.getPublishRatingCount() == null ? 0 : user.getPublishRatingCount())
                : (user.getApplyRatingCount() == null ? 0 : user.getApplyRatingCount());
        return new PublisherBriefVO(
                user.getId(),
                user.getNickname(),
                FileUrls.of(user.getAvatarUrl()),
                user.getGender(),
                schoolName,
                campusName,
                completed,
                ratingAvg,
                ratingCount,
                user.getCancelledCount(),
                user.getReportedCount(),
                user.getStatus(),
                UserRestrictions.flag(user.getForbidPublish(), user.getForbidPublishUntil()),
                UserRestrictions.flag(user.getForbidApply(), user.getForbidApplyUntil()),
                UserRestrictions.flag(user.getMuted(), user.getMutedUntil())
        );
    }

    private void bumpCompleted(Long userId) {
        if (userId == null) {
            return;
        }
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            return;
        }
        int completed = user.getCompletedCount() == null ? 0 : user.getCompletedCount();
        user.setCompletedCount(completed + 1);
        sysUserMapper.updateById(user);
    }

    private void bumpPublishCompleted(Long userId) {
        if (userId == null) {
            return;
        }
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            return;
        }
        int completed = user.getPublishCompletedCount() == null ? 0 : user.getPublishCompletedCount();
        user.setPublishCompletedCount(completed + 1);
        sysUserMapper.updateById(user);
    }

    private TaskVO toVo(
            SubstituteTask task,
            String campusName,
            PublisherBriefVO publisher,
            PublisherBriefVO applicant,
            boolean mine,
            String myApplicationStatus,
            Long myApplicationId,
            boolean matchedApplicant,
            String matchedApplicantNickname,
            Long matchedApplicantId,
            boolean canReview,
            Integer myReviewRating,
            boolean favorited
    ) {
        return new TaskVO(
                task.getId(),
                task.getPublisherId(),
                task.getCourseId(),
                task.getCourseNameSnapshot(),
                task.getTaskDate(),
                task.getStartTime(),
                task.getEndTime(),
                task.getStartAt(),
                task.getEndAt(),
                task.getSchoolId(),
                task.getCampusId(),
                campusName,
                task.getBuilding(),
                task.getClassroom(),
                task.getComputerLab(),
                task.getRequirePhoto(),
                task.getGenderRequirement(),
                task.getReward(),
                task.getReason(),
                task.getRequirement(),
                task.getRemark(),
                task.getStatus(),
                task.getApplyCount(),
                task.getCreatedAt(),
                publisher,
                applicant,
                mine,
                task.getPublisherConfirmed(),
                task.getApplicantConfirmed(),
                task.getPublisherCompleted(),
                task.getApplicantCompleted(),
                task.getAcceptedApplicationId(),
                myApplicationStatus,
                myApplicationId,
                matchedApplicant,
                matchedApplicantNickname,
                matchedApplicantId,
                canReview,
                myReviewRating,
                favorited,
                FileUrls.of(task.getFulfillPhotoKey())
        );
    }

    private Set<Long> favoriteIds(List<SubstituteTask> tasks) {
        LoginUser loginUser = SecurityUtils.currentUserOrNull();
        if (loginUser == null || tasks == null || tasks.isEmpty()) {
            return Set.of();
        }
        return favoriteService.taskIdsOf(loginUser.userId(), tasks.stream().map(SubstituteTask::getId).toList());
    }

    private int photoFlag(Integer value) {
        return value != null && value == 1 ? 1 : 0;
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }
}
