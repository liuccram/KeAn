package com.kean.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kean.entity.Announcement;
import com.kean.entity.School;
import com.kean.entity.SysUser;
import com.kean.mapper.AnnouncementMapper;
import com.kean.mapper.SchoolMapper;
import com.kean.mapper.SysUserMapper;
import com.kean.security.LoginUser;
import com.kean.security.SecurityUtils;
import com.kean.service.UserAnnouncementService;
import com.kean.vo.AnnouncementVO;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class UserAnnouncementServiceImpl implements UserAnnouncementService {

    private final AnnouncementMapper announcementMapper;
    private final SchoolMapper schoolMapper;
    private final SysUserMapper sysUserMapper;

    public UserAnnouncementServiceImpl(
            AnnouncementMapper announcementMapper,
            SchoolMapper schoolMapper,
            SysUserMapper sysUserMapper
    ) {
        this.announcementMapper = announcementMapper;
        this.schoolMapper = schoolMapper;
        this.sysUserMapper = sysUserMapper;
    }

    @Override
    public List<AnnouncementVO> active() {
        Long schoolId = currentSchoolId();
        LambdaQueryWrapper<Announcement> wrapper = new LambdaQueryWrapper<Announcement>()
                .eq(Announcement::getStatus, "PUBLISHED")
                .and(w -> {
                    w.eq(Announcement::getScope, "ALL");
                    if (schoolId != null) {
                        w.or(x -> x.eq(Announcement::getScope, "SCHOOL").eq(Announcement::getSchoolId, schoolId));
                    }
                })
                .orderByDesc(Announcement::getPublishedAt)
                .orderByDesc(Announcement::getId)
                .last("LIMIT 10");
        return announcementMapper.selectList(wrapper).stream().map(this::toVo).toList();
    }

    private Long currentSchoolId() {
        LoginUser login = SecurityUtils.currentUserOrNull();
        if (login == null) {
            return null;
        }
        SysUser user = sysUserMapper.selectById(login.userId());
        return user == null ? null : user.getSchoolId();
    }

    private AnnouncementVO toVo(Announcement announcement) {
        String schoolName = null;
        if (announcement.getSchoolId() != null) {
            School school = schoolMapper.selectById(announcement.getSchoolId());
            if (school != null) {
                schoolName = school.getName();
            }
        }
        return new AnnouncementVO(
                announcement.getId(),
                announcement.getTitle(),
                announcement.getContent(),
                announcement.getStatus(),
                announcement.getScope(),
                announcement.getSchoolId(),
                schoolName,
                announcement.getPublisherId(),
                null,
                announcement.getPublishedAt(),
                announcement.getCreatedAt(),
                announcement.getTargetUserId()
        );
    }
}
