import { request } from "@/utils/request";
import type { AuthUser } from "@/utils/storage";

export interface LoginPayload {
  username: string;
  password: string;
  turnstileToken?: string;
}

export interface TurnstileConfig {
  enabled: boolean;
  siteKey?: string | null;
}

export interface RegisterPayload {
  username: string;
  password: string;
  nickname: string;
  gender: string;
  schoolId: number;
  /** 校区选填，由用户手输（1-50 字）；不填时不传或传 null */
  campusText?: string | null;
  email: string;
  smsCode: string;
  turnstileToken?: string;
}

export interface LoginResult {
  token: string;
  user: AuthUser;
}

export function login(payload: LoginPayload) {
  return request<LoginResult>({
    url: "/api/auth/login",
    method: "POST",
    data: payload
  });
}

export function fetchTurnstileConfig() {
  return request<TurnstileConfig>({
    url: "/api/auth/turnstile",
    method: "GET"
  });
}

export function register(payload: RegisterPayload) {
  return request<AuthUser>({
    url: "/api/auth/register",
    method: "POST",
    data: payload
  });
}

export function fetchMe() {
  return request<AuthUser>({
    url: "/api/auth/me",
    method: "GET"
  });
}

export interface UpdateProfilePayload {
  nickname: string;
  gender: string;
  schoolId: number;
  /** 校区选填，由用户手输（1-50 字）；传 null 或不传表示清空校区 */
  campusText?: string | null;
}

export function updateProfile(payload: UpdateProfilePayload) {
  return request<AuthUser>({
    url: "/api/me/profile",
    method: "PUT",
    data: payload
  });
}

export function updateAvatar(objectKey: string) {
  return request<AuthUser>({
    url: "/api/me/avatar",
    method: "PUT",
    data: { objectKey }
  });
}

export function updateCover(objectKey?: string | null) {
  return request<AuthUser>({
    url: "/api/me/cover",
    method: "PUT",
    data: { objectKey: objectKey || "" }
  });
}

export function changeEmail(payload: { email: string; smsCode: string }) {
  return request<AuthUser>({
    url: "/api/me/email",
    method: "PUT",
    data: payload
  });
}

export interface DeleteAccountPayload {
  /** 当前登录密码，服务端用 PasswordEncoder 校验，错误时不做任何修改 */
  password: string;
}

/**
 * 注销账号（不可逆）。必须带当前密码；成功后服务端会：拉黑全部设备登录态 →
 * 匿名化并逻辑删除账号 → 清理只属于本人的数据。
 * 调用成功后客户端要清掉本地登录态并回登录页（见 pages/mine/security.vue）。
 */
export function deleteAccount(payload: DeleteAccountPayload) {
  return request<null>({
    url: "/api/me/delete-account",
    method: "POST",
    data: payload
  });
}

export function logout() {
  return request<null>({
    url: "/api/auth/logout",
    method: "POST"
  });
}

export function heartbeat() {
  return request<null>({
    url: "/api/me/heartbeat",
    method: "POST"
  });
}
