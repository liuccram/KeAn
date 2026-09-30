package com.kean.vo;

import com.kean.common.PageResult;

import java.util.List;

public record AdminUserPageVO(
        List<UserVO> list,
        long total,
        long page,
        long size,
        AdminUserSummaryVO summary,
        List<AdminGenderGroupVO> genderGroups,
        AdminUserStatusVO statusStats
) {
    public static AdminUserPageVO of(
            PageResult<UserVO> page,
            AdminUserSummaryVO summary,
            List<AdminGenderGroupVO> genderGroups,
            AdminUserStatusVO statusStats
    ) {
        return new AdminUserPageVO(page.list(), page.total(), page.page(), page.size(), summary, genderGroups, statusStats);
    }
}
