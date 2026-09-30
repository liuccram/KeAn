package com.kean.controller;

import com.kean.common.PageResult;
import com.kean.common.Result;
import com.kean.dto.HandleAppealRequest;
import com.kean.dto.HandleReportRequest;
import com.kean.service.ReportService;
import com.kean.vo.ReportAppealVO;
import com.kean.vo.ReportVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/reports")
public class AdminReportController {

    private final ReportService reportService;

    public AdminReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping
    public Result<PageResult<ReportVO>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String appealStatus,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long page,
            @RequestParam(required = false) Long size
    ) {
        return Result.ok(reportService.adminList(status, type, targetType, appealStatus, kind, keyword, page, size));
    }

    @GetMapping("/appeals/pending-count")
    public Result<Long> pendingAppealCount() {
        return Result.ok(reportService.pendingAppealCount());
    }

    @GetMapping("/{id}")
    public Result<ReportVO> detail(@PathVariable Long id) {
        return Result.ok(reportService.adminDetail(id));
    }

    @PutMapping("/{id}")
    public Result<ReportVO> handle(@PathVariable Long id, @Valid @RequestBody HandleReportRequest request) {
        return Result.ok(reportService.handle(id, request.result(), request.remark()));
    }

    @PutMapping("/{id}/appeals/{appealId}")
    public Result<ReportAppealVO> handleAppeal(
            @PathVariable Long id,
            @PathVariable Long appealId,
            @Valid @RequestBody HandleAppealRequest request
    ) {
        return Result.ok(reportService.handleAppeal(id, appealId, request.result(), request.remark()));
    }
}
