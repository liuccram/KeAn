package com.kean.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.kean.entity.SubstituteApplication;
import com.kean.entity.SubstituteTask;
import com.kean.entity.SysUser;
import com.kean.enums.ApplicationStatus;
import com.kean.enums.TaskStatus;
import com.kean.mapper.SubstituteApplicationMapper;
import com.kean.mapper.SubstituteTaskMapper;
import com.kean.mapper.SysUserMapper;
import com.kean.service.NotificationService;
import com.kean.service.TaskStatusService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Component
public class TaskScheduleService {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("M月d日 HH:mm");

    private static final Logger log = LoggerFactory.getLogger(TaskScheduleService.class);

    private final SubstituteTaskMapper taskMapper;
    private final SubstituteApplicationMapper applicationMapper;
    private final SysUserMapper sysUserMapper;
    private final TaskStatusService taskStatusService;
    private final NotificationService notificationService;

    public TaskScheduleService(
            SubstituteTaskMapper taskMapper,
            SubstituteApplicationMapper applicationMapper,
            SysUserMapper sysUserMapper,
            TaskStatusService taskStatusService,
            NotificationService notificationService
    ) {
        this.taskMapper = taskMapper;
        this.applicationMapper = applicationMapper;
        this.sysUserMapper = sysUserMapper;
        this.taskStatusService = taskStatusService;
        this.notificationService = notificationService;
    }

    @Scheduled(fixedDelay = 30000)
    public void refreshStatuses() {
        expireRestrictions();
        LocalDateTime now = LocalDateTime.now();
        List<SubstituteTask> dueExpire = taskMapper.selectList(new LambdaQueryWrapper<SubstituteTask>()
                .in(SubstituteTask::getStatus, TaskStatus.WAITING.name(), TaskStatus.APPLYING.name())
                .le(SubstituteTask::getStartAt, now));
        for (SubstituteTask task : dueExpire) {
            try {
                String before = task.getStatus();
                taskStatusService.expire(task);
                taskMapper.updateById(task);
                notifyExpired(task, before);
            } catch (Exception ex) {
                log.warn("Expire task {} failed: {}", task.getId(), ex.getMessage());
            }
        }
        List<SubstituteTask> dueMatched = taskMapper.selectList(new LambdaQueryWrapper<SubstituteTask>()
                .eq(SubstituteTask::getStatus, TaskStatus.MATCHED.name())
                .le(SubstituteTask::getStartAt, now));
        for (SubstituteTask task : dueMatched) {
            try {
                taskStatusService.toInProgressIfDue(task);
                taskMapper.updateById(task);
            } catch (Exception ex) {
                log.warn("Advance matched task {} failed: {}", task.getId(), ex.getMessage());
            }
        }
        List<SubstituteTask> dueStart = taskMapper.selectList(new LambdaQueryWrapper<SubstituteTask>()
                .eq(SubstituteTask::getStatus, TaskStatus.CONFIRMED.name())
                .le(SubstituteTask::getStartAt, now));
        for (SubstituteTask task : dueStart) {
            try {
                taskStatusService.toInProgressIfDue(task);
                taskMapper.updateById(task);
            } catch (Exception ex) {
                log.warn("Start task {} failed: {}", task.getId(), ex.getMessage());
            }
        }
        List<SubstituteTask> dueNoPhoto = taskMapper.selectList(new LambdaQueryWrapper<SubstituteTask>()
                .eq(SubstituteTask::getRequirePhoto, 1)
                .in(SubstituteTask::getStatus,
                        TaskStatus.MATCHED.name(),
                        TaskStatus.CONFIRMED.name(),
                        TaskStatus.IN_PROGRESS.name())
                .le(SubstituteTask::getEndAt, now)
                .and(w -> w.isNull(SubstituteTask::getApplicantConfirmed).or().ne(SubstituteTask::getApplicantConfirmed, 1)));
        for (SubstituteTask task : dueNoPhoto) {
            try {
                String before = task.getStatus();
                taskStatusService.expire(task);
                taskMapper.updateById(task);
                notifyExpired(task, before);
            } catch (Exception ex) {
                log.warn("Expire no-photo task {} failed: {}", task.getId(), ex.getMessage());
            }
        }
        // 下课满 24 小时后自动完成，该窗口同时预留给未来争议，期间先走举报。
        List<SubstituteTask> dueAutoComplete = taskMapper.selectList(new LambdaQueryWrapper<SubstituteTask>()
                .eq(SubstituteTask::getStatus, TaskStatus.IN_PROGRESS.name())
                .le(SubstituteTask::getEndAt, now.minusHours(TaskStatusService.AUTO_COMPLETE_DELAY_HOURS)));
        for (SubstituteTask task : dueAutoComplete) {
            try {
                taskStatusService.autoComplete(task);
                taskMapper.updateById(task);
                bumpApplicantCompleted(task);
                notifyAutoCompleted(task);
            } catch (Exception ex) {
                log.warn("Auto complete task {} failed: {}", task.getId(), ex.getMessage());
            }
        }
        sendReminders(now);
    }

