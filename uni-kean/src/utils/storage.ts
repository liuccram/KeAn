const TOKEN_KEY = "kean_token";
const USER_KEY = "kean_user";

export interface AuthUser {
  id: number;
  role: string;
  username: string;
  nickname: string;
  gender?: string | null;
  phone?: string | null;
  email?: string | null;
  avatarUrl?: string | null;
  coverUrl?: string | null;
  schoolId?: number | null;
  campusId?: number | null;
  schoolName?: string | null;
  campusName?: string | null;
  completedCount?: number | null;
  ratingAvg?: number | null;
  ratingCount?: number | null;
  publishCompletedCount?: number | null;
  publishRatingAvg?: number | null;
  publishRatingCount?: number | null;
  applyRatingAvg?: number | null;
  applyRatingCount?: number | null;
  cancelledCount?: number | null;
  reportedCount?: number | null;
  schoolChangeCount?: number | null;
  status?: string | null;
  forbidPublish?: number | null;
  forbidApply?: number | null;
  muted?: number | null;
  privateAccount?: number | null;
}

export function getToken(): string {
  return uni.getStorageSync(TOKEN_KEY) || "";
}

export function getUser(): AuthUser | null {
  return (uni.getStorageSync(USER_KEY) as AuthUser) || null;
}

export function setAuth(token: string, user: AuthUser): void {
  uni.setStorageSync(TOKEN_KEY, token);
  uni.setStorageSync(USER_KEY, user);
}

export function clearAuth(): void {
  uni.removeStorageSync(TOKEN_KEY);
  uni.removeStorageSync(USER_KEY);
}
