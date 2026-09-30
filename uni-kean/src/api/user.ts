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
