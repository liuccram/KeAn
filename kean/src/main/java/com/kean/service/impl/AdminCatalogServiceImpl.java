package com.kean.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kean.common.ErrorCode;
import com.kean.common.PageResult;
import com.kean.common.Pages;
import com.kean.dto.CreateCampusRequest;
import com.kean.dto.CreateCourseRequest;
import com.kean.dto.CreateSchoolRequest;
import com.kean.dto.UpdateCatalogRequest;
import com.kean.entity.Campus;
import com.kean.entity.Course;
import com.kean.entity.Province;
import com.kean.entity.School;
import com.kean.exception.BizException;
import com.kean.mapper.CampusMapper;
import com.kean.mapper.CourseMapper;
import com.kean.mapper.ProvinceMapper;
import com.kean.mapper.SchoolMapper;
import com.kean.security.AdminGuard;
import com.kean.service.AdminCatalogService;
import com.kean.service.OperationLogService;
import com.kean.vo.AdminCampusVO;
import com.kean.vo.AdminCourseVO;
import com.kean.vo.AdminSchoolVO;
import com.kean.vo.ProvinceVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class AdminCatalogServiceImpl implements AdminCatalogService {

    private final SchoolMapper schoolMapper;
    private final CampusMapper campusMapper;
    private final CourseMapper courseMapper;
    private final ProvinceMapper provinceMapper;
    private final OperationLogService operationLogService;

    public AdminCatalogServiceImpl(
            SchoolMapper schoolMapper,
            CampusMapper campusMapper,
            CourseMapper courseMapper,
            ProvinceMapper provinceMapper,
            OperationLogService operationLogService
    ) {
        this.schoolMapper = schoolMapper;
        this.campusMapper = campusMapper;
        this.courseMapper = courseMapper;
        this.provinceMapper = provinceMapper;
        this.operationLogService = operationLogService;
    }

    @Override
    public PageResult<AdminSchoolVO> listSchools(String keyword, Long provinceId, Integer status, Long page, Long size) {
        AdminGuard.require();
        long pageNo = Pages.page(page);
        long pageSize = Pages.size(size, Pages.CATALOG_MAX_SIZE);
        LambdaQueryWrapper<School> wrapper = new LambdaQueryWrapper<School>()
                .orderByAsc(School::getProvinceId)
                .orderByAsc(School::getName)
                .orderByAsc(School::getId);
        if (StringUtils.hasText(keyword)) {
            wrapper.like(School::getName, keyword.trim());
        }
        if (provinceId != null) {
            wrapper.eq(School::getProvinceId, provinceId);
        }
        if (status != null) {
            wrapper.eq(School::getStatus, status);
        }
        Page<School> result = schoolMapper.selectPage(new Page<>(pageNo, pageSize), wrapper);
        return new PageResult<>(result.getRecords().stream().map(this::toSchool).toList(), result.getTotal(), pageNo, pageSize);
    }

    @Override
    public java.util.List<ProvinceVO> listProvinces() {
        AdminGuard.require();
        return provinceMapper.selectList(new LambdaQueryWrapper<Province>().orderByAsc(Province::getSort).orderByAsc(Province::getId))
                .stream()
                .map(item -> new ProvinceVO(item.getId(), item.getName(), item.getSort()))
                .toList();
    }

    @Override
    @Transactional
    public AdminSchoolVO createSchool(CreateSchoolRequest request) {
        AdminGuard.require();
        String name = request.name().trim();
        assertSchoolNameFree(name, null);
        School school = new School();
        school.setName(name);
        school.setProvinceId(requireProvince(request.provinceId()).getId());
        school.setStatus(1);
        schoolMapper.insert(school);
        operationLogService.record("SCHOOL_CREATE", "SCHOOL", school.getId(), name);
        return toSchool(school);
    }

    @Override
    @Transactional
    public AdminSchoolVO updateSchool(Long id, UpdateCatalogRequest request) {
        AdminGuard.require();
        School school = schoolMapper.selectById(id);
        if (school == null) {
            throw new BizException(ErrorCode.SCHOOL_INVALID);
        }
        if (StringUtils.hasText(request.name())) {
            String name = request.name().trim();
            assertSchoolNameFree(name, id);
            school.setName(name);
        }
        if (request.provinceId() != null) {
            school.setProvinceId(requireProvince(request.provinceId()).getId());
        }
        if (request.status() != null) {
            school.setStatus(request.status() == 0 ? 0 : 1);
        }
        schoolMapper.updateById(school);
        operationLogService.record("SCHOOL_UPDATE", "SCHOOL", school.getId(), school.getName());
        return toSchool(school);
    }

    @Override
    public PageResult<AdminCampusVO> listCampuses(Long schoolId, Integer status, Long page, Long size) {
        AdminGuard.require();
        if (schoolId == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择学校");
        }
        long pageNo = Pages.page(page);
        long pageSize = Pages.size(size, Pages.CATALOG_MAX_SIZE);
        LambdaQueryWrapper<Campus> wrapper = new LambdaQueryWrapper<Campus>()
                .eq(Campus::getSchoolId, schoolId)
                .orderByAsc(Campus::getId);
        if (status != null) {
            wrapper.eq(Campus::getStatus, status);
        }
        Page<Campus> result = campusMapper.selectPage(new Page<>(pageNo, pageSize), wrapper);
        School school = schoolMapper.selectById(schoolId);
        String schoolName = school == null ? null : school.getName();
        return new PageResult<>(
                result.getRecords().stream().map(item -> toCampus(item, schoolName)).toList(),
                result.getTotal(),
                pageNo,
                pageSize
        );
    }

    @Override
    @Transactional
    public AdminCampusVO createCampus(CreateCampusRequest request) {
        AdminGuard.require();
        School school = requireSchool(request.schoolId());
        Campus campus = new Campus();
        campus.setSchoolId(school.getId());
        campus.setName(request.name().trim());
        campus.setStatus(1);
        campusMapper.insert(campus);
        operationLogService.record("CAMPUS_CREATE", "CAMPUS", campus.getId(), campus.getName());
        return toCampus(campus, school.getName());
    }

    @Override
    @Transactional
    public AdminCampusVO updateCampus(Long id, UpdateCatalogRequest request) {
        AdminGuard.require();
        Campus campus = campusMapper.selectById(id);
        if (campus == null) {
            throw new BizException(ErrorCode.SCHOOL_INVALID);
        }
        if (StringUtils.hasText(request.name())) {
            campus.setName(request.name().trim());
        }
        if (request.status() != null) {
            campus.setStatus(request.status() == 0 ? 0 : 1);
        }
        campusMapper.updateById(campus);
        School school = schoolMapper.selectById(campus.getSchoolId());
        operationLogService.record("CAMPUS_UPDATE", "CAMPUS", campus.getId(), campus.getName());
        return toCampus(campus, school == null ? null : school.getName());
    }

    @Override
    public PageResult<AdminCourseVO> listCourses(Long schoolId, Integer status, Long page, Long size) {
        AdminGuard.require();
        if (schoolId == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择学校");
        }
        long pageNo = Pages.page(page);
        long pageSize = Pages.size(size, Pages.CATALOG_MAX_SIZE);
        LambdaQueryWrapper<Course> wrapper = new LambdaQueryWrapper<Course>()
                .eq(Course::getSchoolId, schoolId)
                .orderByAsc(Course::getId);
        if (status != null) {
            wrapper.eq(Course::getStatus, status);
        }
        Page<Course> result = courseMapper.selectPage(new Page<>(pageNo, pageSize), wrapper);
        School school = schoolMapper.selectById(schoolId);
        String schoolName = school == null ? null : school.getName();
        return new PageResult<>(
                result.getRecords().stream().map(item -> toCourse(item, schoolName)).toList(),
                result.getTotal(),
                pageNo,
                pageSize
        );
    }

    @Override
    @Transactional
    public AdminCourseVO createCourse(CreateCourseRequest request) {
        AdminGuard.require();
        School school = requireSchool(request.schoolId());
        assertCourseCodeFree(school.getId(), request.courseCode().trim(), null);
        Course course = new Course();
        course.setSchoolId(school.getId());
        course.setCourseCode(request.courseCode().trim());
        course.setCourseName(request.courseName().trim());
        course.setStatus(1);
        courseMapper.insert(course);
        operationLogService.record("COURSE_CREATE", "COURSE", course.getId(), course.getCourseName());
        return toCourse(course, school.getName());
    }

    @Override
    @Transactional
    public AdminCourseVO updateCourse(Long id, UpdateCatalogRequest request) {
        AdminGuard.require();
        Course course = courseMapper.selectById(id);
        if (course == null) {
            throw new BizException(ErrorCode.COURSE_INVALID);
        }
        if (StringUtils.hasText(request.courseCode())) {
            assertCourseCodeFree(course.getSchoolId(), request.courseCode().trim(), course.getId());
            course.setCourseCode(request.courseCode().trim());
        }
        if (StringUtils.hasText(request.courseName())) {
            course.setCourseName(request.courseName().trim());
        } else if (StringUtils.hasText(request.name())) {
            course.setCourseName(request.name().trim());
        }
        if (request.status() != null) {
            course.setStatus(request.status() == 0 ? 0 : 1);
        }
        courseMapper.updateById(course);
        School school = schoolMapper.selectById(course.getSchoolId());
        operationLogService.record("COURSE_UPDATE", "COURSE", course.getId(), course.getCourseName());
        return toCourse(course, school == null ? null : school.getName());
    }

    private void assertSchoolNameFree(String name, Long excludeId) {
        LambdaQueryWrapper<School> wrapper = new LambdaQueryWrapper<School>().eq(School::getName, name);
        if (excludeId != null) {
            wrapper.ne(School::getId, excludeId);
        }
        if (schoolMapper.selectCount(wrapper) > 0) {
            throw new BizException(ErrorCode.CATALOG_DUPLICATE, "学校名称已存在");
        }
    }

    private void assertCourseCodeFree(Long schoolId, String courseCode, Long excludeId) {
        LambdaQueryWrapper<Course> wrapper = new LambdaQueryWrapper<Course>()
                .eq(Course::getSchoolId, schoolId)
                .eq(Course::getCourseCode, courseCode);
        if (excludeId != null) {
            wrapper.ne(Course::getId, excludeId);
        }
        if (courseMapper.selectCount(wrapper) > 0) {
            throw new BizException(ErrorCode.CATALOG_DUPLICATE, "该校课程代码已存在");
        }
    }

    private School requireSchool(Long id) {
        School school = schoolMapper.selectById(id);
        if (school == null) {
            throw new BizException(ErrorCode.SCHOOL_INVALID);
        }
        return school;
    }

    private AdminSchoolVO toSchool(School school) {
        Province province = school.getProvinceId() == null ? null : provinceMapper.selectById(school.getProvinceId());
        return new AdminSchoolVO(
                school.getId(),
                school.getName(),
                school.getProvinceId(),
                province == null ? null : province.getName(),
                school.getStatus(),
                school.getCreatedAt()
        );
    }

    private Province requireProvince(Long provinceId) {
        if (provinceId == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择省份");
        }
        Province province = provinceMapper.selectById(provinceId);
        if (province == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "省份不存在");
        }
        return province;
    }

    private AdminCampusVO toCampus(Campus campus, String schoolName) {
        return new AdminCampusVO(campus.getId(), campus.getSchoolId(), schoolName, campus.getName(), campus.getStatus(), campus.getCreatedAt());
    }

    private AdminCourseVO toCourse(Course course, String schoolName) {
        return new AdminCourseVO(
                course.getId(),
                course.getSchoolId(),
                schoolName,
                course.getCourseCode(),
                course.getCourseName(),
                course.getStatus(),
                course.getCreatedAt()
        );
    }
}
