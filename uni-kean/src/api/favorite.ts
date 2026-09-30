import { request } from "@/utils/request";
import type { PageResult, TaskItem } from "@/api/task";

export function listFavorites(page = 1, size = 20) {
  return request<PageResult<TaskItem>>({
    url: "/api/favorites",
    method: "GET",
    data: { page, size }
  });
}

export function addFavorite(taskId: number) {
  return request<void>({
    url: "/api/favorites",
    method: "POST",
    data: { taskId }
  });
}

export function removeFavorite(taskId: number) {
  return request<void>({
    url: `/api/favorites/${taskId}`,
    method: "DELETE"
  });
}
