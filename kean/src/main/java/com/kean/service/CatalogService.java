package com.kean.service;

import com.kean.vo.CampusVO;
import com.kean.vo.CourseVO;
import com.kean.vo.ProvinceVO;
import com.kean.vo.SchoolVO;

import java.util.List;

public interface CatalogService {

    List<ProvinceVO> listProvinces();

    List<SchoolVO> listSchools(Long provinceId);

    List<CampusVO> listCampuses(Long schoolId);

    List<CourseVO> listCourses(Long schoolId);
}
