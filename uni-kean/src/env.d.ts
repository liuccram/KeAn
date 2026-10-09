/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL: string
  /** box-im IM 通道开关，默认 false（只有 true/1/on/yes 才开启） */
  readonly VITE_IM_ENABLED?: string
  /** im-server 的 WebSocket 地址，如 ws://100.64.0.5:8878/im（路径必须是 /im） */
  readonly VITE_IM_WS_URL?: string
  /** im-server 基址（http/https 或 ws/wss 均可），未配 VITE_IM_WS_URL 时用它 + /im */
  readonly VITE_IM_BASE_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

interface TurnstileApi {
  render(el: string | HTMLElement, options: Record<string, unknown>): string
  reset(widgetId?: string): void
  remove(widgetId: string): void
}

interface Window {
  turnstile?: TurnstileApi
}

declare module '*.vue' {
  import { DefineComponent } from 'vue'
  const component: DefineComponent<{}, {}, any>
  export default component
}
