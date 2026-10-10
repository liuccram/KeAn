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
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
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

    /**
     * 标题里课程名的最大长度。notification.title 是 VARCHAR(100)（V1 建表），
     * 而"「」+ N 分钟后开始"固定占 11 个字符（N 最多 3 位），留足余量取 80。
     */
    private static final int TITLE_COURSE_MAX = 80;

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

    /**
     * 多实例部署时，同一个 {@code @Scheduled} 会在每个实例上各跑一遍 —— 重复发通知、
     * completedCount 重复累加。{@code @SchedulerLock} 保证同一时刻只有一个实例真正执行，
     * 拿不到锁的实例直接跳过本轮、下一轮再来。
     *
     * <p>{@code lockAtMostFor} 是持有上限：实例崩溃时超过它即可被其它实例接管，
     * 避免永久死锁。任务本身只跑几秒，2 分钟足够宽裕。
     */
    @Scheduled(fixedDelay = 30000)
    @SchedulerLock(name = "task.refreshStatuses", lockAtMostFor = "PT2M")
    public void refreshStatuses() {
        // 前置清理（用户禁言/禁止发布等到期）失败时不能拖垮整轮：它是纯附加动作，
        // 与下面的任务状态扫描没有任何依赖关系。这里单独兜住（表结构缺失、DB 抖动等），
        // 记 warn 后继续往下跑，保证过期扫描每轮都能执行到；下一轮 30 秒后再重试。
        try {
            expireRestrictions();
        } catch (Exception ex) {
            log.warn("Expire user restrictions failed, continue this round: {}", ex.getMessage());
        }
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
                    // ⚠️ B1：标题里带课程名，格式「课程名」N 分钟后开始 —— 客户端「{课程} N 分钟后开始」
                    // 的定型文案一直缺课程名（旧标题是"距上课还有 N 分钟"，课程名只在正文里）。
                    // 正文**一个字都不动**：客户端还在从正文的「」与"将于…开始"里抽课程名与上课时间。
                    String title = "「" + titleCourseName(task) + "」" + minutes + " 分钟后开始";
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

    /**
     * 发布者侧通知。收件角色固定是发布者，所以在这里统一标注 receiverRole=PUBLISHER ——
     * 本文件里所有"同一事件发两方"的通知（即将开始 / 待对方上传照片 / 可以确认完成 / 过期）
     * 都走这个入口，各自那几处调用点因此不需要逐个改，也不会漏。
     */
    private void notifyPublisher(SubstituteTask task, String title, String content) {
        notificationService.notifyUser(
                task.getPublisherId(),
                "TASK",
                title,
                content,
                "TASK",
                task.getId(),
                NotificationService.RECEIVER_ROLE_PUBLISHER
        );
    }

    /**
     * 代课者侧通知。收件角色固定是代课者，统一标注 receiverRole=APPLICANT（理由同 {@link #notifyPublisher}）。
     * 取不到已接受的申请人时**不发**（保持原行为，不猜收件人）。
     */
    private void notifyApplicant(SubstituteTask task, String title, String content) {
        Long applicantId = acceptedApplicantUserId(task);
        if (applicantId == null) {
            return;
        }
        notificationService.notifyUser(
                applicantId,
                "TASK",
                title,
                content,
                "TASK",
                task.getId(),
                NotificationService.RECEIVER_ROLE_APPLICANT
        );
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

    /**
     * 标题里用的课程名：快照为 null **或空白**时都回退「代课任务」，绝不拼出空「」。
     *
     * <p>为什么不直接用 {@link #courseName(SubstituteTask)}：那个方法只兜住 null，而且它同时服务于
     * **正文**，而正文一个字都不许动（客户端靠正文里的「」抽课程名），所以这里单独给标题一条兜底口径。
     *
     * <p>另外这里按标题列做长度保护：课程名快照最长 128（V1:69），"「」+ 分钟后开始"固定占 11 个字符，
     * 不截断的话超长课程名在严格模式下会写成 1406 Data too long —— 那条提醒会被 sendReminders 的
     * try/catch 每 30 秒吞一次、却永远发不出去，所以按列预算截断（与 LoginDeviceServiceImpl:481
     * 把设备名截到 120 是同一思路）。
     */
    private String titleCourseName(SubstituteTask task) {
        String course = task.getCourseNameSnapshot();
        String name = StringUtils.hasText(course) ? course.trim() : "代课任务";
        return name.length() > TITLE_COURSE_MAX ? name.substring(0, TITLE_COURSE_MAX) : name;
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
                task.getId(),
                NotificationService.RECEIVER_ROLE_PUBLISHER
        );
        Long applicantId = acceptedApplicantUserId(task);
        if (applicantId != null) {
            notificationService.notifyUser(
                    applicantId,
                    "TASK",
                    title,
                    "代课「" + course + "」已过下课 24 小时，系统已自动确认完成，请为发布者 " + publisherName(task) + " 打星。",
                    "REVIEW",
                    task.getId(),
                    NotificationService.RECEIVER_ROLE_APPLICANT
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
