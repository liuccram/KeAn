package com.kean.service;

import com.kean.common.PageResult;
import com.kean.dto.CancelTaskRequest;
import com.kean.dto.CreateTaskRequest;
import com.kean.dto.TaskQuery;
import com.kean.vo.TaskVO;

public interface TaskService {

    PageResult<TaskVO> list(TaskQuery query);

    TaskVO detail(Long id);

    TaskVO create(CreateTaskRequest request);

    TaskVO update(Long id, CreateTaskRequest request);

    void delete(Long id);

    TaskVO confirm(Long id, String objectKey);

    TaskVO complete(Long id);

    TaskVO cancel(Long id, CancelTaskRequest request);

    PageResult<TaskVO> listMyPublished(Long page, Long size, Boolean excludeCancelled);

    PageResult<TaskVO> listMyApplied(Long page, Long size, Boolean excludeCancelled);
}
