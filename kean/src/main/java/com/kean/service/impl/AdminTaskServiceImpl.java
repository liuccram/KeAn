package com.kean.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kean.common.ErrorCode;
import com.kean.common.PageResult;
import com.kean.common.Pages;
import com.kean.dto.CancelTaskRequest;
import com.kean.entity.Campus;
import com.kean.entity.School;
import com.kean.entity.SubstituteApplication;
import com.kean.entity.SubstituteTask;
import com.kean.entity.SysUser;
import com.kean.enums.ApplicationStatus;
import com.kean.enums.TaskStatus;
import com.kean.exception.BizException;
import com.kean.mapper.CampusMapper;
import com.kean.mapper.SchoolMapper;
import com.kean.mapper.SubstituteApplicationMapper;
import com.kean.mapper.SubstituteTaskMapper;
import com.kean.mapper.SysUserMapper;
import com.kean.security.AdminGuard;
import com.kean.service.AdminTaskService;
import com.kean.service.NotificationService;
import com.kean.service.OperationLogService;
import com.kean.service.TaskStatusService;
import com.kean.utils.FileUrls;
import com.kean.vo.AdminApplicationVO;
import com.kean.vo.AdminTaskDetailVO;
import com.kean.vo.AdminTaskItemVO;
import com.kean.vo.AdminTimelineVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AdminTaskServiceImpl implements AdminTaskService {

    private final SubstituteTaskMapper taskMapper;
    private final SubstituteApplicationMapper applicationMapper;
    private final SysUserMapper sysUserMapper;
    private final SchoolMapper schoolMapper;
    private final CampusMapper campusMapper;
    private final TaskStatusService taskStatusService;
    private final NotificationService notificationService;
    private final OperationLogService operationLogService;

    public AdminTaskServiceImpl(
            SubstituteTaskMapper taskMapper,
            SubstituteApplicationMapper applicationMapper,
            SysUserMapper sysUserMapper,
            SchoolMapper schoolMapper,
            CampusMapper campusMapper,
            TaskStatusService taskStatusService,
            NotificationService notificationService,
            OperationLogService operationLogService
    ) {
        this.taskMapper = taskMapper;
        this.applicationMapper = applicationMapper;
        this.sysUserMapper = sysUserMapper;
        this.schoolMapper = schoolMapper;
        this.campusMapper = campusMapper;
        this.taskStatusService = taskStatusService;
        this.notificationService = notificationService;
        this.operationLogService = operationLogService;
    }

    @Override
    public PageResult<AdminTaskItemVO> list(
            String keyword,
            Long schoolId,
            Long campusId,
            String status,
            String taskDate,
            Long page,
            Long size
    ) {
        AdminGuard.require();
        long pageNo = Pages.page(page);
        long pageSize = Pages.size(size);
        LambdaQueryWrapper<SubstituteTask> wrapper = new LambdaQueryWrapper<SubstituteTask>()
                .orderByDesc(SubstituteTask::getId);
        if (schoolId != null) {
            wrapper.eq(SubstituteTask::getSchoolId, schoolId);
        }
        if (campusId != null) {
            wrapper.eq(SubstituteTask::getCampusId, campusId);
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(SubstituteTask::getStatus, status.trim().toUpperCase());
        }
        if (StringUtils.hasText(taskDate)) {
            wrapper.eq(SubstituteTask::getTaskDate, parseDate(taskDate));
        }
        if (StringUtils.hasText(keyword)) {
            String key = keyword.trim();
            wrapper.and(w -> {
                w.like(SubstituteTask::getCourseNameSnapshot, key)
                        .or().like(SubstituteTask::getBuilding, key)
                        .or().like(SubstituteTask::getClassroom, key);
                if (key.matches("\\d+")) {
                    w.or().eq(SubstituteTask::getId, Long.parseLong(key));
                }
            });
        }
        Page<SubstituteTask> result = taskMapper.selectPage(new Page<>(pageNo, pageSize), wrapper);
        return new PageResult<>(toItems(result.getRecords()), result.getTotal(), pageNo, pageSize);
    }

    @Override
    public AdminTaskDetailVO detail(Long id) {
        AdminGuard.require();
        SubstituteTask task = requireTask(id);
        AdminTaskItemVO item = toItems(List.of(task)).get(0);
        return new AdminTaskDetailVO(
                item,
                task.getReason(),
                task.getRequirement(),
                task.getRemark(),
                task.getGenderRequirement(),
                task.getComputerLab(),
                task.getRequirePhoto(),
                task.getPublisherConfirmed(),
                task.getApplicantConfirmed(),
                task.getPublisherCompleted(),
                task.getApplicantCompleted(),
                FileUrls.of(task.getFulfillPhotoKey()),
                task.getCancelReason(),
                task.getCancelledBy(),
                task.getAcceptedApplicationId(),
                timeline(task)
        );
    }

    @Override
    public List<AdminApplicationVO> applications(Long taskId) {
        AdminGuard.require();
        requireTask(taskId);
        List<SubstituteApplication> applications = applicationMapper.selectList(
                new LambdaQueryWrapper<SubstituteApplication>()
                        .eq(SubstituteApplication::getTaskId, taskId)
                        .orderByAsc(SubstituteApplication::getCreatedAt)
        );
        Set<Long> userIds = applications.stream().map(SubstituteApplication::getApplicantId).collect(Collectors.toSet());
        Map<Long, SysUser> users = userIds.isEmpty() ? Map.of() : sysUserMapper.selectByIds(userIds).stream()
                .collect(Collectors.toMap(SysUser::getId, u -> u, (a, b) -> a));
        Set<Long> schoolIds = users.values().stream().map(SysUser::getSchoolId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> schools = schoolIds.isEmpty() ? Map.of() : schoolMapper.selectByIds(schoolIds).stream()
                .collect(Collectors.toMap(School::getId, School::getName, (a, b) -> a));
        return applications.stream().map(item -> {
            SysUser user = users.get(item.getApplicantId());
            return new AdminApplicationVO(
                    item.getId(),
                    item.getTaskId(),
                    item.getApplicantId(),
                    user == null ? null : user.getNickname(),
                    user == null ? null : schools.get(user.getSchoolId()),
                    item.getMessage(),
                    item.getStatus(),
                    item.getCreatedAt()
            );
        }).toList();
    }

    @Override
    @Transactional
    public AdminTaskDetailVO cancel(Long id, CancelTaskRequest request) {
        AdminGuard.require();
        SubstituteTask task = requireTask(id);
        taskStatusService.cancelByAdmin(task, request.reason().trim());
        taskMapper.updateById(task);
        notifyCancelled(task, request.reason().trim());
        operationLogService.record("TASK_CANCEL", "TASK", task.getId(), request.reason());
        return detail(id);
    }

    public void cancelFromReport(SubstituteTask task, String reason) {
        taskStatusService.cancelByAdmin(task, reason);
        taskMapper.updateById(task);
        notifyCancelled(task, reason);
    }

    private void notifyCancelled(SubstituteTask task, String reason) {
        String course = task.getCourseNameSnapshot() == null ? "代课任务" : task.getCourseNameSnapshot();
        String extra = StringUtils.hasText(reason) ? " 原因：" + reason : "";
        notificationService.notifyUser(
                task.getPublisherId(),
                "TASK",
                "管理员已取消代课",
                "代课「" + course + "」已被管理员取消。" + extra,
                "TASK",
                task.getId()
        );
        Long applicantId = acceptedApplicantId(task);
        if (applicantId != null) {
            notificationService.notifyUser(
                    applicantId,
                    "TASK",
                    "管理员已取消代课",
                    "代课「" + course + "」已被管理员取消。" + extra,
                    "TASK",
                    task.getId()
            );
        }
    }

    private List<AdminTaskItemVO> toItems(List<SubstituteTask> tasks) {
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

    private List<AdminTimelineVO> timeline(SubstituteTask task) {
        List<AdminTimelineVO> events = new ArrayList<>();
        events.add(new AdminTimelineVO(task.getCreatedAt(), "CREATED", "发布任务"));
        SubstituteApplication first = applicationMapper.selectOne(new LambdaQueryWrapper<SubstituteApplication>()
                .eq(SubstituteApplication::getTaskId, task.getId())
                .orderByAsc(SubstituteApplication::getCreatedAt)
                .last("LIMIT 1"));
        if (first != null) {
            events.add(new AdminTimelineVO(first.getCreatedAt(), "APPLYING", "进入申请中"));
        }
        if (task.getAcceptedApplicationId() != null) {
            SubstituteApplication accepted = applicationMapper.selectById(task.getAcceptedApplicationId());
            LocalDateTime matchedAt = accepted == null ? task.getUpdatedAt() : accepted.getUpdatedAt();
            events.add(new AdminTimelineVO(matchedAt, "MATCHED", "已匹配代课者"));
        }
        if (isSet(task.getApplicantConfirmed())) {
            events.add(new AdminTimelineVO(task.getUpdatedAt(), "CONFIRMED", "代课者已上传履约照片"));
        }
        if (TaskStatus.IN_PROGRESS.name().equals(task.getStatus())
                || TaskStatus.COMPLETED.name().equals(task.getStatus())
                || TaskStatus.CANCELLED.name().equals(task.getStatus())) {
            if (task.getStartAt() != null) {
                events.add(new AdminTimelineVO(task.getStartAt(), "IN_PROGRESS", "进入上课"));
            }
        }
        if (TaskStatus.COMPLETED.name().equals(task.getStatus())) {
            events.add(new AdminTimelineVO(task.getUpdatedAt(), "COMPLETED", "已完成"));
        } else if (TaskStatus.CANCELLED.name().equals(task.getStatus())) {
            String who = "ADMIN".equals(task.getCancelledBy()) ? "管理员取消" : "用户取消";
            events.add(new AdminTimelineVO(task.getUpdatedAt(), "CANCELLED", who));
        } else if (TaskStatus.EXPIRED.name().equals(task.getStatus())) {
            events.add(new AdminTimelineVO(task.getUpdatedAt(), "EXPIRED", "已过期"));
        }
        events.sort(Comparator.comparing(AdminTimelineVO::at, Comparator.nullsLast(Comparator.naturalOrder())));
        return events;
    }

    private SubstituteTask requireTask(Long id) {
        SubstituteTask task = taskMapper.selectById(id);
        if (task == null) {
            throw new BizException(ErrorCode.TASK_NOT_FOUND);
        }
        return task;
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

    private boolean isSet(Integer flag) {
        return flag != null && flag == 1;
    }

    private LocalDate parseDate(String raw) {
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException ex) {
            throw new BizException(ErrorCode.BAD_REQUEST, "日期格式应为 yyyy-MM-dd");
        }
    }
}
