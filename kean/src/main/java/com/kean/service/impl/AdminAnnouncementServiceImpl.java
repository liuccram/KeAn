package com.kean.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kean.common.ErrorCode;
import com.kean.common.PageResult;
import com.kean.common.Pages;
import com.kean.dto.CreateAnnouncementRequest;
import com.kean.entity.Announcement;
import com.kean.entity.School;
import com.kean.entity.SysUser;
import com.kean.exception.BizException;
import com.kean.mapper.AnnouncementMapper;
import com.kean.mapper.SchoolMapper;
import com.kean.mapper.SysUserMapper;
import com.kean.security.AdminGuard;
import com.kean.security.LoginUser;
import com.kean.service.AdminAnnouncementService;
import com.kean.service.OperationLogService;
import com.kean.vo.AnnouncementVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

@Service
public class AdminAnnouncementServiceImpl implements AdminAnnouncementService {

    private final AnnouncementMapper announcementMapper;
    private final SchoolMapper schoolMapper;
    private final SysUserMapper sysUserMapper;
    private final OperationLogService operationLogService;

    public AdminAnnouncementServiceImpl(
            AnnouncementMapper announcementMapper,
            SchoolMapper schoolMapper,
            SysUserMapper sysUserMapper,
            OperationLogService operationLogService
    ) {
        this.announcementMapper = announcementMapper;
        this.schoolMapper = schoolMapper;
        this.sysUserMapper = sysUserMapper;
        this.operationLogService = operationLogService;
    }

    @Override
    public PageResult<AnnouncementVO> list(String status, String keyword, Long page, Long size) {
        AdminGuard.require();
        long pageNo = Pages.page(page);
        long pageSize = Pages.size(size);
        LambdaQueryWrapper<Announcement> wrapper = new LambdaQueryWrapper<Announcement>().orderByDesc(Announcement::getId);
        if (StringUtils.hasText(status)) {
            wrapper.eq(Announcement::getStatus, status.trim().toUpperCase());
        }
        if (StringUtils.hasText(keyword)) {
            wrapper.like(Announcement::getTitle, keyword.trim());
        }
        Page<Announcement> result = announcementMapper.selectPage(new Page<>(pageNo, pageSize), wrapper);
        return new PageResult<>(result.getRecords().stream().map(this::toVo).toList(), result.getTotal(), pageNo, pageSize);
    }

    @Override
    @Transactional
    public AnnouncementVO create(CreateAnnouncementRequest request) {
        LoginUser admin = AdminGuard.require();
        Announcement announcement = new Announcement();
        fill(announcement, request);
        announcement.setPublisherId(admin.userId());
        announcement.setStatus("DRAFT");
        announcementMapper.insert(announcement);
        if (Boolean.TRUE.equals(request.publish())) {
            return publish(announcement.getId());
        }
        operationLogService.record("ANNOUNCEMENT_CREATE", "ANNOUNCEMENT", announcement.getId(), announcement.getTitle());
        return toVo(announcement);
    }

    @Override
    @Transactional
    public AnnouncementVO update(Long id, CreateAnnouncementRequest request) {
        AdminGuard.require();
        Announcement announcement = require(id);
        if (!"DRAFT".equals(announcement.getStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "仅草稿可修改");
        }
        fill(announcement, request);
        announcementMapper.updateById(announcement);
        operationLogService.record("ANNOUNCEMENT_UPDATE", "ANNOUNCEMENT", announcement.getId(), announcement.getTitle());
        if (Boolean.TRUE.equals(request.publish())) {
            return publish(announcement.getId());
        }
        return toVo(announcement);
    }

    @Override
    @Transactional
    public AnnouncementVO publish(Long id) {
        AdminGuard.require();
        Announcement announcement = require(id);
        if ("PUBLISHED".equals(announcement.getStatus())) {
            return toVo(announcement);
        }
        if ("OFFLINE".equals(announcement.getStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "已下线公告不能再发布");
        }
        announcement.setStatus("PUBLISHED");
        announcement.setPublishedAt(LocalDateTime.now());
        announcementMapper.updateById(announcement);
        operationLogService.record("ANNOUNCEMENT_PUBLISH", "ANNOUNCEMENT", announcement.getId(), announcement.getTitle());
        return toVo(announcement);
    }

    @Override
    @Transactional
    public AnnouncementVO offline(Long id) {
        AdminGuard.require();
        Announcement announcement = require(id);
        if (!"PUBLISHED".equals(announcement.getStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "仅已发布公告可下线");
        }
        announcement.setStatus("OFFLINE");
        announcementMapper.updateById(announcement);
        operationLogService.record("ANNOUNCEMENT_OFFLINE", "ANNOUNCEMENT", announcement.getId(), announcement.getTitle());
        return toVo(announcement);
    }

    private void fill(Announcement announcement, CreateAnnouncementRequest request) {
        String scope = request.scope().trim().toUpperCase();
        if ("SCHOOL".equals(scope) && request.schoolId() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "按学校发布时请选择学校");
        }
        if ("SCHOOL".equals(scope) && schoolMapper.selectById(request.schoolId()) == null) {
            throw new BizException(ErrorCode.SCHOOL_INVALID);
        }
        announcement.setTitle(request.title().trim());
        announcement.setContent(request.content().trim());
        announcement.setScope(scope);
        announcement.setSchoolId("SCHOOL".equals(scope) ? request.schoolId() : null);
    }

    private Announcement require(Long id) {
        Announcement announcement = announcementMapper.selectById(id);
        if (announcement == null) {
            throw new BizException(ErrorCode.ANNOUNCEMENT_NOT_FOUND);
        }
        return announcement;
    }

    private AnnouncementVO toVo(Announcement announcement) {
        String schoolName = null;
        if (announcement.getSchoolId() != null) {
            School school = schoolMapper.selectById(announcement.getSchoolId());
            if (school != null) {
                schoolName = school.getName();
            }
        }
        SysUser publisher = sysUserMapper.selectById(announcement.getPublisherId());
        return new AnnouncementVO(
                announcement.getId(),
                announcement.getTitle(),
                announcement.getContent(),
                announcement.getStatus(),
                announcement.getScope(),
                announcement.getSchoolId(),
                schoolName,
                announcement.getPublisherId(),
                publisher == null ? null : publisher.getNickname(),
                announcement.getPublishedAt(),
                announcement.getCreatedAt(),
                announcement.getTargetUserId()
        );
    }
}
