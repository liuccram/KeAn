package com.kean.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kean.common.ErrorCode;
import com.kean.entity.School;
import com.kean.entity.SubstituteTask;
import com.kean.entity.SysUser;
import com.kean.enums.TaskStatus;
import com.kean.enums.UserRole;
import com.kean.exception.BizException;
import com.kean.mapper.AdminStatsMapper;
import com.kean.mapper.ReportMapper;
import com.kean.mapper.SchoolMapper;
import com.kean.mapper.SubstituteTaskMapper;
import com.kean.mapper.SysUserMapper;
import com.kean.security.AdminGuard;
import com.kean.service.AdminDashboardService;
import com.kean.service.AdminTaskService;
import com.kean.service.PresenceService;
import com.kean.service.ReportService;
import com.kean.vo.AdminDashboardVO;
import com.kean.vo.AdminStatsVO;
import com.kean.vo.AdminTaskItemVO;
import com.kean.vo.ReportVO;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AdminDashboardServiceImpl implements AdminDashboardService {

    private static final Map<String, String> REPORT_TYPES = new LinkedHashMap<>();

    static {
        REPORT_TYPES.put("FAKE", "虚假信息");
        REPORT_TYPES.put("HARASS", "骚扰辱骂");
        REPORT_TYPES.put("MALICIOUS_CANCEL", "恶意取消");
        REPORT_TYPES.put("FRAUD", "欺诈诱导");
        REPORT_TYPES.put("VIOLATION", "违规内容");
        REPORT_TYPES.put("OTHER", "其他");
    }

    private final SysUserMapper sysUserMapper;
    private final SubstituteTaskMapper taskMapper;
    private final ReportMapper reportMapper;
    private final SchoolMapper schoolMapper;
    private final AdminStatsMapper adminStatsMapper;
    private final AdminTaskService adminTaskService;
    private final ReportService reportService;
    private final PresenceService presenceService;

    public AdminDashboardServiceImpl(
            SysUserMapper sysUserMapper,
            SubstituteTaskMapper taskMapper,
            ReportMapper reportMapper,
            SchoolMapper schoolMapper,
            AdminStatsMapper adminStatsMapper,
            AdminTaskService adminTaskService,
            ReportService reportService,
            PresenceService presenceService
    ) {
        this.sysUserMapper = sysUserMapper;
        this.taskMapper = taskMapper;
        this.reportMapper = reportMapper;
        this.schoolMapper = schoolMapper;
        this.adminStatsMapper = adminStatsMapper;
        this.adminTaskService = adminTaskService;
        this.reportService = reportService;
        this.presenceService = presenceService;
    }

    @Override
    public AdminDashboardVO dashboard() {
        AdminGuard.require();
        long userTotal = nz(sysUserMapper.selectCount(new LambdaQueryWrapper<SysUser>().eq(SysUser::getRole, UserRole.USER.name())));
        long substituteUserTotal = nz(sysUserMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getRole, UserRole.USER.name())
                .gt(SysUser::getCompletedCount, 0)));
        long pendingReportTotal = nz(reportMapper.selectCount(new LambdaQueryWrapper<com.kean.entity.Report>()
                .in(com.kean.entity.Report::getStatus, "PENDING", "PROCESSING")));
        long activeTaskTotal = nz(taskMapper.selectCount(new LambdaQueryWrapper<SubstituteTask>()
                .in(SubstituteTask::getStatus,
                        TaskStatus.MATCHED.name(),
                        TaskStatus.CONFIRMED.name(),
                        TaskStatus.IN_PROGRESS.name())));
        LocalDate today = LocalDate.now();
        LocalDate from = today.minusDays(6);
        List<AdminDashboardVO.AdminTrendPointVO> trend = mergeTrend(from, today.plusDays(1),
                adminStatsMapper.taskPublished(from.atStartOfDay(), today.plusDays(1).atStartOfDay()),
                adminStatsMapper.taskCompleted(from.atStartOfDay(), today.plusDays(1).atStartOfDay()));
        List<AdminDashboardVO.AdminStatusCountVO> status = toStatus(adminStatsMapper.taskStatusCounts());
        List<AdminTaskItemVO> recentTasks = adminTaskService.list(null, null, null, null, null, 1L, 8L).list();
        List<ReportVO> pendingReports = reportService.adminList("PENDING", null, null, null, null, null, 1L, 8L).list();
        return new AdminDashboardVO(userTotal, substituteUserTotal, pendingReportTotal, activeTaskTotal, presenceService.onlineCount(), trend, status, recentTasks, pendingReports);
    }

    @Override
    public AdminStatsVO stats(String from, String to) {
        AdminGuard.require();
        LocalDate toDate = parseDate(to, LocalDate.now());
        LocalDate fromDate = parseDate(from, toDate.minusDays(29));
        if (fromDate.isAfter(toDate)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "开始日期不能晚于结束日期");
        }
        LocalDateTime fromTime = fromDate.atStartOfDay();
        LocalDateTime toTime = toDate.plusDays(1).atStartOfDay();
        List<AdminDashboardVO.AdminDateCountVO> userGrowth = fillDates(fromDate, toDate, adminStatsMapper.userGrowth(fromTime, toTime));
        List<AdminDashboardVO.AdminTrendPointVO> taskTrend = mergeTrend(fromDate, toDate.plusDays(1),
                adminStatsMapper.taskPublished(fromTime, toTime),
                adminStatsMapper.taskCompleted(fromTime, toTime));
        List<AdminDashboardVO.AdminSchoolRankVO> ranking = adminStatsMapper.schoolRanking(fromTime, toTime).stream()
                .map(row -> {
                    School school = row.getSchoolId() == null ? null : schoolMapper.selectById(row.getSchoolId());
                    return new AdminDashboardVO.AdminSchoolRankVO(
                            row.getSchoolId(),
                            school == null ? "未知学校" : school.getName(),
                            nz(row.getCnt())
                    );
                })
                .toList();
        return new AdminStatsVO(
                userGrowth,
                taskTrend,
                ranking,
                toStatus(adminStatsMapper.taskStatusCounts()),
                adminStatsMapper.reportTypeCounts(fromTime, toTime).stream()
                        .map(row -> new AdminDashboardVO.AdminReportTypeCountVO(
                                row.getType(),
                                REPORT_TYPES.getOrDefault(row.getType(), row.getType()),
                                nz(row.getCnt())
                        ))
                        .toList()
        );
    }

    private List<AdminDashboardVO.AdminTrendPointVO> mergeTrend(
            LocalDate fromInclusive,
            LocalDate toExclusive,
            List<AdminStatsMapper.DateCountRow> published,
            List<AdminStatsMapper.DateCountRow> completed
    ) {
        Map<LocalDate, Long> pub = toMap(published);
        Map<LocalDate, Long> done = toMap(completed);
        List<AdminDashboardVO.AdminTrendPointVO> list = new ArrayList<>();
        for (LocalDate date = fromInclusive; date.isBefore(toExclusive); date = date.plusDays(1)) {
            list.add(new AdminDashboardVO.AdminTrendPointVO(date, pub.getOrDefault(date, 0L), done.getOrDefault(date, 0L)));
        }
        return list;
    }

    private List<AdminDashboardVO.AdminDateCountVO> fillDates(LocalDate from, LocalDate to, List<AdminStatsMapper.DateCountRow> rows) {
        Map<LocalDate, Long> map = toMap(rows);
        List<AdminDashboardVO.AdminDateCountVO> list = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            list.add(new AdminDashboardVO.AdminDateCountVO(date, map.getOrDefault(date, 0L)));
        }
        return list;
    }

    private Map<LocalDate, Long> toMap(List<AdminStatsMapper.DateCountRow> rows) {
        Map<LocalDate, Long> map = new LinkedHashMap<>();
        if (rows == null) {
            return map;
        }
        for (AdminStatsMapper.DateCountRow row : rows) {
            if (row.getStatDate() != null) {
                map.put(row.getStatDate(), nz(row.getCnt()));
            }
        }
        return map;
    }

    private List<AdminDashboardVO.AdminStatusCountVO> toStatus(List<AdminStatsMapper.StatusCountRow> rows) {
        Map<String, Long> map = new LinkedHashMap<>();
        for (String status : List.of(
                TaskStatus.WAITING.name(),
                TaskStatus.APPLYING.name(),
                TaskStatus.MATCHED.name(),
                TaskStatus.CONFIRMED.name(),
                TaskStatus.IN_PROGRESS.name(),
                TaskStatus.COMPLETED.name(),
                TaskStatus.CANCELLED.name(),
                TaskStatus.EXPIRED.name()
        )) {
            map.put(status, 0L);
        }
        if (rows != null) {
            for (AdminStatsMapper.StatusCountRow row : rows) {
                if (row.getStatus() != null) {
                    map.put(row.getStatus(), nz(row.getCnt()));
                }
            }
        }
        return map.entrySet().stream()
                .map(entry -> new AdminDashboardVO.AdminStatusCountVO(entry.getKey(), entry.getValue()))
                .toList();
    }

    private LocalDate parseDate(String raw, LocalDate fallback) {
        if (!StringUtils.hasText(raw)) {
            return fallback;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException ex) {
            throw new BizException(ErrorCode.BAD_REQUEST, "日期格式应为 yyyy-MM-dd");
        }
    }

    private long nz(Long value) {
        return value == null ? 0 : value;
    }
}