    private void sendReminders(LocalDateTime now) {
        LocalDateTime inTwoHours = now.plusHours(2);
        LocalDateTime inHalfHour = now.plusMinutes(30);
        List<SubstituteTask> upcoming = taskMapper.selectList(new LambdaQueryWrapper<SubstituteTask>()
                .in(SubstituteTask::getStatus, TaskStatus.MATCHED.name(), TaskStatus.CONFIRMED.name())
                .gt(SubstituteTask::getStartAt, now)
                .le(SubstituteTask::getStartAt, inTwoHours));
        for (SubstituteTask task : upcoming) {
            try {
                boolean noPhoto = task.getApplicantConfirmed() == null || task.getApplicantConfirmed() != 1;
                boolean soon = !task.getStartAt().isAfter(inHalfHour);
                String course = courseName(task);
                String startText = formatClock(task.getStartAt());
                String applicant = applicantName(task);
                if (soon && (task.getClassReminded() == null || task.getClassReminded() != 1)) {
                    long minutes = minutesUntil(now, task.getStartAt());
                    String title = "距上课还有 " + minutes + " 分钟";
                    if (!taskStatusService.requirePhoto(task)) {
                        notifyPublisher(
                                task,
                                title,
                                "代课「" + course + "」将于 " + startText + " 开始，请关注上课。"
                        );
                        notifyApplicant(
                                task,
                                title,
                                "代课「" + course + "」将于 " + startText + " 开始，请按时到场。"
                        );
                    } else if (noPhoto) {
                        notifyPublisher(
                                task,
                                title,
                                "代课「" + course + "」将于 " + startText + " 开始。开课前 5 分钟至下课前，代课者 " + applicant + " 可上传现场照片。"
                        );
                        notifyApplicant(
                                task,
                                title,
                                "代课「" + course + "」将于 " + startText + " 开始。开课前 5 分钟至下课前可上传现场照片。"
                        );
                    } else {
                        notifyPublisher(
                                task,
                                title,
                                "代课「" + course + "」将于 " + startText + " 开始。代课者 " + applicant + " 已上传照片，请关注上课。"
                        );
                        notifyApplicant(
                                task,
                                title,
                                "代课「" + course + "」将于 " + startText + " 开始。你已上传照片，请按时到场。"
                        );
                    }
                    task.setClassReminded(1);
                    taskMapper.updateById(task);
                }
            } catch (Exception ex) {
                log.warn("Remind upcoming task {} failed: {}", task.getId(), ex.getMessage());
            }
        }
        LocalDateTime photoOpen = now.plusMinutes(TaskStatusService.PHOTO_WINDOW_BEFORE_MINUTES);
        List<SubstituteTask> photoDue = taskMapper.selectList(new LambdaQueryWrapper<SubstituteTask>()
                .eq(SubstituteTask::getRequirePhoto, 1)
                .in(SubstituteTask::getStatus,
                        TaskStatus.MATCHED.name(),
                        TaskStatus.CONFIRMED.name(),
                        TaskStatus.IN_PROGRESS.name())
                .le(SubstituteTask::getStartAt, photoOpen)
                .ge(SubstituteTask::getEndAt, now)
                .and(w -> w.isNull(SubstituteTask::getApplicantConfirmed).or().ne(SubstituteTask::getApplicantConfirmed, 1))
                .and(w -> w.isNull(SubstituteTask::getPhotoReminded).or().ne(SubstituteTask::getPhotoReminded, 1)));
        for (SubstituteTask task : photoDue) {
            try {
                String course = courseName(task);
                String startText = formatClock(task.getStartAt());
                String applicant = applicantName(task);
                notifyPublisher(
                        task,
                        "待对方上传照片",
                        "代课「" + course + "」将于 " + startText + " 开始。代课者 " + applicant + " 未上传照片，可在下课前上传。"
                );
                notifyApplicant(
                        task,
                        "可以上传现场照片",
                        "代课「" + course + "」将于 " + startText + " 开始。你现在可以上传现场照片，下课前未上传将过期。"
                );
                task.setPhotoReminded(1);
                taskMapper.updateById(task);
            } catch (Exception ex) {
                log.warn("Remind photo task {} failed: {}", task.getId(), ex.getMessage());
            }
        }
        List<SubstituteTask> dueComplete = taskMapper.selectList(new LambdaQueryWrapper<SubstituteTask>()
                .eq(SubstituteTask::getStatus, TaskStatus.IN_PROGRESS.name())
                .le(SubstituteTask::getEndAt, now)
                .and(w -> w.eq(SubstituteTask::getRequirePhoto, 0).or().eq(SubstituteTask::getApplicantConfirmed, 1))
                .and(w -> w.isNull(SubstituteTask::getCompleteReminded).or().ne(SubstituteTask::getCompleteReminded, 1)));
        for (SubstituteTask task : dueComplete) {
            try {
                String course = courseName(task);
                String endText = formatClock(task.getEndAt());
                String applicant = applicantName(task);
                notifyPublisher(
                        task,
                        "可以确认完成",
                        "代课「" + course + "」已于 " + endText + " 下课，你或代课者 " + applicant + " 确认即可完成。"
                );
                notifyApplicant(
                        task,
                        "可以确认完成",
                        "代课「" + course + "」已于 " + endText + " 下课，你或发布者确认即可完成。"
                );
                task.setCompleteReminded(1);
                taskMapper.updateById(task);
            } catch (Exception ex) {
                log.warn("Remind complete task {} failed: {}", task.getId(), ex.getMessage());
            }
        }
    }

