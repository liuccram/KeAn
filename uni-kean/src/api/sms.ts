import { request } from "@/utils/request";

export interface SmsSendResult {
  sent: boolean;
  debugCode?: string | null;
  channel?: "MAIL" | "CONSOLE";
}

export function sendSms(payload: {
  email?: string;
  scene: "REGISTER" | "CHANGE_PASSWORD" | "FORGOT_PASSWORD" | "CHANGE_EMAIL";
  turnstileToken?: string;
}) {
  return request<SmsSendResult>({
    url: "/api/auth/sms",
    method: "POST",
    data: payload
  });
}

export function changePassword(payload: { smsCode: string; newPassword: string }) {
  return request<null>({
    url: "/api/auth/password",
    method: "POST",
    data: payload
  });
}

export function resetPassword(payload: { email: string; smsCode: string; newPassword: string; turnstileToken?: string }) {
  return request<null>({
    url: "/api/auth/password/reset",
    method: "POST",
    data: payload
  });
}
