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
  campusId: number;
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
  campusId: number;
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
