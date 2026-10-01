import { resolveApiBase } from "./apiBase";
import { prepareImageForUpload } from "./image";
import { clearAuth, getToken } from "./storage";
import { useUserStore } from "@/store/user";

export interface ApiResult<T> {
  code: number;
  message: string;
  data: T;
}

interface RequestOptions {
  url: string;
  method?: UniApp.RequestOptions["method"];
  data?: unknown;
}

const REQUEST_TIMEOUT_MS = 15000;
let handlingBan = false;

function currentRoute() {
  try {
    const pages = getCurrentPages();
    const last = pages[pages.length - 1] as { route?: string } | undefined;
    return last?.route || "";
  } catch {
    return "";
  }
}

export function handleAccountBanned(message?: string) {
  if (handlingBan) {
    return;
  }
  handlingBan = true;
  try {
    useUserStore().logoutLocal();
  } catch {
    clearAuth();
  }
  const content = message || "你的账号因违规已被封禁，如有疑问请联系平台。";
  const goLogin = () => {
    handlingBan = false;
    if (currentRoute().includes("auth/login")) {
      return;
    }
    uni.reLaunch({ url: "/pages/auth/login" });
  };
  uni.showModal({
    title: "账号已被封禁",
    content,
    showCancel: false,
    confirmText: "知道了",
    success: goLogin,
    fail: goLogin
  });
}

function deviceLabel() {
  try {
    const info = uni.getSystemInfoSync() as {
      deviceBrand?: string;
      brand?: string;
      deviceModel?: string;
      model?: string;
      osName?: string;
      platform?: string;
      osVersion?: string;
      system?: string;
    };
    const parts = [
      info.deviceBrand || info.brand,
      info.deviceModel || info.model,
      info.osName || info.platform,
      info.osVersion || info.system
    ].filter(Boolean);
    const name = parts.join(" ").replace(/\s+/g, " ").trim();
    return name.slice(0, 120) || "未知设备";
  } catch {
    return "未知设备";
  }
}

function resolveBaseUrl(): string {
  return resolveApiBase();
}

export function buildUrl(path: string): string {
  if (/^https?:\/\//i.test(path)) {
    return path;
  }
  const normalized = path.startsWith("/") ? path : `/${path}`;
  const base = resolveBaseUrl();
  if (!base) {
    return normalized;
  }
  return `${base}${normalized}`;
}

export function resolveMediaUrl(path?: string | null): string {
  if (!path) {
    return "";
  }
  if (/^https?:\/\//i.test(path) || path.startsWith("data:")) {
    return path;
  }
  if (path.startsWith("/api/files/")) {
    return buildUrl(path);
  }
  return buildUrl(`/api/files/${path.replace(/^\//, "")}`);
}

export interface UploadedFile {
  objectKey: string;
  url: string;
}

export type UploadScene = "AVATAR" | "CHAT" | "REPORT" | "APPEAL" | "FULFILL" | "COVER";

export type UploadPhase = "preparing" | "uploading";

export interface UploadProgress {
  /** preparing = 本地转码（HEIC 之类可能耗时数秒）；uploading = 正在传输 */
  phase: UploadPhase;
  /** 0-100；preparing 阶段恒为 0 */
  percent: number;
  /** 第几次尝试，从 1 开始 —— 重试时界面可以提示"正在重试" */
  attempt: number;
}

export interface UploadOptions {
  onProgress?: (progress: UploadProgress) => void;
  /** 失败自动重试次数，默认 2（最多尝试 3 次）。只对传输失败与 5xx/429 重试 */
  retries?: number;
}

const UPLOAD_TIMEOUT_MS = 30000;
const DEFAULT_UPLOAD_RETRIES = 2;
const RETRY_BASE_DELAY_MS = 400;

/**
 * 用普通 Error + 标记位而不是 class extends Error：
 * uni-app 可能编译到 ES5，那种情况下继承内置 Error 会让 instanceof 失效。
 */
function uploadFailure(message: string, retryable: boolean): Error {
  const error = new Error(message) as Error & { retryable?: boolean };
  error.retryable = retryable;
  return error;
}

function isRetryable(error: Error): boolean {
  return (error as { retryable?: boolean }).retryable !== false;
}

