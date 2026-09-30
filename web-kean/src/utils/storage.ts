const TOKEN_KEY = "kean_admin_token";
const USER_KEY = "kean_admin_user";

export interface AdminUser {
  id: number;
  role: string;
  username: string;
  nickname: string;
  mustChangePassword?: boolean;
}

export function getToken(): string {
  return localStorage.getItem(TOKEN_KEY) || "";
}

export function getUser(): AdminUser | null {
  const raw = localStorage.getItem(USER_KEY);
  return raw ? (JSON.parse(raw) as AdminUser) : null;
}

export function setAuth(token: string, user: AdminUser): void {
  localStorage.setItem(TOKEN_KEY, token);
  localStorage.setItem(USER_KEY, JSON.stringify(user));
}

export function clearAuth(): void {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(USER_KEY);
}
