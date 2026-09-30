package com.kean.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.kean.common.ErrorCode;
import com.kean.dto.ApplyRequest;
import com.kean.entity.SubstituteApplication;
import com.kean.entity.SubstituteTask;
import com.kean.entity.SysUser;
import com.kean.enums.ApplicationStatus;
import com.kean.enums.TaskStatus;
import com.kean.enums.UserStatus;
import com.kean.exception.BizException;
import com.kean.mapper.SubstituteApplicationMapper;
import com.kean.mapper.SubstituteTaskMapper;
import com.kean.mapper.SysUserMapper;
import com.kean.security.SecurityUtils;
import com.kean.service.ApplicationService;
import com.kean.service.BlacklistService;
import com.kean.service.NotificationService;
import com.kean.service.TaskService;
import com.kean.service.TaskStatusService;
import com.kean.utils.UserRestrictions;
import com.kean.vo.ApplicationVO;
import com.kean.vo.TaskVO;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ApplicationServiceImpl implements ApplicationService {

    private final SubstituteApplicationMapper applicationMapper;
    private final SubstituteTaskMapper taskMapper;
    private final SysUserMapper sysUserMapper;
    private final TaskStatusService taskStatusService;
    private final TaskService taskService;
    private final NotificationService notificationService;
    private final BlacklistService blacklistService;

    public ApplicationServiceImpl(
            SubstituteApplicationMapper applicationMapper,
            SubstituteTaskMapper taskMapper,
            SysUserMapper sysUserMapper,
            TaskStatusService taskStatusService,
            TaskService taskService,
            NotificationService notificationService,
            BlacklistService blacklistService
    ) {
        this.applicationMapper = applicationMapper;
        this.taskMapper = taskMapper;
        this.sysUserMapper = sysUserMapper;
        this.taskStatusService = taskStatusService;
        this.taskService = taskService;
        this.notificationService = notificationService;
        this.blacklistService = blacklistService;
    }

    @Override
    @Transactional
    public TaskVO apply(Long taskId, ApplyRequest request) {
        SysUser user = requireApplicant();
        SubstituteTask task = requireTask(taskId);
        if (Objects.equals(task.getPublisherId(), user.getId())) {
            throw new BizException(ErrorCode.CANNOT_APPLY_OWN);
        }
        blacklistService.assertCanInteract(user.getId(), task.getPublisherId());
        if (!Objects.equals(task.getSchoolId(), user.getSchoolId())) {
            throw new BizException(ErrorCode.TASK_NOT_FOUND);
        }
        taskStatusService.assertCanApply(task);
        assertGenderMatch(task, user);
        assertNoTimeConflict(user.getId(), task);
        String message = trimToNull(request == null ? null : request.message());
        SubstituteApplication existing = applicationMapper.selectOne(new LambdaQueryWrapper<SubstituteApplication>()
                .eq(SubstituteApplication::getTaskId, taskId)
                .eq(SubstituteApplication::getApplicantId, user.getId()));
        if (existing != null) {
            if (!ApplicationStatus.CANCELLED.name().equals(existing.getStatus())) {
                throw new BizException(ErrorCode.ALREADY_APPLIED);
            }
            existing.setMessage(message);
            existing.setStatus(ApplicationStatus.PENDING.name());
            applicationMapper.updateById(existing);
        } else {
            SubstituteApplication application = new SubstituteApplication();
            application.setTaskId(taskId);
            application.setApplicantId(user.getId());
            application.setMessage(message);
            application.setStatus(ApplicationStatus.PENDING.name());
            applicationMapper.insert(application);
        }
        int count = task.getApplyCount() == null ? 0 : task.getApplyCount();
        task.setApplyCount(count + 1);
        taskStatusService.onApplicationCreated(task);
        taskMapper.updateById(task);
        notificationService.notifyUser(
                task.getPublisherId(),
                "APPLICATION",
                "有人申请了你的代课",
                nicknameOf(user) + " 申请了「" + courseName(task) + "」，请在消息中查看并处理。",
                "TASK",
                task.getId()
        );
        return taskService.detail(taskId);
    }

    @Override
    public List<ApplicationVO> listByTask(Long taskId) {
        Long userId = SecurityUtils.currentUserId();
        SubstituteTask task = requireTask(taskId);
        if (!Objects.equals(task.getPublisherId(), userId)) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        List<SubstituteApplication> applications = applicationMapper.selectList(
                new LambdaQueryWrapper<SubstituteApplication>()
                        .eq(SubstituteApplication::getTaskId, taskId)
                        .orderByAsc(SubstituteApplication::getCreatedAt)
        );
        return toVos(applications);
    }

    @Override
    @Transactional
    public TaskVO accept(Long applicationId) {
        Long userId = SecurityUtils.currentUserId();
        SubstituteApplication application = requireApplication(applicationId);
        SubstituteTask task = lockTask(application.getTaskId());
        if (!Objects.equals(task.getPublisherId(), userId)) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        application = requireApplication(applicationId);
        if (isMatchedTo(task, application)) {
            return taskService.detail(task.getId());
        }
        if (task.getAcceptedApplicationId() != null
                || TaskStatus.MATCHED.name().equals(task.getStatus())
                || TaskStatus.CONFIRMED.name().equals(task.getStatus())
                || TaskStatus.IN_PROGRESS.name().equals(task.getStatus())
                || TaskStatus.COMPLETED.name().equals(task.getStatus())) {
            throw new BizException(ErrorCode.TASK_ALREADY_MATCHED);
        }
        if (!ApplicationStatus.PENDING.name().equals(application.getStatus())) {
            throw new BizException(ErrorCode.TASK_STATUS_INVALID, "只能接受待处理申请");
        }
        assertNoTimeConflict(application.getApplicantId(), task);
        int claimed = taskMapper.update(null, new LambdaUpdateWrapper<SubstituteTask>()
                .eq(SubstituteTask::getId, task.getId())
                .eq(SubstituteTask::getStatus, TaskStatus.APPLYING.name())
                .isNull(SubstituteTask::getAcceptedApplicationId)
                .set(SubstituteTask::getStatus, TaskStatus.MATCHED.name())
                .set(SubstituteTask::getAcceptedApplicationId, application.getId()));
        if (claimed != 1) {
            throw new BizException(ErrorCode.TASK_ALREADY_MATCHED);
        }
        application.setStatus(ApplicationStatus.ACCEPTED.name());
        try {
            applicationMapper.updateById(application);
        } catch (DataIntegrityViolationException ex) {
            throw new BizException(ErrorCode.TASK_ALREADY_MATCHED);
        }
        List<SubstituteApplication> pendingOthers = applicationMapper.selectList(new LambdaQueryWrapper<SubstituteApplication>()
                .eq(SubstituteApplication::getTaskId, task.getId())
                .eq(SubstituteApplication::getStatus, ApplicationStatus.PENDING.name())
                .ne(SubstituteApplication::getId, application.getId()));
        applicationMapper.update(null, new LambdaUpdateWrapper<SubstituteApplication>()
                .eq(SubstituteApplication::getTaskId, task.getId())
                .eq(SubstituteApplication::getStatus, ApplicationStatus.PENDING.name())
                .ne(SubstituteApplication::getId, application.getId())
                .set(SubstituteApplication::getStatus, ApplicationStatus.REJECTED.name()));
        String acceptBody = taskStatusService.requirePhoto(task)
                ? "你申请的代课「" + courseName(task) + "」已被发布者接受，请在开课前 5 分钟至下课前上传现场照片。"
                : "你申请的代课「" + courseName(task) + "」已被发布者接受，下课后可直接确认完成。";
        notificationService.notifyUser(
                application.getApplicantId(),
                "TASK",
                "申请已被接受",
                acceptBody,
                "TASK",
                task.getId()
        );
        for (SubstituteApplication item : pendingOthers) {
            notificationService.notifyUser(
                    item.getApplicantId(),
                    "TASK",
                    "申请未被选中",
                    "你申请的代课「" + courseName(task) + "」发布者已选择其他人。",
                    "TASK",
                    task.getId()
            );
        }
        return taskService.detail(task.getId());
    }

    @Override
    @Transactional
    public TaskVO reject(Long applicationId) {
        Long userId = SecurityUtils.currentUserId();
        SubstituteApplication application = requireApplication(applicationId);
        SubstituteTask task = lockTask(application.getTaskId());
        if (!Objects.equals(task.getPublisherId(), userId)) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        application = requireApplication(applicationId);
        if (!ApplicationStatus.PENDING.name().equals(application.getStatus())) {
            throw new BizException(ErrorCode.TASK_STATUS_INVALID, "只能拒绝待处理申请");
        }
        application.setStatus(ApplicationStatus.REJECTED.name());
        applicationMapper.updateById(application);
        if (countPending(task.getId()) == 0) {
            taskStatusService.onNoPendingApplications(task);
            taskMapper.updateById(task);
        }
        notificationService.notifyUser(
                application.getApplicantId(),
                "TASK",
                "申请已被拒绝",
                "你申请的代课「" + courseName(task) + "」未被接受。",
                "TASK",
                task.getId()
        );
        return taskService.detail(task.getId());
    }

    @Override
    @Transactional
    public TaskVO withdraw(Long applicationId) {
        Long userId = SecurityUtils.currentUserId();
        SubstituteApplication application = requireApplication(applicationId);
        if (!Objects.equals(application.getApplicantId(), userId)) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        if (!ApplicationStatus.PENDING.name().equals(application.getStatus())) {
            throw new BizException(ErrorCode.TASK_STATUS_INVALID, "只能撤回待处理申请");
        }
        SubstituteTask task = requireTask(application.getTaskId());
        application.setStatus(ApplicationStatus.CANCELLED.name());
        applicationMapper.updateById(application);
        int count = task.getApplyCount() == null ? 0 : task.getApplyCount();
        task.setApplyCount(Math.max(0, count - 1));
        if (countPending(task.getId()) == 0) {
            taskStatusService.onNoPendingApplications(task);
        }
        taskMapper.updateById(task);
        SysUser applicant = sysUserMapper.selectById(userId);
        String name = applicant == null ? "同学" : nicknameOf(applicant);
        notificationService.notifyUser(
                task.getPublisherId(),
                "APPLICATION",
                "有人撤回了申请",
                name + " 撤回了对「" + courseName(task) + "」的申请。",
                "TASK",
                task.getId()
        );
        return taskService.detail(task.getId());
    }

    private long countPending(Long taskId) {
        return applicationMapper.selectCount(new LambdaQueryWrapper<SubstituteApplication>()
                .eq(SubstituteApplication::getTaskId, taskId)
                .eq(SubstituteApplication::getStatus, ApplicationStatus.PENDING.name()));
    }

    private List<ApplicationVO> toVos(List<SubstituteApplication> applications) {
        Set<Long> userIds = applications.stream().map(SubstituteApplication::getApplicantId).collect(Collectors.toSet());
        Map<Long, SysUser> users = userIds.isEmpty() ? Map.of() : sysUserMapper.selectByIds(userIds).stream()
                .collect(Collectors.toMap(SysUser::getId, user -> user, (a, b) -> a));
        return applications.stream()
                .map(item -> toVo(item, users.get(item.getApplicantId())))
                .toList();
    }

    private ApplicationVO toVo(SubstituteApplication item, SysUser user) {
        String nickname = null;
        if (user != null) {
            nickname = StringUtils.hasText(user.getNickname()) ? user.getNickname() : user.getUsername();
        }
        return new ApplicationVO(
                item.getId(),
                item.getTaskId(),
                item.getApplicantId(),
                nickname,
                item.getMessage(),
                item.getStatus(),
                item.getCreatedAt(),
                user == null || user.getCompletedCount() == null ? 0 : user.getCompletedCount(),
                user == null ? null : user.getApplyRatingAvg(),
                user == null || user.getApplyRatingCount() == null ? 0 : user.getApplyRatingCount(),
                user == null || user.getCancelledCount() == null ? 0 : user.getCancelledCount(),
                user == null || user.getReportedCount() == null ? 0 : user.getReportedCount(),
                user == null ? null : user.getStatus(),
                user == null ? 0 : UserRestrictions.flag(user.getForbidPublish(), user.getForbidPublishUntil()),
                user == null ? 0 : UserRestrictions.flag(user.getForbidApply(), user.getForbidApplyUntil()),
                user == null ? 0 : UserRestrictions.flag(user.getMuted(), user.getMutedUntil())
        );
    }

    private void assertGenderMatch(SubstituteTask task, SysUser user) {
        String requirement = task.getGenderRequirement();
        if (!StringUtils.hasText(requirement) || "ANY".equals(requirement)) {
            return;
        }
        if (!requirement.equals(user.getGender())) {
            throw new BizException(ErrorCode.GENDER_NOT_MATCH);
        }
    }

    private void assertNoTimeConflict(Long userId, SubstituteTask candidate) {
        for (SubstituteTask other : busyTasksOf(userId)) {
            if (Objects.equals(other.getId(), candidate.getId())) {
                continue;
            }
            if (overlaps(candidate, other)) {
                throw new BizException(
                        ErrorCode.TIME_CONFLICT,
                        "该时段你已有代课「" + courseName(other) + "」，不能再申请或接受重叠课程"
                );
            }
        }
    }

    private List<SubstituteTask> busyTasksOf(Long userId) {
        List<SubstituteApplication> accepted = applicationMapper.selectList(new LambdaQueryWrapper<SubstituteApplication>()
                .eq(SubstituteApplication::getApplicantId, userId)
                .eq(SubstituteApplication::getStatus, ApplicationStatus.ACCEPTED.name()));
        List<Long> taskIds = accepted.stream().map(SubstituteApplication::getTaskId).distinct().toList();
        if (taskIds.isEmpty()) {
            return List.of();
        }
        return taskMapper.selectList(new LambdaQueryWrapper<SubstituteTask>()
                .in(SubstituteTask::getId, taskIds)
                .in(SubstituteTask::getStatus,
                        TaskStatus.MATCHED.name(),
                        TaskStatus.CONFIRMED.name(),
                        TaskStatus.IN_PROGRESS.name()));
    }

    private boolean overlaps(SubstituteTask a, SubstituteTask b) {
        if (a.getStartAt() == null || a.getEndAt() == null || b.getStartAt() == null || b.getEndAt() == null) {
            return false;
        }
        return a.getStartAt().isBefore(b.getEndAt()) && b.getStartAt().isBefore(a.getEndAt());
    }

    private SysUser requireApplicant() {
        SysUser user = sysUserMapper.selectById(SecurityUtils.currentUserId());
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        if (UserStatus.BANNED.name().equals(user.getStatus())) {
            throw new BizException(ErrorCode.ACCOUNT_BANNED);
        }
        if (UserRestrictions.forbidApply(user)) {
            throw new BizException(ErrorCode.FORBID_APPLY);
        }
        return user;
    }

    private SubstituteTask lockTask(Long taskId) {
        SubstituteTask task = taskMapper.selectOne(new LambdaQueryWrapper<SubstituteTask>()
                .eq(SubstituteTask::getId, taskId)
                .last("FOR UPDATE"));
        if (task == null) {
            throw new BizException(ErrorCode.TASK_NOT_FOUND);
        }
        return task;
    }

    private boolean isMatchedTo(SubstituteTask task, SubstituteApplication application) {
        return Objects.equals(task.getAcceptedApplicationId(), application.getId())
                && ApplicationStatus.ACCEPTED.name().equals(application.getStatus());
    }

    private SubstituteTask requireTask(Long taskId) {
        SubstituteTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BizException(ErrorCode.TASK_NOT_FOUND);
        }
        return task;
    }

    private SubstituteApplication requireApplication(Long id) {
        SubstituteApplication application = applicationMapper.selectById(id);
        if (application == null) {
            throw new BizException(ErrorCode.APPLICATION_NOT_FOUND);
        }
        return application;
    }

    private String courseName(SubstituteTask task) {
        return task.getCourseNameSnapshot() == null ? "代课任务" : task.getCourseNameSnapshot();
    }

    private String nicknameOf(SysUser user) {
        if (user.getNickname() != null && StringUtils.hasText(user.getNickname())) {
            return user.getNickname();
        }
        return user.getUsername();
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }
}