/** 4xx 业务错误（格式不支持、图片过大、未登录等）重试没有意义，只有 5xx/429/传输失败值得重试。 */
function isRetryableStatus(status?: number): boolean {
  if (status == null) {
    return true;
  }
  return status >= 500 || status === 429;
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function uploadOnce(
  readyPath: string,
  scene: UploadScene,
  header: Record<string, string>,
  attempt: number,
  onProgress?: (progress: UploadProgress) => void
): Promise<UploadedFile> {
  return new Promise((resolve, reject) => {
    const task = uni.uploadFile({
      url: buildUrl("/api/files"),
      filePath: readyPath,
      name: "file",
      formData: { scene },
      header,
      timeout: UPLOAD_TIMEOUT_MS,
      success: (res) => {
        const body = parseBody<UploadedFile>(res.data);
        if (res.statusCode === 401 || body?.code === 40100) {
          clearAuth();
          reject(uploadFailure(body?.message || "未登录或登录已失效", false));
          return;
        }
        if (res.statusCode >= 200 && res.statusCode < 300 && body?.code === 0 && body.data) {
          resolve(body.data);
          return;
        }
        reject(uploadFailure(
          body?.message || `上传失败(${res.statusCode})`,
          isRetryableStatus(res.statusCode)
        ));
      },
      fail: (err) => {
        // 传输层失败（断网、超时）都值得重试。
        reject(uploadFailure(err.errMsg || "图片上传失败", true));
      }
    });
    task?.onProgressUpdate?.((res) => {
      const percent = Math.min(100, Math.max(0, Math.round(res.progress || 0)));
      onProgress?.({ phase: "uploading", percent, attempt });
    });
  });
}

export async function uploadFile(
  filePath: string,
  scene: UploadScene,
  options: UploadOptions = {}
): Promise<UploadedFile> {
  const retries = Math.max(0, options.retries ?? DEFAULT_UPLOAD_RETRIES);
  const token = getToken();
  const header: Record<string, string> = {};
  if (token) {
    header.Authorization = `Bearer ${token}`;
  }

  // 转码在本地完成，HEIC 之类可能耗时数秒 —— 先播报"处理中"，否则界面看起来像卡死。
  options.onProgress?.({ phase: "preparing", percent: 0, attempt: 1 });
  const readyPath = await prepareImageForUpload(filePath);

  let lastError: Error | null = null;
  for (let attempt = 1; attempt <= retries + 1; attempt += 1) {
    try {
      options.onProgress?.({ phase: "uploading", percent: 0, attempt });
      // 转码结果在多次尝试之间复用，不重复转码。
      return await uploadOnce(readyPath, scene, header, attempt, options.onProgress);
    } catch (error) {
      const failure = error instanceof Error ? error : new Error(String(error));
      lastError = failure;
      if (!isRetryable(failure) || attempt > retries) {
        break;
      }
      await delay(RETRY_BASE_DELAY_MS * attempt);
    }
  }
  throw lastError ?? new Error("图片上传失败");
}

function parseBody<T>(raw: unknown): ApiResult<T> | null {
  if (raw == null) {
    return null;
  }
  if (typeof raw === "string") {
    try {
      return JSON.parse(raw) as ApiResult<T>;
    } catch {
      return null;
    }
  }
  if (typeof raw === "object") {
    return raw as ApiResult<T>;
  }
  return null;
}

export function request<T>(options: RequestOptions): Promise<T> {
  const token = getToken();
  const method = options.method || "GET";
  const header: Record<string, string> = {};
  if (method !== "GET") {
    header["Content-Type"] = "application/json";
  }
  if (token) {
    header.Authorization = `Bearer ${token}`;
  }
  header["X-Kean-Device"] = deviceLabel();

  const url = buildUrl(options.url);
  // #ifdef APP-PLUS
  if (!/^https?:\/\//i.test(url)) {
    return Promise.reject(new Error("接口地址必须是 http/https，请配置 VITE_API_BASE_URL"));
  }
  // #endif

  const payload = options.data;
  const hasData = payload !== undefined && payload !== null
    && !(typeof payload === "object" && !Array.isArray(payload) && Object.keys(payload as object).length === 0);

  return new Promise((resolve, reject) => {
    uni.request({
      url,
      method,
      data: hasData ? payload : undefined,
      header,
      timeout: REQUEST_TIMEOUT_MS,
      success: (res) => {
        const body = parseBody<T>(res.data);
        if (body?.code === 40301) {
          handleAccountBanned(body.message);
          reject(new Error(body.message || "账号已被封禁"));
          return;
        }
        if (res.statusCode === 401 || body?.code === 40100) {
          clearAuth();
          reject(new Error(body?.message || "未登录或登录已失效"));
          return;
        }
        if (res.statusCode >= 200 && res.statusCode < 300 && body?.code === 0) {
          resolve(body.data);
          return;
        }
        reject(new Error(body?.message || `请求失败(${res.statusCode})`));
      },
      fail: (err) => {
        const msg = err.errMsg || "网络异常";
        const host = resolveApiBase() || "后端";
        if (/timeout/i.test(msg)) {
          reject(new Error(`请求超时，请确认 ${host} 已启动，且手机与电脑同一 WiFi`));
          return;
        }
        reject(new Error(`无法连接 ${host}（${msg}）`));
      }
    });
  });
}
