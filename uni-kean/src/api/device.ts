import { request } from "@/utils/request";

export interface LoginDevice {
  id: number;
  deviceName: string;
  ip?: string;
  current: boolean;
  loginCount?: number;
  lastSeenAt?: string;
  createdAt?: string;
}

export function listLoginDevices() {
  return request<LoginDevice[]>({
    url: "/api/me/devices",
    method: "GET"
  });
}

export function kickLoginDevice(id: number) {
  return request<null>({
    url: `/api/me/devices/${id}`,
    method: "DELETE"
  });
}
