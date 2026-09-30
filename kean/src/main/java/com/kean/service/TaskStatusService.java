package com.kean.service;

import com.kean.common.ErrorCode;
import com.kean.entity.SubstituteTask;
import com.kean.enums.TaskStatus;
import com.kean.enums.UserRole;
import com.kean.exception.BizException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Objects;

@Service
public class TaskStatusService {

    /** 待上课阶段，开课前此时长内双方均不可取消。 */
    public static final int MATCHED_CANCEL_LOCK_HOURS = 3;

    /** 已有人接代课后，开课前此时长内不可再改地点。 */
    public static final int LOCATION_LOCK_BEFORE_HOURS = 1;

    /** 现场照片最早可在开课前此时长上传，最晚到下课。 */
    public static final int PHOTO_WINDOW_BEFORE_MINUTES = 5;

    /** 下课后此时长内无人确认则自动完成，同时预留未来争议窗口。 */
    public static final int AUTO_COMPLETE_DELAY_HOURS = 24;

    public void initWaiting(SubstituteTask task) {
        task.setStatus(TaskStatus.WAITING.name());
    }

    public void assertEditable(SubstituteTask task) {
        if (!TaskStatus.WAITING.name().equals(task.getStatus())
                || (task.getApplyCount() != null && task.getApplyCount() > 0)) {
            throw new BizException(ErrorCode.TASK_NOT_EDITABLE);
        }
    }

    public void assertLocationEditable(SubstituteTask task) {
        String status = task.getStatus();
        if (!TaskStatus.WAITING.name().equals(status)
                && !TaskStatus.APPLYING.name().equals(status)
                && !TaskStatus.MATCHED.name().equals(status)
                && !TaskStatus.CONFIRMED.name().equals(status)) {
            throw new BizException(ErrorCode.TASK_NOT_EDITABLE, "当前状态不能修改地点或备注");
        }
    }

    public boolean hasAcceptedSubstitute(SubstituteTask task) {
        if (task == null) {
            return false;
        }
        String status = task.getStatus();
        return TaskStatus.MATCHED.name().equals(status)
                || TaskStatus.CONFIRMED.name().equals(status)
                || task.getAcceptedApplicationId() != null;
    }

    public boolean isLocationLocked(SubstituteTask task) {
        if (!hasAcceptedSubstitute(task) || task.getStartAt() == null) {
            return false;
        }
        return !task.getStartAt().isAfter(LocalDateTime.now().plusHours(LOCATION_LOCK_BEFORE_HOURS));
    }

    public boolean isFullyEditable(SubstituteTask task) {
        return TaskStatus.WAITING.name().equals(task.getStatus())
                && (task.getApplyCount() == null || task.getApplyCount() == 0);
    }

    public void assertCanApply(SubstituteTask task) {
        String status = task.getStatus();
        if (!TaskStatus.WAITING.name().equals(status) && !TaskStatus.APPLYING.name().equals(status)) {
            throw new BizException(ErrorCode.TASK_STATUS_INVALID, "当前状态不可申请");
        }
        if (task.getStartAt() != null && !task.getStartAt().isAfter(LocalDateTime.now())) {
            throw new BizException(ErrorCode.TASK_STATUS_INVALID, "任务已到上课时间，不能申请");
        }
    }

    public void onApplicationCreated(SubstituteTask task) {
        assertCanApply(task);
        if (TaskStatus.WAITING.name().equals(task.getStatus())) {
            task.setStatus(TaskStatus.APPLYING.name());
        }
    }

    public void onNoPendingApplications(SubstituteTask task) {
        if (TaskStatus.APPLYING.name().equals(task.getStatus())) {
            task.setStatus(TaskStatus.WAITING.name());
        }
    }

    public void onAccepted(SubstituteTask task, Long applicationId) {
        if (!TaskStatus.APPLYING.name().equals(task.getStatus())) {
            throw new BizException(ErrorCode.TASK_STATUS_INVALID, "当前状态不能选人");
        }
        task.setAcceptedApplicationId(applicationId);
        task.setStatus(TaskStatus.MATCHED.name());
    }

    public void onPublisherConfirm(SubstituteTask task) {
        throw new BizException(ErrorCode.TASK_STATUS_INVALID, "发布者无需确认履约，请下课后再确认完成");
    }

    public void onApplicantConfirm(SubstituteTask task) {
        assertCanUploadFulfillPhoto(task);
        task.setApplicantConfirmed(1);
        if (task.getStartAt() != null && !task.getStartAt().isAfter(LocalDateTime.now())) {
            task.setStatus(TaskStatus.IN_PROGRESS.name());
        } else if (!TaskStatus.IN_PROGRESS.name().equals(task.getStatus())) {
            task.setStatus(TaskStatus.MATCHED.name());
        }
    }

    public void toInProgressIfDue(SubstituteTask task) {
        if (task.getStartAt() == null || task.getStartAt().isAfter(LocalDateTime.now())) {
            return;
        }
        if (TaskStatus.CONFIRMED.name().equals(task.getStatus())
                || TaskStatus.MATCHED.name().equals(task.getStatus())) {
            task.setStatus(TaskStatus.IN_PROGRESS.name());
        }
    }

    public void completeByParty(SubstituteTask task) {
        assertCanComplete(task);
        task.setPublisherCompleted(1);
        task.setApplicantCompleted(1);
        task.setStatus(TaskStatus.COMPLETED.name());
    }

    public void onPublisherComplete(SubstituteTask task) {
        completeByParty(task);
    }

    public void onApplicantComplete(SubstituteTask task) {
        completeByParty(task);
    }

