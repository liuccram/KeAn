import { getToken } from "@/utils/storage";
import { resolveApiBase } from "@/utils/apiBase";

function httpBase(): string {
  const base = resolveApiBase();
  // #ifdef H5
  if (!base && typeof window !== "undefined") {
    return `${window.location.protocol}//${window.location.host}`;
  }
  // #endif
  return base;
}

export function resolveChatWsUrl(): string {
  const http = httpBase();
  const ws = http.replace(/^http/i, "ws");
  return `${ws}/ws/chat`;
}

export function chatAuthPayload(): string {
  return JSON.stringify({ type: "AUTH", token: getToken() });
}

export function chatSocketConnectOptions() {
  const token = getToken();
  return {
    url: resolveChatWsUrl(),
    header: token ? { Authorization: `Bearer ${token}` } : {},
    protocols: ["kean"],
    complete: () => undefined
  };
}
