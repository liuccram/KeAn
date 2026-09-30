package com.kean.vo;

import java.util.List;

public record AdminStatsVO(
        List<AdminDashboardVO.AdminDateCountVO> userGrowth,
        List<AdminDashboardVO.AdminTrendPointVO> taskTrend,
        List<AdminDashboardVO.AdminSchoolRankVO> schoolRanking,
        List<AdminDashboardVO.AdminStatusCountVO> taskStatus,
        List<AdminDashboardVO.AdminReportTypeCountVO> reportTypes
) {
}
