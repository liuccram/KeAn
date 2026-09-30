package com.kean.service;

import com.kean.vo.AdminDashboardVO;
import com.kean.vo.AdminStatsVO;

public interface AdminDashboardService {

    AdminDashboardVO dashboard();

    AdminStatsVO stats(String from, String to);
}
