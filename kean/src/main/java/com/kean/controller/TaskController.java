package com.kean.controller;

import com.kean.common.PageResult;
import com.kean.common.Result;
import com.kean.dto.CancelTaskRequest;
import com.kean.dto.ConfirmTaskRequest;
import com.kean.dto.CreateTaskRequest;
import com.kean.dto.TaskQuery;
import com.kean.service.TaskService;
import com.kean.vo.TaskVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @GetMapping
    public Result<PageResult<TaskVO>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String taskDate,
            @RequestParam(required = false) String timeSlot,
            @RequestParam(required = false) Long courseId,
            @RequestParam(required = false) Long campusId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long schoolId,
            @RequestParam(required = false) Long page,
            @RequestParam(required = false) Long size
    ) {
        return Result.ok(taskService.list(new TaskQuery(keyword, taskDate, timeSlot, courseId, campusId, status, schoolId, page, size)));
    }

    @GetMapping("/{id}")
    public Result<TaskVO> detail(@PathVariable Long id) {
        return Result.ok(taskService.detail(id));
    }

    @PostMapping
    public Result<TaskVO> create(@Valid @RequestBody CreateTaskRequest request) {
        return Result.ok(taskService.create(request));
    }

    @PutMapping("/{id}")
    public Result<TaskVO> update(@PathVariable Long id, @Valid @RequestBody CreateTaskRequest request) {
        return Result.ok(taskService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        taskService.delete(id);
        return Result.ok();
    }

    @PostMapping("/{id}/confirm")
    public Result<TaskVO> confirm(@PathVariable Long id, @Valid @RequestBody ConfirmTaskRequest request) {
        return Result.ok(taskService.confirm(id, request.objectKey()));
    }

    @PostMapping("/{id}/complete")
    public Result<TaskVO> complete(@PathVariable Long id) {
        return Result.ok(taskService.complete(id));
    }

    @PostMapping("/{id}/cancel")
    public Result<TaskVO> cancel(@PathVariable Long id, @Valid @RequestBody CancelTaskRequest request) {
        return Result.ok(taskService.cancel(id, request));
    }
}
