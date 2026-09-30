import { request } from "@/utils/request";

export interface ProvinceItem {
  id: number;
  name: string;
  sort?: number;
}

export interface SchoolItem {
  id: number;
  name: string;
  provinceId?: number;
}

export interface CampusItem {
  id: number;
  schoolId: number;
  name: string;
}

export interface CourseItem {
  id: number;
  schoolId: number;
  courseCode: string;
  courseName: string;
}

export function listProvinces() {
  return request<ProvinceItem[]>({ url: "/api/provinces", method: "GET" });
}

export function listSchools(provinceId?: number) {
  return request<SchoolItem[]>({
    url: "/api/schools",
    method: "GET",
    data: provinceId ? { provinceId } : undefined
  });
}

export function listCampuses(schoolId?: number) {
  return request<CampusItem[]>({
    url: "/api/campuses",
    method: "GET",
    data: schoolId ? { schoolId } : undefined
  });
}

export function listCourses(schoolId?: number) {
  return request<CourseItem[]>({
    url: "/api/courses",
    method: "GET",
    data: schoolId ? { schoolId } : undefined
  });
}
