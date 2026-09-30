package com.kean.controller;

import com.kean.common.Result;
import com.kean.dto.CreateAppealRequest;
import com.kean.dto.CreateReportRequest;
import com.kean.service.ReportService;
import com.kean.vo.ReportAppealVO;
import com.kean.vo.ReportVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/types")
    public Result<List<Map<String, String>>> types(@RequestParam(required = false) String scope) {
        return Result.ok(reportService.types(scope));
    }

    @PostMapping
    public Result<ReportVO> create(@Valid @RequestBody CreateReportRequest request) {
        return Result.ok(reportService.create(
                request.targetType(),
                request.targetId(),
                request.type(),
                request.description(),
                request.images()
        ));
    }

    @GetMapping("/me")
    public Result<List<ReportVO>> mine() {
        return Result.ok(reportService.listMine());
    }

    @GetMapping("/against-me")
    public Result<List<ReportVO>> againstMe() {
        return Result.ok(reportService.listAgainstMe());
    }

    @PostMapping("/{id}/appeals")
    public Result<ReportAppealVO> appeal(@PathVariable Long id, @Valid @RequestBody CreateAppealRequest request) {
        return Result.ok(reportService.appeal(id, request.content(), request.images()));
    }
}
