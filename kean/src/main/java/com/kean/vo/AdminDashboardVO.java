package com.kean.vo;

import java.time.LocalDate;
import java.util.List;

public record AdminDashboardVO(
        long userTotal,
        long substituteUserTotal,
        long pendingReportTotal,
        long activeTaskTotal,
        long onlineUserTotal,
        List<AdminTrendPointVO> taskTrend,
        List<AdminStatusCountVO> taskStatus,
        List<AdminTaskItemVO> recentTasks,
        List<ReportVO> pendingReports
) {
    public record AdminTrendPointVO(LocalDate date, long published, long completed) {
    }

    public record AdminStatusCountVO(String status, long count) {
    }

    public record AdminSchoolRankVO(Long schoolId, String schoolName, long taskCount) {
    }

    public record AdminReportTypeCountVO(String type, String label, long count) {
    }

    public record AdminDateCountVO(LocalDate date, long count) {
    }
}
