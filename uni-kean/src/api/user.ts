import { request } from "@/utils/request";

export interface PublicReview {
  id: number;
  taskId: number;
  courseName?: string;
  fromNickname?: string;
  rating: number;
  tags?: string[];
  content?: string | null;
  targetRole?: string | null;
  createdAt?: string;
}

export interface PublicProfile {
  id: number;
  nickname: string;
  avatarUrl?: string | null;
  gender?: string | null;
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
  privateAccount?: number;
  limited?: boolean;
  mine?: boolean;
  reviews?: PublicReview[];
}

export function getPublicProfile(id: number) {
  return request<PublicProfile>({
    url: `/api/users/${id}`,
    method: "GET"
  });
}

export function updatePrivacy(privateAccount: 0 | 1) {
  return request<PublicProfile & { username?: string }>({
    url: "/api/me/privacy",
    method: "PUT",
    data: { privateAccount }
  });
}

// 「仅允许一台设备在线」的开关接口 updateSingleDevice（PUT /api/me/single-device）已随
// 「暂时去掉单设备入口」一起从客户端移除：该功能在后端已被全局配置停用
// （kean.security.single-device.enabled 默认 false，详见 docs/ops/security-hardening.md）。
// 后端接口与 sys_user.single_device 列都保留着，将来要恢复时只需改环境变量，
// 再把这里的调用函数与 pages/mine/security.vue 的开关加回来即可。
