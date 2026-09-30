package com.kean.controller;

import com.kean.common.PageResult;
import com.kean.common.Result;
import com.kean.dto.CreateCampusRequest;
import com.kean.dto.CreateCourseRequest;
import com.kean.dto.CreateSchoolRequest;
import com.kean.dto.UpdateCatalogRequest;
import com.kean.service.AdminCatalogService;
import com.kean.vo.AdminCampusVO;
import com.kean.vo.AdminCourseVO;
import com.kean.vo.AdminSchoolVO;
import com.kean.vo.ProvinceVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class AdminCatalogController {

    private final AdminCatalogService adminCatalogService;

    public AdminCatalogController(AdminCatalogService adminCatalogService) {
        this.adminCatalogService = adminCatalogService;
    }

    @GetMapping("/schools")
    public Result<PageResult<AdminSchoolVO>> schools(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long provinceId,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Long page,
            @RequestParam(required = false) Long size
    ) {
        return Result.ok(adminCatalogService.listSchools(keyword, provinceId, status, page, size));
    }

    @GetMapping("/schools/provinces")
    public Result<java.util.List<ProvinceVO>> provinces() {
        return Result.ok(adminCatalogService.listProvinces());
    }

    @PostMapping("/schools")
    public Result<AdminSchoolVO> createSchool(@Valid @RequestBody CreateSchoolRequest request) {
        return Result.ok(adminCatalogService.createSchool(request));
    }

    @PutMapping("/schools/{id}")
    public Result<AdminSchoolVO> updateSchool(@PathVariable Long id, @Valid @RequestBody UpdateCatalogRequest request) {
        return Result.ok(adminCatalogService.updateSchool(id, request));
    }

    @GetMapping("/campuses")
    public Result<PageResult<AdminCampusVO>> campuses(
            @RequestParam Long schoolId,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Long page,
            @RequestParam(required = false) Long size
    ) {
        return Result.ok(adminCatalogService.listCampuses(schoolId, status, page, size));
    }

    @PostMapping("/campuses")
    public Result<AdminCampusVO> createCampus(@Valid @RequestBody CreateCampusRequest request) {
        return Result.ok(adminCatalogService.createCampus(request));
    }

    @PutMapping("/campuses/{id}")
    public Result<AdminCampusVO> updateCampus(@PathVariable Long id, @Valid @RequestBody UpdateCatalogRequest request) {
        return Result.ok(adminCatalogService.updateCampus(id, request));
    }

    @GetMapping("/courses")
    public Result<PageResult<AdminCourseVO>> courses(
            @RequestParam Long schoolId,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Long page,
            @RequestParam(required = false) Long size
    ) {
        return Result.ok(adminCatalogService.listCourses(schoolId, status, page, size));
    }

    @PostMapping("/courses")
    public Result<AdminCourseVO> createCourse(@Valid @RequestBody CreateCourseRequest request) {
        return Result.ok(adminCatalogService.createCourse(request));
    }

    @PutMapping("/courses/{id}")
    public Result<AdminCourseVO> updateCourse(@PathVariable Long id, @Valid @RequestBody UpdateCatalogRequest request) {
        return Result.ok(adminCatalogService.updateCourse(id, request));
    }
}
