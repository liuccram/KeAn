import { request } from "@/utils/request";

/**
 * box-im 兼容 token 的取票接口（阶段 D2 新增）。
 *
 * <p>对应后端新增端点 {@code GET /api/im/token}（{@code ImController}）。
 * 走的就是课安原有的 {@code request()}：自动带上 {@code Authorization: Bearer <kean token>}，
 * 因此不需要任何新的鉴权通道。</p>
 *
 * <p>该端点<b>总是返回 200</b>，用 {@code enabled} 表达“后端 IM 通道是否可用” ——
 * 后端没配 {@code IM_JWT_SECRET} 时 {@code enabled=false}，其余字段为 null。
 * 调用方应把 {@code enabled=false} 当成“正常但不可用”，而不是错误。</p>
 */
export interface ImToken {
  /** 后端是否已启用 IM（等价于是否配好了 IM_JWT_SECRET） */
  enabled: boolean;
  /** 发给 im-server {@code {cmd:0}} 登录帧的 accessToken；enabled=false 时为 null */
  accessToken: string | null;
  /** 双 token 里的 refreshToken；im-platform 未部署时无服务端消费方 */
  refreshToken: string | null;
  /** accessToken 的绝对过期时间（epoch 毫秒） */
  expireAt: number | null;
}

/**
 * 取一对 box-im 兼容 token。
 *
 * <p>不传 {@code terminal} 时由后端按 {@code X-Kean-Device} 请求头推断，推不出就按 {@code app}。
 * 需要精确控制时传 {@code "web"} / {@code "app"} / {@code "pc"}。</p>
 */
export function getImToken(terminal?: "web" | "app" | "pc") {
  const query = terminal ? `?terminal=${terminal}` : "";
  return request<ImToken>({
    url: `/api/im/token${query}`,
    method: "GET"
  });
}
