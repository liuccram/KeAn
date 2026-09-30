import { request } from "@/utils/request";
import type { AdminUser } from "@/utils/storage";

export interface LoginResult {
  token: string;
  user: AdminUser;
}

export interface TurnstileConfig {
  enabled: boolean;
  siteKey?: string | null;
}

export function fetchTurnstileConfig() {
  return request<TurnstileConfig>("/api/auth/turnstile");
}

export function login(username: string, password: string, turnstileToken?: string) {
  return request<LoginResult>("/api/auth/login", {
    method: "POST",
    data: { username, password, turnstileToken }
  });
}

export function fetchMe() {
  return request<AdminUser>("/api/auth/me");
}

export function logout() {
  return request<null>("/api/auth/logout", { method: "POST" });
}
