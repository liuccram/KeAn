package com.kean.service;

import com.kean.common.PageResult;
import com.kean.dto.CancelTaskRequest;
import com.kean.vo.AdminApplicationVO;
import com.kean.vo.AdminTaskDetailVO;
import com.kean.vo.AdminTaskItemVO;

import java.util.List;

public interface AdminTaskService {

    PageResult<AdminTaskItemVO> list(
            String keyword,
            Long schoolId,
            Long campusId,
            String status,
            String taskDate,
            Long page,
            Long size
    );

    AdminTaskDetailVO detail(Long id);

    List<AdminApplicationVO> applications(Long taskId);

    AdminTaskDetailVO cancel(Long id, CancelTaskRequest request);
}
