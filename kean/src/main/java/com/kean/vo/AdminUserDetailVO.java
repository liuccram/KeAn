package com.kean.vo;

import com.kean.common.PageResult;

import java.util.List;

public record AdminUserDetailVO(
        UserVO user,
        PageResult<AdminTaskItemVO> published,
        PageResult<AdminTaskItemVO> applied,
        List<ReviewItemVO> given,
        List<ReviewItemVO> received
) {
}
