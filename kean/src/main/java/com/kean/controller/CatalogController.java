package com.kean.controller;

import com.kean.common.Result;
import com.kean.service.CatalogService;
import com.kean.vo.CampusVO;
import com.kean.vo.CourseVO;
import com.kean.vo.ProvinceVO;
import com.kean.vo.SchoolVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class CatalogController {

    private final CatalogService catalogService;

    public CatalogController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping("/provinces")
    public Result<List<ProvinceVO>> provinces() {
        return Result.ok(catalogService.listProvinces());
    }

    @GetMapping("/schools")
    public Result<List<SchoolVO>> schools(@RequestParam(required = false) Long provinceId) {
        return Result.ok(catalogService.listSchools(provinceId));
    }

    @GetMapping("/campuses")
    public Result<List<CampusVO>> campuses(@RequestParam(required = false) Long schoolId) {
        return Result.ok(catalogService.listCampuses(schoolId));
    }

    @GetMapping("/courses")
    public Result<List<CourseVO>> courses(@RequestParam(required = false) Long schoolId) {
        return Result.ok(catalogService.listCourses(schoolId));
    }
}
