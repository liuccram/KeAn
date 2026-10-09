/**
 * box-im IM 通道的 feature flag。
 *
 * <p><b>默认关闭</b>：只有显式把 {@code VITE_IM_ENABLED} 设为 {@code true}/{@code 1}/{@code on}/{@code yes}
 * 才会打开。没配置、写成别的值、或在非 Vite 环境（{@code import.meta.env} 缺失）下一律为 {@code false}。</p>
 *
 * <p>与既有链路的关系：开关关闭时 {@link ./imSocket} 的 {@code connect()} 会立刻返回 ——
 * 不建立连接、不注册定时器、不发 HTTP 请求。现有 {@code utils/realtime.ts} +
 * {@code utils/ws.ts} + {@code ws://<host>/ws/chat} 链路完全照旧，行为零变化。</p>
 *
 * <p>刻意只做一个布尔判断、不在这里读 socket 地址：地址由 {@code imSocket.ts} 自己解析，
 * 本文件保持“一个 flag”的单一职责。</p>
 */

/** 开关打开时可被接受的真值写法（大小写不敏感）。 */
const TRUTHY = ["true", "1", "on", "yes"];

/**
 * 读取原始环境变量值。
 *
 * <p>不写条件编译指令：{@code import.meta.env} 在 uni-app 支持的各端都由 Vite 静态替换，
 * 而 {@code String(undefined)} 会得到 {@code "undefined"} —— 它不在 {@link TRUTHY} 里，
 * 因此“变量不存在”自然落到 {@code false}，不需要额外分支。</p>
 */
function readRawFlag(): string {
  return String(import.meta.env.VITE_IM_ENABLED ?? "").trim().toLowerCase();
}

/** IM 通道是否启用。默认 {@code false}。 */
export function isImEnabled(): boolean {
  try {
    return TRUTHY.includes(readRawFlag());
  } catch {
    // import.meta.env 在某些非 Vite 打包路径下不可用：按“未启用”处理，绝不抛错阻断启动。
    return false;
  }
}

/** 便于调试：当前 IM 通道的开关状态与来源变量名。 */
export function describeImFlag(): { enabled: boolean; source: string } {
  return { enabled: isImEnabled(), source: "VITE_IM_ENABLED" };
}
