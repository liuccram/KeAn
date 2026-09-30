package com.kean.service;

import com.kean.common.PageResult;
import com.kean.dto.CreateCampusRequest;
import com.kean.dto.CreateCourseRequest;
import com.kean.dto.CreateSchoolRequest;
import com.kean.dto.UpdateCatalogRequest;
import com.kean.vo.AdminCampusVO;
import com.kean.vo.AdminCourseVO;
import com.kean.vo.AdminSchoolVO;
import com.kean.vo.ProvinceVO;

import java.util.List;

public interface AdminCatalogService {

    PageResult<AdminSchoolVO> listSchools(String keyword, Long provinceId, Integer status, Long page, Long size);

    List<ProvinceVO> listProvinces();

    AdminSchoolVO createSchool(CreateSchoolRequest request);

    AdminSchoolVO updateSchool(Long id, UpdateCatalogRequest request);

    PageResult<AdminCampusVO> listCampuses(Long schoolId, Integer status, Long page, Long size);

    AdminCampusVO createCampus(CreateCampusRequest request);

    AdminCampusVO updateCampus(Long id, UpdateCatalogRequest request);

    PageResult<AdminCourseVO> listCourses(Long schoolId, Integer status, Long page, Long size);

    AdminCourseVO createCourse(CreateCourseRequest request);

    AdminCourseVO updateCourse(Long id, UpdateCatalogRequest request);
}
