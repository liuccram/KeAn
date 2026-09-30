import { request } from "@/utils/request";
import type { PageResult } from "@/api/task";

export interface ReviewItem {
  id: number;
  taskId: number;
  courseName: string;
  fromNickname: string;
  fromAvatarUrl?: string | null;
  rating: number;
  tags?: string[];
  content?: string | null;
  targetRole?: string | null;
  createdAt: string;
}

export interface ReviewPendingItem {
  taskId: number;
  courseName: string;
  peerNickname: string;
  role: string;
}

export interface MyReviewsResult {
  publishRatingAvg?: number | null;
  publishRatingCount: number;
  publishCompletedCount: number;
  applyRatingAvg?: number | null;
  applyRatingCount: number;
  applyCompletedCount: number;
  page: PageResult<ReviewItem>;
}

export function listReviewTags() {
  return request<string[]>({
    url: "/api/reviews/tags",
    method: "GET"
  });
}

export function listPendingReviews() {
  return request<ReviewPendingItem[]>({
    url: "/api/reviews/pending",
    method: "GET"
  });
}

export function createReview(payload: { taskId: number; rating: number; tags?: string[]; content?: string }) {
  return request<ReviewItem>({
    url: "/api/reviews",
    method: "POST",
    data: payload
  });
}

export function listMyReviews(page = 1, size = 20) {
  return request<MyReviewsResult>({
    url: "/api/reviews/me",
    method: "GET",
    data: { page, size }
  });
}
