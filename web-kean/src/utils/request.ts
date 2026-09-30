import axios from "axios";
import { ElMessage } from "element-plus";
import { clearAuth, getToken } from "./storage";

export interface ApiResult<T> {
  code: number;
  message: string;
  data: T;
}

const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || "",
  timeout: 15000
});

http.interceptors.request.use((config) => {
  const token = getToken();
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

http.interceptors.response.use(
  (response) => {
    const body = response.data as ApiResult<unknown>;
    if (body && typeof body.code === "number" && body.code !== 0) {
      ElMessage.error(body.message || "请求失败");
      return Promise.reject(new Error(body.message || "请求失败"));
    }
    return response;
  },
  (error) => {
    const status = error.response?.status;
    const message = error.response?.data?.message || error.message || "网络异常";
    const code = error.response?.data?.code;
    if (status === 401) {
      clearAuth();
      if (!location.hash.includes("/login") && !location.pathname.includes("/login")) {
        location.href = "/login";
      }
    }
    if (code !== 40307) {
      ElMessage.error(message);
    }
    return Promise.reject(error);
  }
);

export async function request<T>(url: string, options: { method?: string; data?: unknown } = {}) {
  const method = (options.method || "GET").toUpperCase();
  const response = await http.request<ApiResult<T>>({
    url,
    method,
    params: method === "GET" ? options.data : undefined,
    data: method === "GET" ? undefined : options.data
  });
  return response.data.data;
}
