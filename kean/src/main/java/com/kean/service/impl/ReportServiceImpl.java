package com.kean.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kean.common.ErrorCode;
import com.kean.common.PageResult;
import com.kean.common.Pages;
import com.kean.dto.CancelTaskRequest;
import com.kean.entity.ChatMessage;
import com.kean.entity.Report;
import com.kean.entity.ReportAppeal;
import com.kean.entity.SubstituteTask;
import com.kean.entity.SysUser;
import com.kean.enums.TaskStatus;
import com.kean.enums.UserRole;
import com.kean.enums.UserStatus;
import com.kean.exception.BizException;
import com.kean.mapper.ChatMessageMapper;
import com.kean.mapper.ReportAppealMapper;
import com.kean.mapper.ReportMapper;
import com.kean.mapper.SubstituteTaskMapper;
import com.kean.mapper.SysUserMapper;
import com.kean.security.AdminGuard;
import com.kean.security.LoginUser;
import com.kean.security.SecurityUtils;
import com.kean.security.TokenRevokeService;
import com.kean.service.AccountBanService;
import com.kean.service.AdminTaskService;
import com.kean.service.NotificationService;
import com.kean.service.OperationLogService;
import com.kean.service.ReportService;
import com.kean.utils.FileUrls;
import com.kean.vo.ReportAppealVO;
import com.kean.vo.ReportVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class ReportServiceImpl implements ReportService {

    private static final Set<String> TARGETS = Set.of("USER", "TASK", "MESSAGE", "FEEDBACK");
    private static final Map<String, String> TYPES = new LinkedHashMap<>();
    private static final Map<String, String> FEEDBACK_TYPES = new LinkedHashMap<>();
    private static final Set<String> HANDLE_RESULTS = Set.of("WARN", "DELETE", "RESTRICT", "BAN", "REJECT");
    private static final Set<String> FEEDBACK_HANDLES = Set.of("REPLY", "REJECT");
    private static final TypeReference<List<String>> IMAGE_TYPE = new TypeReference<>() {
    };

    private static final Map<String, String> HANDLE_LABELS = new LinkedHashMap<>();

    static {
        TYPES.put("FAKE", "虚假信息");
        TYPES.put("HARASS", "骚扰辱骂");
        TYPES.put("MALICIOUS_CANCEL", "恶意取消");
        TYPES.put("FRAUD", "欺诈诱导");
        TYPES.put("VIOLATION", "违规内容");
        TYPES.put("OTHER", "其他");
        FEEDBACK_TYPES.put("SUGGESTION", "功能建议");
        FEEDBACK_TYPES.put("BUG", "故障问题");
        FEEDBACK_TYPES.put("ACCOUNT", "账号问题");
        FEEDBACK_TYPES.put("SERVICE", "服务体验");
        FEEDBACK_TYPES.put("OTHER", "其他事宜");
        HANDLE_LABELS.put("WARN", "警告");
        HANDLE_LABELS.put("DELETE", "删除内容");
        HANDLE_LABELS.put("RESTRICT", "限制功能");
        HANDLE_LABELS.put("BAN", "封禁账号");
        HANDLE_LABELS.put("REJECT", "驳回");
        HANDLE_LABELS.put("REPLY", "已回复");
    }

    private final ReportMapper reportMapper;
    private final ReportAppealMapper reportAppealMapper;
    private final SysUserMapper sysUserMapper;
    private final SubstituteTaskMapper taskMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final ObjectMapper objectMapper;
    private final NotificationService notificationService;
    private final TokenRevokeService tokenRevokeService;
    private final AccountBanService accountBanService;
    private final AdminTaskService adminTaskService;
    private final OperationLogService operationLogService;

    public ReportServiceImpl(
            ReportMapper reportMapper,
            ReportAppealMapper reportAppealMapper,
            SysUserMapper sysUserMapper,
            SubstituteTaskMapper taskMapper,
            ChatMessageMapper chatMessageMapper,
            ObjectMapper objectMapper,
            NotificationService notificationService,
            TokenRevokeService tokenRevokeService,
            AccountBanService accountBanService,
            AdminTaskService adminTaskService,
            OperationLogService operationLogService
    ) {
        this.reportMapper = reportMapper;
        this.reportAppealMapper = reportAppealMapper;
        this.sysUserMapper = sysUserMapper;
        this.taskMapper = taskMapper;
        this.chatMessageMapper = chatMessageMapper;
        this.objectMapper = objectMapper;
        this.notificationService = notificationService;
        this.tokenRevokeService = tokenRevokeService;
        this.accountBanService = accountBanService;
        this.adminTaskService = adminTaskService;
        this.operationLogService = operationLogService;
    }

    @Override
    public List<Map<String, String>> types() {
        return types(null);
    }

    @Override
    public List<Map<String, String>> types(String scope) {
        Map<String, String> source = "FEEDBACK".equalsIgnoreCase(scope) ? FEEDBACK_TYPES : TYPES;
        List<Map<String, String>> list = new ArrayList<>();
        source.forEach((value, label) -> {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("value", value);
            item.put("label", label);
            list.add(item);
        });
        return list;
    }

    @Override
    @Transactional
    public ReportVO create(String targetType, Long targetId, String type, String description, List<String> images) {
        LoginUser loginUser = SecurityUtils.currentUser();
        String normalizedTarget = normalize(targetType);
        String normalizedType = normalize(type);
        if (!TARGETS.contains(normalizedTarget)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "举报对象不正确");
        }
        boolean feedback = isFeedbackType(normalizedTarget);
        if (feedback) {
            if (!FEEDBACK_TYPES.containsKey(normalizedType)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "反馈类型不正确");
            }
            if (!StringUtils.hasText(description)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "请填写反馈内容");
            }
        } else {
            if (!TYPES.containsKey(normalizedType)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "举报原因不正确");
            }
            validateTarget(loginUser.userId(), normalizedTarget, targetId);
            LambdaQueryWrapper<Report> existWrapper = new LambdaQueryWrapper<Report>()
                    .eq(Report::getReporterId, loginUser.userId())
                    .eq(Report::getTargetType, normalizedTarget)
                    .eq(Report::getTargetId, targetId);
            if (!"TASK".equals(normalizedTarget)) {
                existWrapper.eq(Report::getStatus, "PENDING");
            }
            Long pending = reportMapper.selectCount(existWrapper);
            if (pending != null && pending > 0) {
                throw new BizException(ErrorCode.ALREADY_REPORTED,
                        "TASK".equals(normalizedTarget) ? "该代课你已举报过，不可再次举报" : ErrorCode.ALREADY_REPORTED.getMessage());
            }
        }
        List<String> keys = sanitizeImages(loginUser.userId(), images, "report");
        Report report = new Report();
        report.setReporterId(loginUser.userId());
        report.setTargetType(normalizedTarget);
        report.setTargetId(feedback ? 0L : targetId);
        report.setType(normalizedType);
        report.setDescription(StringUtils.hasText(description) ? description.trim() : null);
        report.setImagesJson(writeJson(keys));
        report.setStatus("PENDING");
        reportMapper.insert(report);
        SysUser reporter = sysUserMapper.selectById(loginUser.userId());
        String reporterName = reporter == null || !StringUtils.hasText(reporter.getNickname())
                ? "用户"
                : reporter.getNickname();
        String typeName = typeLabelOf(report);
        if (feedback) {
            notifyAdmins(
                    "收到用户反馈",
                    "「" + reporterName + "」提交了一条反馈（" + typeName + "），请尽快查看。",
                    report.getId()
            );
        } else {
            notifyAdmins(
                    "收到用户举报",
                    "「" + reporterName + "」提交了一条举报（" + typeName + "），对象：" + targetLabel(report) + "，请尽快处理。",
                    report.getId()
            );
        }
        return toVo(report);
    }

    @Override
    public List<ReportVO> listMine() {
        Long userId = SecurityUtils.currentUserId();
        List<Report> reports = reportMapper.selectList(new LambdaQueryWrapper<Report>()
                .eq(Report::getReporterId, userId)
                .orderByDesc(Report::getCreatedAt));
        return reports.stream().map(this::toVo).toList();
    }

    @Override
    public List<ReportVO> listAgainstMe() {
        Long userId = SecurityUtils.currentUserId();
        List<Report> reports = reportMapper.selectList(new LambdaQueryWrapper<Report>()
                .in(Report::getStatus, List.of("RESOLVED", "REJECTED"))
                .orderByDesc(Report::getHandledAt)
                .orderByDesc(Report::getId));
        return reports.stream()
                .filter(item -> Objects.equals(userId, resolveTargetUserId(item)))
                .map(this::toVo)
                .toList();
    }

    @Override
    @Transactional
    public ReportAppealVO appeal(Long reportId, String content, List<String> images) {
        LoginUser loginUser = SecurityUtils.currentUser();
        Report report = reportMapper.selectById(reportId);
        if (report == null) {
            throw new BizException(ErrorCode.REPORT_NOT_FOUND);
        }
        if (!"RESOLVED".equals(report.getStatus())) {
            throw new BizException(ErrorCode.APPEAL_NOT_ALLOWED);
        }
        if (!Objects.equals(loginUser.userId(), resolveTargetUserId(report))) {
            throw new BizException(ErrorCode.FORBIDDEN, "只能对自己的处理结果提出申诉");
        }
        Long exists = reportAppealMapper.selectCount(new LambdaQueryWrapper<ReportAppeal>()
                .eq(ReportAppeal::getReportId, report.getId())
                .eq(ReportAppeal::getUserId, loginUser.userId()));
        if (exists != null && exists > 0) {
            throw new BizException(ErrorCode.ALREADY_APPEALED);
        }
        String text = content == null ? "" : content.trim();
        List<String> keys = sanitizeImages(loginUser.userId(), images, "appeal");
        if (!StringUtils.hasText(text) && keys.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请填写申诉说明或上传图片");
        }
        ReportAppeal appeal = new ReportAppeal();
        appeal.setReportId(report.getId());
        appeal.setUserId(loginUser.userId());
        appeal.setContent(text);
        appeal.setImagesJson(writeJson(keys));
        appeal.setStatus("PENDING");
        reportAppealMapper.insert(appeal);
        String target = targetLabel(report);
        notificationService.notifyUser(
                report.getReporterId(),
                "SYSTEM",
                "被举报人已提出申诉",
                "你举报的「" + target + "」当事人已就处理结果提出申诉，平台将复核。",
                "REPORT",
                report.getId()
        );
        SysUser appellant = sysUserMapper.selectById(loginUser.userId());
        String appellantName = appellant == null || !StringUtils.hasText(appellant.getNickname())
                ? "用户"
                : appellant.getNickname();
        notifyAdmins(
                "收到用户申诉",
                "「" + appellantName + "」就「" + target + "」的处理结果提出申诉，请尽快复核。",
                report.getId()
        );
        return toAppealVo(appeal);
    }

    @Override
    public PageResult<ReportVO> adminList(String status, String type, String targetType, String appealStatus, String kind, String keyword, Long page, Long size) {
        AdminGuard.require();
        long pageNo = Pages.page(page);
        long pageSize = Pages.size(size);
        LambdaQueryWrapper<Report> wrapper = new LambdaQueryWrapper<Report>().orderByDesc(Report::getCreatedAt);
        if (StringUtils.hasText(status)) {
            wrapper.eq(Report::getStatus, status.trim().toUpperCase(Locale.ROOT));
        }
        if (StringUtils.hasText(type)) {
            wrapper.eq(Report::getType, type.trim().toUpperCase(Locale.ROOT));
        }
        if (StringUtils.hasText(targetType)) {
            wrapper.eq(Report::getTargetType, targetType.trim().toUpperCase(Locale.ROOT));
        }
        if ("FEEDBACK".equalsIgnoreCase(kind)) {
            wrapper.eq(Report::getTargetType, "FEEDBACK");
        } else if ("REPORT".equalsIgnoreCase(kind)) {
            wrapper.ne(Report::getTargetType, "FEEDBACK");
        }
        if ("PENDING".equalsIgnoreCase(StringUtils.hasText(appealStatus) ? appealStatus.trim() : "")) {
            List<Long> reportIds = reportAppealMapper.selectList(new LambdaQueryWrapper<ReportAppeal>()
                            .eq(ReportAppeal::getStatus, "PENDING")
                            .select(ReportAppeal::getReportId))
                    .stream()
                    .map(ReportAppeal::getReportId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            if (reportIds.isEmpty()) {
                return new PageResult<>(List.of(), 0L, pageNo, pageSize);
            }
            wrapper.in(Report::getId, reportIds);
        }
        Page<Report> result = reportMapper.selectPage(new Page<>(pageNo, pageSize), wrapper);
        List<ReportVO> list = result.getRecords().stream().map(this::toVo).toList();
        if (StringUtils.hasText(keyword)) {
            String key = keyword.trim();
            list = list.stream()
                    .filter(item -> contains(item.reporterNickname(), key) || contains(item.targetLabel(), key))
                    .toList();
        }
        return new PageResult<>(list, result.getTotal(), pageNo, pageSize);
    }

    @Override
    public long pendingAppealCount() {
        AdminGuard.require();
        Long count = reportAppealMapper.selectCount(new LambdaQueryWrapper<ReportAppeal>()
                .eq(ReportAppeal::getStatus, "PENDING"));
        return count == null ? 0L : count;
    }

    @Override
    public ReportVO adminDetail(Long id) {
        AdminGuard.require();
        Report report = reportMapper.selectById(id);
        if (report == null) {
            throw new BizException(ErrorCode.REPORT_NOT_FOUND);
        }
        return toVo(report);
    }

    @Override
    @Transactional
    public ReportVO handle(Long id, String result, String remark) {
        LoginUser admin = AdminGuard.require();
        Report report = reportMapper.selectById(id);
        if (report == null) {
            throw new BizException(ErrorCode.REPORT_NOT_FOUND);
        }
        if (!"PENDING".equals(report.getStatus()) && !"PROCESSING".equals(report.getStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST, isFeedback(report) ? "该反馈已处理" : "该举报已处理");
        }
        String handle = normalize(result);
        Set<String> allowed = isFeedback(report) ? FEEDBACK_HANDLES : HANDLE_RESULTS;
        if (!allowed.contains(handle)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "处理结果不正确");
        }
        if (!isFeedback(report)) {
            applyHandle(report, handle);
        }
        report.setStatus("REJECT".equals(handle) ? "REJECTED" : "RESOLVED");
        report.setHandlerId(admin.userId());
        report.setHandleResult(handle);
        report.setHandleRemark(StringUtils.hasText(remark) ? remark.trim() : null);
        report.setHandledAt(LocalDateTime.now());
        reportMapper.updateById(report);
        notifyHandle(report, handle);
        operationLogService.record("REPORT_HANDLE", "REPORT", report.getId(), handle);
        return toVo(report);
    }

    @Override
    @Transactional
    public ReportAppealVO handleAppeal(Long reportId, Long appealId, String result, String remark) {
        LoginUser admin = AdminGuard.require();
        Report report = reportMapper.selectById(reportId);
        if (report == null) {
            throw new BizException(ErrorCode.REPORT_NOT_FOUND);
        }
        ReportAppeal appeal = reportAppealMapper.selectById(appealId);
        if (appeal == null || !Objects.equals(appeal.getReportId(), reportId)) {
            throw new BizException(ErrorCode.APPEAL_NOT_FOUND);
        }
        if (!"PENDING".equals(appeal.getStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "该申诉已处理");
        }
        String handle = normalize(result);
        if (!Set.of("ACCEPT", "REJECT").contains(handle)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "申诉处理结果不正确");
        }
        appeal.setStatus("ACCEPT".equals(handle) ? "ACCEPTED" : "REJECTED");
        appeal.setHandlerId(admin.userId());
        appeal.setHandleRemark(StringUtils.hasText(remark) ? remark.trim() : null);
        appeal.setHandledAt(LocalDateTime.now());
        reportAppealMapper.updateById(appeal);
        String title = "ACCEPT".equals(handle) ? "申诉已受理" : "申诉未获支持";
        String body = "ACCEPT".equals(handle)
                ? "你的申诉已受理，平台将结合复核调整账号限制。"
                : "你的申诉经复核未予支持，原处理结果维持。";
        if (StringUtils.hasText(appeal.getHandleRemark())) {
            body = body + " 说明：" + appeal.getHandleRemark();
        }
        notificationService.notifyUser(appeal.getUserId(), "SYSTEM", title, body, "REPORT", report.getId());
        operationLogService.record("REPORT_APPEAL_HANDLE", "REPORT", report.getId(), handle);
        return toAppealVo(appeal);
    }

    private void applyHandle(Report report, String handle) {
        Long userId = resolveTargetUserId(report);
        if ("REJECT".equals(handle)) {
            return;
        }
        if (userId != null) {
            SysUser user = sysUserMapper.selectById(userId);
            if (user != null) {
                int reported = user.getReportedCount() == null ? 0 : user.getReportedCount();
                user.setReportedCount(reported + 1);
                if ("RESTRICT".equals(handle)) {
                    user.setForbidPublish(1);
                    user.setForbidApply(1);
                    user.setMuted(1);
                }
                if ("BAN".equals(handle)) {
                    user.setStatus(UserStatus.BANNED.name());
                    user.setForbidPublish(1);
                    user.setForbidApply(1);
                    user.setMuted(1);
                    sysUserMapper.updateById(user);
                    accountBanService.onBanned(user, "因举报处理，你的账号已被封禁。如有异议可在「举报与反馈」中申诉。");
                    return;
                }
                sysUserMapper.updateById(user);
            }
        }
        if ("DELETE".equals(handle)) {
            if ("TASK".equals(report.getTargetType())) {
                SubstituteTask task = taskMapper.selectById(report.getTargetId());
                if (task != null
                        && !TaskStatus.COMPLETED.name().equals(task.getStatus())
                        && !TaskStatus.CANCELLED.name().equals(task.getStatus())
                        && !TaskStatus.EXPIRED.name().equals(task.getStatus())) {
                    adminTaskService.cancel(task.getId(), new CancelTaskRequest("管理员处理举报后取消"));
                }
            } else if ("MESSAGE".equals(report.getTargetType())) {
                ChatMessage message = chatMessageMapper.selectById(report.getTargetId());
                if (message != null) {
                    chatMessageMapper.deleteById(message.getId());
                }
            }
        }
    }

    private void validateTarget(Long reporterId, String targetType, Long targetId) {
        if (targetId == null || targetId <= 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "举报对象不存在");
        }
        if ("USER".equals(targetType)) {
            if (Objects.equals(reporterId, targetId)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "不能举报自己");
            }
            SysUser user = sysUserMapper.selectById(targetId);
            if (user == null || !UserRole.USER.name().equals(user.getRole())) {
                throw new BizException(ErrorCode.BAD_REQUEST, "用户不存在");
            }
            return;
        }
        if ("TASK".equals(targetType)) {
            SubstituteTask task = taskMapper.selectById(targetId);
            if (task == null) {
                throw new BizException(ErrorCode.TASK_NOT_FOUND);
            }
            if (Objects.equals(task.getPublisherId(), reporterId)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "不能举报自己的代课");
            }
            return;
        }
        ChatMessage message = chatMessageMapper.selectById(targetId);
        if (message == null) {
            throw new BizException(ErrorCode.CHAT_NOT_FOUND);
        }
        if (Objects.equals(message.getSenderId(), reporterId)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不能举报自己的消息");
        }
    }

    private List<String> sanitizeImages(Long userId, List<String> images, String folder) {
        if (images == null || images.isEmpty()) {
            return List.of();
        }
        List<String> keys = new ArrayList<>();
        String prefix = folder + "/" + userId + "/";
        for (String image : images) {
            String key = FileUrls.objectKey(image);
            if (key == null || !key.startsWith(prefix)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "证据图片无效");
            }
            keys.add(key);
            if (keys.size() >= 3) {
                break;
            }
        }
        return keys;
    }

    private Long resolveTargetUserId(Report report) {
        if (isFeedback(report)) {
            return null;
        }
        if ("USER".equals(report.getTargetType())) {
            return report.getTargetId();
        }
        if ("TASK".equals(report.getTargetType())) {
            SubstituteTask task = taskMapper.selectById(report.getTargetId());
            return task == null ? null : task.getPublisherId();
        }
        ChatMessage message = chatMessageMapper.selectById(report.getTargetId());
        return message == null ? null : message.getSenderId();
    }

    private void notifyAdmins(String title, String content, Long reportId) {
        List<SysUser> admins = sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getRole, UserRole.ADMIN.name()));
        for (SysUser admin : admins) {
            notificationService.notifyUser(admin.getId(), "SYSTEM", title, content, "REPORT", reportId);
        }
    }

    private LoginUser requireAdmin() {
        return AdminGuard.require();
    }

    private void notifyHandle(Report report, String handle) {
        Long targetUserId = resolveTargetUserId(report);
        String typeLabel = typeLabelOf(report);
        String resultLabel = HANDLE_LABELS.getOrDefault(handle, handle);
        String target = targetLabel(report);
        String reply = StringUtils.hasText(report.getHandleRemark()) ? report.getHandleRemark().trim() : null;
        if (isFeedback(report)) {
            String lead = "REJECT".equals(handle)
                    ? "你提交的反馈（" + typeLabel + "）暂未采纳。"
                    : "管理员已回复你的反馈（" + typeLabel + "）。";
            notificationService.notifyUser(
                    report.getReporterId(),
                    "SYSTEM",
                    "REJECT".equals(handle) ? "反馈未予采纳" : "反馈已回复",
                    appendHandleLines(lead, "REJECT".equals(handle) ? "未予采纳" : "已回复", reply),
                    "REPORT",
                    report.getId()
            );
            return;
        }
        if ("REJECT".equals(handle)) {
            notificationService.notifyUser(
                    report.getReporterId(),
                    "SYSTEM",
                    "举报未予处理",
                    appendHandleLines("你提交的举报（" + typeLabel + "）经核实未予处理。", "未予处理", reply),
                    "REPORT",
                    report.getId()
            );
            return;
        }
        notificationService.notifyUser(
                report.getReporterId(),
                "SYSTEM",
                "举报已处理",
                appendHandleLines("你举报的「" + target + "」（" + typeLabel + "）已处理。", resultLabel, reply),
                "REPORT",
                report.getId()
        );
        if (targetUserId != null && !"BAN".equals(handle)) {
            String title = switch (handle) {
                case "BAN" -> "账号已被封禁";
                case "RESTRICT" -> "账号功能已受限";
                case "DELETE" -> "相关内容已被处理";
                default -> "你收到一条平台警告";
            };
            notificationService.notifyUser(
                    targetUserId,
                    "SYSTEM",
                    title,
                    appendHandleLines(
                            "因举报（" + typeLabel + "），平台已对你的账号或内容进行处理。如有异议可在「举报与反馈」中申诉。",
                            resultLabel,
                            reply
                    ),
                    "REPORT",
                    report.getId()
            );
        }
    }

    private static String appendHandleLines(String lead, String result, String reply) {
        StringBuilder body = new StringBuilder(lead);
        if (StringUtils.hasText(result)) {
            body.append("\n处理结果：").append(result);
        }
        if (StringUtils.hasText(reply)) {
            body.append("\n回复：").append(reply);
        }
        return body.toString();
    }

    private boolean contains(String text, String keyword) {
        return text != null && text.contains(keyword);
    }

    private ReportVO toVo(Report report) {
        SysUser reporter = sysUserMapper.selectById(report.getReporterId());
        SysUser handler = report.getHandlerId() == null ? null : sysUserMapper.selectById(report.getHandlerId());
        Long targetUserId = resolveTargetUserId(report);
        SysUser targetUser = targetUserId == null ? null : sysUserMapper.selectById(targetUserId);
        List<String> images = readJson(report.getImagesJson()).stream().map(FileUrls::of).toList();
        return new ReportVO(
                report.getId(),
                report.getReporterId(),
                reporter == null ? "用户" : reporter.getNickname(),
                report.getTargetType(),
                report.getTargetId(),
                targetLabel(report),
                targetUserId,
                targetUser == null ? null : targetUser.getNickname(),
                targetUser == null ? null : targetUser.getStatus(),
                targetUser == null ? null : targetUser.getForbidPublish(),
                targetUser == null ? null : targetUser.getForbidApply(),
                targetUser == null ? null : targetUser.getMuted(),
                report.getType(),
                typeLabelOf(report),
                report.getDescription(),
                images,
                report.getStatus(),
                report.getHandleResult(),
                report.getHandleRemark(),
                report.getHandlerId(),
                handler == null ? null : handler.getNickname(),
                report.getCreatedAt(),
                report.getHandledAt(),
                listAppeals(report.getId())
        );
    }

    private List<ReportAppealVO> listAppeals(Long reportId) {
        return reportAppealMapper.selectList(new LambdaQueryWrapper<ReportAppeal>()
                        .eq(ReportAppeal::getReportId, reportId)
                        .orderByDesc(ReportAppeal::getId))
                .stream()
                .map(this::toAppealVo)
                .toList();
    }

    private ReportAppealVO toAppealVo(ReportAppeal appeal) {
        SysUser user = sysUserMapper.selectById(appeal.getUserId());
        SysUser handler = appeal.getHandlerId() == null ? null : sysUserMapper.selectById(appeal.getHandlerId());
        List<String> images = readJson(appeal.getImagesJson()).stream().map(FileUrls::of).toList();
        return new ReportAppealVO(
                appeal.getId(),
                appeal.getReportId(),
                appeal.getUserId(),
                user == null ? "用户" : user.getNickname(),
                appeal.getContent(),
                images,
                appeal.getStatus(),
                appeal.getHandleRemark(),
                appeal.getHandlerId(),
                handler == null ? null : handler.getNickname(),
                appeal.getCreatedAt(),
                appeal.getHandledAt()
        );
    }

    private String targetLabel(Report report) {
        if (isFeedback(report)) {
            return "管理员反馈";
        }
        if ("USER".equals(report.getTargetType())) {
            SysUser user = sysUserMapper.selectById(report.getTargetId());
            return user == null ? "用户#" + report.getTargetId() : user.getNickname();
        }
        if ("TASK".equals(report.getTargetType())) {
            SubstituteTask task = taskMapper.selectById(report.getTargetId());
            return task == null ? "代课#" + report.getTargetId() : task.getCourseNameSnapshot();
        }
        return "消息#" + report.getTargetId();
    }

    private boolean isFeedback(Report report) {
        return report != null && isFeedbackType(report.getTargetType());
    }

    private static boolean isFeedbackType(String targetType) {
        return "FEEDBACK".equals(targetType);
    }

    private String typeLabelOf(Report report) {
        if (report == null) {
            return "";
        }
        if (isFeedback(report)) {
            return FEEDBACK_TYPES.getOrDefault(report.getType(), report.getType());
        }
        return TYPES.getOrDefault(report.getType(), report.getType());
    }

    private String writeJson(List<String> images) {
        try {
            return objectMapper.writeValueAsString(images == null ? List.of() : images);
        } catch (Exception ex) {
            return "[]";
        }
    }

    private List<String> readJson(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            List<String> list = objectMapper.readValue(json, IMAGE_TYPE);
            return list == null ? List.of() : list;
        } catch (Exception ex) {
            return List.of();
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