    private void notifyPublisher(SubstituteTask task, String title, String content) {
        notificationService.notifyUser(task.getPublisherId(), "TASK", title, content, "TASK", task.getId());
    }

    private void notifyApplicant(SubstituteTask task, String title, String content) {
        Long applicantId = acceptedApplicantUserId(task);
        if (applicantId == null) {
            return;
        }
        notificationService.notifyUser(applicantId, "TASK", title, content, "TASK", task.getId());
    }

    private Long acceptedApplicantUserId(SubstituteTask task) {
        if (task.getAcceptedApplicationId() == null) {
            return null;
        }
        SubstituteApplication application = applicationMapper.selectById(task.getAcceptedApplicationId());
        if (application == null || !ApplicationStatus.ACCEPTED.name().equals(application.getStatus())) {
            return null;
        }
        return application.getApplicantId();
    }

    private String applicantName(SubstituteTask task) {
        return userDisplayName(acceptedApplicantUserId(task), "代课者");
    }

    private String publisherName(SubstituteTask task) {
        return userDisplayName(task.getPublisherId(), "发布者");
    }

    private String userDisplayName(Long userId, String fallback) {
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

    private String formatClock(LocalDateTime time) {
        if (time == null) {
            return "";
        }
        return time.format(CLOCK);
    }

    private long minutesUntil(LocalDateTime now, LocalDateTime start) {
        if (start == null) {
            return 0;
        }
        return Math.max(ChronoUnit.MINUTES.between(now, start), 0);
    }

    private String courseName(SubstituteTask task) {
        return task.getCourseNameSnapshot() == null ? "代课任务" : task.getCourseNameSnapshot();
    }

    private void bumpApplicantCompleted(SubstituteTask task) {
        if (task.getAcceptedApplicationId() == null) {
            return;
        }
        SubstituteApplication application = applicationMapper.selectById(task.getAcceptedApplicationId());
        if (application == null || !ApplicationStatus.ACCEPTED.name().equals(application.getStatus())) {
            return;
        }
        SysUser user = sysUserMapper.selectById(application.getApplicantId());
        if (user == null) {
            return;
        }
        int completed = user.getCompletedCount() == null ? 0 : user.getCompletedCount();
        user.setCompletedCount(completed + 1);
        sysUserMapper.updateById(user);
        SysUser publisher = sysUserMapper.selectById(task.getPublisherId());
        if (publisher == null) {
            return;
        }
        int published = publisher.getPublishCompletedCount() == null ? 0 : publisher.getPublishCompletedCount();
        publisher.setPublishCompletedCount(published + 1);
        sysUserMapper.updateById(publisher);
    }

    private void notifyAutoCompleted(SubstituteTask task) {
        String course = courseName(task);
        String title = "代课已自动完成";
        notificationService.notifyUser(
                task.getPublisherId(),
                "TASK",
                title,
                "代课「" + course + "」已过下课 24 小时，系统已自动确认完成，请为代课者 " + applicantName(task) + " 打星。",
                "REVIEW",
                task.getId()
        );
        Long applicantId = acceptedApplicantUserId(task);
        if (applicantId != null) {
            notificationService.notifyUser(
                    applicantId,
                    "TASK",
                    title,
                    "代课「" + course + "」已过下课 24 小时，系统已自动确认完成，请为发布者 " + publisherName(task) + " 打星。",
                    "REVIEW",
                    task.getId()
            );
        }
    }

    private void expireRestrictions() {
        LocalDateTime now = LocalDateTime.now();
        sysUserMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getForbidPublish, 1)
                .isNotNull(SysUser::getForbidPublishUntil)
                .le(SysUser::getForbidPublishUntil, now)
                .set(SysUser::getForbidPublish, 0)
                .set(SysUser::getForbidPublishUntil, null));
        sysUserMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getForbidApply, 1)
                .isNotNull(SysUser::getForbidApplyUntil)
                .le(SysUser::getForbidApplyUntil, now)
                .set(SysUser::getForbidApply, 0)
                .set(SysUser::getForbidApplyUntil, null));
        sysUserMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getMuted, 1)
                .isNotNull(SysUser::getMutedUntil)
                .le(SysUser::getMutedUntil, now)
                .set(SysUser::getMuted, 0)
                .set(SysUser::getMutedUntil, null));
    }

    private void notifyExpired(SubstituteTask task, String before) {
        String course = courseName(task);
        if (TaskStatus.MATCHED.name().equals(before)
                || TaskStatus.CONFIRMED.name().equals(before)
                || TaskStatus.IN_PROGRESS.name().equals(before)) {
            String applicant = applicantName(task);
            notifyPublisher(task, "匹配任务已过期", "代课「" + course + "」下课前代课者 " + applicant + " 未上传照片，任务已过期。");
            notifyApplicant(task, "匹配任务已过期", "代课「" + course + "」下课前你未上传照片，任务已过期。");
            return;
        }
        notifyPublisher(task, "代课任务已过期", "你发布的代课「" + course + "」已到上课时间且无人完成匹配，任务已过期。");
        if (task.getAcceptedApplicationId() != null) {
            notifyApplicant(task, "代课任务已过期", "代课「" + course + "」已到上课时间且无人完成匹配，任务已过期。");
        }
    }
}
