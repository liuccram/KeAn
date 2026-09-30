package com.kean.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kean.entity.Campus;
import com.kean.entity.Course;
import com.kean.entity.Province;
import com.kean.entity.School;
import com.kean.entity.SysUser;
import com.kean.mapper.CampusMapper;
import com.kean.mapper.CourseMapper;
import com.kean.mapper.ProvinceMapper;
import com.kean.mapper.SchoolMapper;
import com.kean.mapper.SysUserMapper;
import com.kean.security.LoginUser;
import com.kean.security.SecurityUtils;
import com.kean.service.CatalogService;
import com.kean.vo.CampusVO;
import com.kean.vo.CourseVO;
import com.kean.vo.ProvinceVO;
import com.kean.vo.SchoolVO;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CatalogServiceImpl implements CatalogService {

    private static final long DEFAULT_SCHOOL_ID = 1L;

    private final SchoolMapper schoolMapper;
    private final CampusMapper campusMapper;
    private final CourseMapper courseMapper;
    private final SysUserMapper sysUserMapper;
    private final ProvinceMapper provinceMapper;

    public CatalogServiceImpl(
            SchoolMapper schoolMapper,
            CampusMapper campusMapper,
            CourseMapper courseMapper,
            SysUserMapper sysUserMapper,
            ProvinceMapper provinceMapper
    ) {
        this.schoolMapper = schoolMapper;
        this.campusMapper = campusMapper;
        this.courseMapper = courseMapper;
        this.sysUserMapper = sysUserMapper;
        this.provinceMapper = provinceMapper;
    }

    @Override
    public List<ProvinceVO> listProvinces() {
        return provinceMapper.selectList(new LambdaQueryWrapper<Province>()
                        .orderByAsc(Province::getSort)
                        .orderByAsc(Province::getId))
                .stream()
                .map(item -> new ProvinceVO(item.getId(), item.getName(), item.getSort()))
                .toList();
    }

    @Override
    public List<SchoolVO> listSchools(Long provinceId) {
        LambdaQueryWrapper<School> wrapper = new LambdaQueryWrapper<School>()
                .eq(School::getStatus, 1)
                .orderByAsc(School::getName)
                .orderByAsc(School::getId);
        if (provinceId != null) {
            wrapper.eq(School::getProvinceId, provinceId);
        }
        return schoolMapper.selectList(wrapper)
                .stream()
                .map(item -> new SchoolVO(item.getId(), item.getName(), item.getProvinceId()))
                .toList();
    }

    @Override
    public List<CampusVO> listCampuses(Long schoolId) {
        Long resolved = resolveSchoolId(schoolId);
        return campusMapper.selectList(new LambdaQueryWrapper<Campus>()
                        .eq(Campus::getSchoolId, resolved)
                        .eq(Campus::getStatus, 1)
                        .orderByAsc(Campus::getId))
                .stream()
                .map(item -> new CampusVO(item.getId(), item.getSchoolId(), item.getName()))
                .toList();
    }

    @Override
    public List<CourseVO> listCourses(Long schoolId) {
        Long resolved = resolveSchoolId(schoolId);
        return courseMapper.selectList(new LambdaQueryWrapper<Course>()
                        .eq(Course::getSchoolId, resolved)
                        .eq(Course::getStatus, 1)
                        .orderByAsc(Course::getId))
                .stream()
                .map(item -> new CourseVO(item.getId(), item.getSchoolId(), item.getCourseCode(), item.getCourseName()))
                .toList();
    }

    private Long resolveSchoolId(Long schoolId) {
        if (schoolId != null) {
            return schoolId;
        }
        LoginUser loginUser = SecurityUtils.currentUserOrNull();
        if (loginUser != null) {
            SysUser user = sysUserMapper.selectById(loginUser.userId());
            if (user != null && user.getSchoolId() != null) {
                return user.getSchoolId();
            }
        }
        return DEFAULT_SCHOOL_ID;
    }
}
