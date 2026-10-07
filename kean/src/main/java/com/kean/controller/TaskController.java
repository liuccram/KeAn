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
            // 已废弃的校区筛选：刻意用 String 接收并丢弃，不再做 Long 绑定（原因见方法开头注释）。
            @RequestParam(required = false) String campusId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long schoolId,
            @RequestParam(required = false) Long page,
            @RequestParam(required = false) Long size
    ) {
        // campusId 一律传 null：TaskServiceImpl.list() 本来就不读它（校区不再是筛选条件），
        // 这与 docs/api/tasks.md 里「campusId 已废弃：传了也不过滤」的约定一致。
        //
        // 之所以把入参声明从 Long 改成 String：本轮改动把校区从「目录 ID」降级成发布者手输文本，
        // 旧客户端 / 缓存包 / 第三方调用只要把校区文本（或空串）塞进 campusId，Long 绑定就会抛
        // MethodArgumentTypeMismatchException，被 GlobalExceptionHandler 统一映射成
        // HTTP 400 / code 40000 —— 请求在进入本方法前就失败，整条列表请求直接挂掉
        // （首页任务列表因此完全加载不出来）。改成 String 后收下任意内容但不使用：
        // 传什么都不会再参与过滤，也不会再把列表请求打成 400。
        return Result.ok(taskService.list(new TaskQuery(keyword, taskDate, timeSlot, courseId, null, status, schoolId, page, size)));
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
