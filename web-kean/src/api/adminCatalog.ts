import { request } from "@/utils/request";
import type { PageResult, ProvinceItem, SchoolItem, CampusItem } from "./catalog";

export interface CourseItem {
  id: number;
  schoolId: number;
  schoolName?: string;
  courseCode: string;
  courseName: string;
  status: number;
}

export function createSchool(name: string, provinceId: number) {
  return request<SchoolItem>("/api/admin/schools", { method: "POST", data: { name, provinceId } });
}

export function listSchoolProvinces() {
  return request<ProvinceItem[]>("/api/admin/schools/provinces", { method: "GET" });
}

export function updateSchool(id: number, payload: { name?: string; provinceId?: number; status?: number }) {
  return request<SchoolItem>(`/api/admin/schools/${id}`, { method: "PUT", data: payload });
}

export function createCampus(schoolId: number, name: string) {
  return request<CampusItem>("/api/admin/campuses", { method: "POST", data: { schoolId, name } });
}

export function updateCampus(id: number, payload: { name?: string; status?: number }) {
  return request<CampusItem>(`/api/admin/campuses/${id}`, { method: "PUT", data: payload });
}

export function listCourses(schoolId: number, params?: { status?: number; page?: number; size?: number }) {
  return request<PageResult<CourseItem>>("/api/admin/courses", { method: "GET", data: { schoolId, size: 50, ...params } });
}

export function createCourse(schoolId: number, courseCode: string, courseName: string) {
  return request<CourseItem>("/api/admin/courses", { method: "POST", data: { schoolId, courseCode, courseName } });
}

export function updateCourse(id: number, payload: { courseCode?: string; courseName?: string; status?: number }) {
  return request<CourseItem>(`/api/admin/courses/${id}`, { method: "PUT", data: payload });
}
