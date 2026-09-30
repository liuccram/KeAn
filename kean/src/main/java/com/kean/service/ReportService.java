package com.kean.service;

import com.kean.common.PageResult;
import com.kean.vo.ReportAppealVO;
import com.kean.vo.ReportVO;

import java.util.List;
import java.util.Map;

public interface ReportService {

    List<Map<String, String>> types();

    List<Map<String, String>> types(String scope);

    ReportVO create(String targetType, Long targetId, String type, String description, List<String> images);

    List<ReportVO> listMine();

    List<ReportVO> listAgainstMe();

    ReportAppealVO appeal(Long reportId, String content, List<String> images);

    PageResult<ReportVO> adminList(String status, String type, String targetType, String appealStatus, String kind, String keyword, Long page, Long size);

    long pendingAppealCount();

    ReportVO adminDetail(Long id);

    ReportVO handle(Long id, String result, String remark);

    ReportAppealVO handleAppeal(Long reportId, Long appealId, String result, String remark);
}
