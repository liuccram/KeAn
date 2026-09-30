package com.kean.service;

import com.kean.common.PageResult;
import com.kean.dto.CreateAnnouncementRequest;
import com.kean.vo.AnnouncementVO;

public interface AdminAnnouncementService {

    PageResult<AnnouncementVO> list(String status, String keyword, Long page, Long size);

    AnnouncementVO create(CreateAnnouncementRequest request);

    AnnouncementVO update(Long id, CreateAnnouncementRequest request);

    AnnouncementVO publish(Long id);

    AnnouncementVO offline(Long id);
}