    public void autoComplete(SubstituteTask task) {
        if (!TaskStatus.IN_PROGRESS.name().equals(task.getStatus())) {
            throw new BizException(ErrorCode.TASK_STATUS_INVALID);
        }
        if (requirePhoto(task) && !isSet(task.getApplicantConfirmed())) {
            throw new BizException(ErrorCode.TASK_STATUS_INVALID);
        }
        task.setPublisherCompleted(1);
        task.setApplicantCompleted(1);
        task.setStatus(TaskStatus.COMPLETED.name());
    }

    public void cancelByUser(SubstituteTask task, String reason, boolean publisher, boolean acceptedApplicant) {
        String status = task.getStatus();
        if (TaskStatus.WAITING.name().equals(status) || TaskStatus.APPLYING.name().equals(status)) {
            if (!publisher) {
                throw new BizException(ErrorCode.FORBIDDEN, "仅发布者可取消");
            }
        } else if (TaskStatus.MATCHED.name().equals(status) || TaskStatus.CONFIRMED.name().equals(status)) {
            if (!publisher && !acceptedApplicant) {
                throw new BizException(ErrorCode.FORBIDDEN, "仅发布者或已选代课者可取消");
            }
            assertMatchedCancelAllowed(task);
        } else if (TaskStatus.IN_PROGRESS.name().equals(status)) {
            throw new BizException(ErrorCode.FORBIDDEN, "进行中仅管理员可强制取消");
        } else {
            throw new BizException(ErrorCode.TASK_STATUS_INVALID, "当前状态不能取消");
        }
        task.setStatus(TaskStatus.CANCELLED.name());
        task.setCancelReason(reason);
        task.setCancelledBy("USER");
    }

    public void cancelByAdmin(SubstituteTask task, String reason) {
        String status = task.getStatus();
        if (TaskStatus.COMPLETED.name().equals(status)
                || TaskStatus.CANCELLED.name().equals(status)
                || TaskStatus.EXPIRED.name().equals(status)) {
            throw new BizException(ErrorCode.TASK_STATUS_INVALID, "当前状态不能取消");
        }
        task.setStatus(TaskStatus.CANCELLED.name());
        task.setCancelReason(reason);
        task.setCancelledBy("ADMIN");
    }

    public void expire(SubstituteTask task) {
        String status = task.getStatus();
        if (TaskStatus.WAITING.name().equals(status)
                || TaskStatus.APPLYING.name().equals(status)
                || TaskStatus.MATCHED.name().equals(status)
                || TaskStatus.CONFIRMED.name().equals(status)
                || TaskStatus.IN_PROGRESS.name().equals(status)) {
            task.setStatus(TaskStatus.EXPIRED.name());
            return;
        }
        throw new BizException(ErrorCode.TASK_STATUS_INVALID);
    }

    public void assertCanUploadFulfillPhoto(SubstituteTask task) {
        if (!requirePhoto(task)) {
            throw new BizException(ErrorCode.TASK_STATUS_INVALID, "本任务无需上传现场照片");
        }
        String status = task.getStatus();
        if (!TaskStatus.MATCHED.name().equals(status)
                && !TaskStatus.CONFIRMED.name().equals(status)
                && !TaskStatus.IN_PROGRESS.name().equals(status)) {
            throw new BizException(ErrorCode.TASK_STATUS_INVALID, "当前状态不能上传现场照片");
        }
        LocalDateTime now = LocalDateTime.now();
        if (task.getStartAt() != null
                && now.isBefore(task.getStartAt().minusMinutes(PHOTO_WINDOW_BEFORE_MINUTES))) {
            throw new BizException(
                    ErrorCode.TASK_STATUS_INVALID,
                    "开课前 " + PHOTO_WINDOW_BEFORE_MINUTES + " 分钟至下课前才能上传现场照片"
            );
        }
        if (task.getEndAt() != null && now.isAfter(task.getEndAt())) {
            throw new BizException(ErrorCode.TASK_STATUS_INVALID, "已下课，不能再上传现场照片");
        }
    }

    public boolean isAdmin(String role) {
        return Objects.equals(UserRole.ADMIN.name(), role);
    }

    private void assertCanComplete(SubstituteTask task) {
        if (!TaskStatus.IN_PROGRESS.name().equals(task.getStatus())) {
            throw new BizException(ErrorCode.TASK_STATUS_INVALID, "上课开始后才能确认完成");
        }
        if (requirePhoto(task) && !isSet(task.getApplicantConfirmed())) {
            throw new BizException(ErrorCode.TASK_STATUS_INVALID, "代课者上传履约照片后才能确认完成");
        }
        if (task.getEndAt() != null && task.getEndAt().isAfter(LocalDateTime.now())) {
            throw new BizException(ErrorCode.TASK_STATUS_INVALID, "下课后再确认完成");
        }
    }

    private void assertMatchedCancelAllowed(SubstituteTask task) {
        if (isSet(task.getApplicantConfirmed())) {
            throw new BizException(ErrorCode.TASK_STATUS_INVALID, "已上传履约照片后不可取消，如未到场请私聊对方或举报");
        }
        if (task.getStartAt() != null
                && !task.getStartAt().isAfter(LocalDateTime.now().plusHours(MATCHED_CANCEL_LOCK_HOURS))) {
            throw new BizException(
                    ErrorCode.TASK_STATUS_INVALID,
                    "开课前 " + MATCHED_CANCEL_LOCK_HOURS + " 小时内不可取消"
            );
        }
    }

    public boolean requirePhoto(SubstituteTask task) {
        return task != null && task.getRequirePhoto() != null && task.getRequirePhoto() == 1;
    }

    private boolean isSet(Integer flag) {
        return flag != null && flag == 1;
    }
}
