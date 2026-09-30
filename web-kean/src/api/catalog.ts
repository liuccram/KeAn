import { request } from "@/utils/request";

export interface PageResult<T> {
  list: T[];
  total: number;
  page: number;
  size: number;
}

export interface SchoolItem {
  id: number;
  name: string;
  provinceId?: number;
  provinceName?: string;
  province?: string;
  status?: number;
}

export interface CampusItem {
  id: number;
  schoolId: number;
  schoolName?: string;
  name: string;
  status?: number;
}

export interface ProvinceItem {
  id: number;
  name: string;
  sort?: number;
}

export function listProvinces() {
  return request<ProvinceItem[]>("/api/provinces", { method: "GET" });
}

export function listSchools(params?: { keyword?: string; provinceId?: number; status?: number; page?: number; size?: number }) {
  return request<PageResult<SchoolItem>>("/api/admin/schools", { method: "GET", data: { size: 500, ...params } });
}

export function listCampuses(schoolId: number, params?: { status?: number; page?: number; size?: number }) {
  return request<PageResult<CampusItem>>("/api/admin/campuses", {
    method: "GET",
    data: { schoolId, size: 200, ...params }
  });
}
