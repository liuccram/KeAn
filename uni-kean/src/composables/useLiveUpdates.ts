import { onHide, onShow, onUnload } from "@dcloudio/uni-app";
import { onRealtime, type RealtimeEvent } from "@/utils/realtime";
import { isImEnabled } from "@/utils/imFlag";
import { connect as connectIm, disconnect as disconnectIm, onMapped, resetImToken } from "@/utils/imSocket";
import { refreshMessageBadge } from "@/utils/messageBadge";

/**
 * 页面级实时更新订阅。
 *
 * <p><b>两条通道并存</b>（阶段 3）：</p>
 * <ol>
 *   <li>课安自研 {@code ws://<host>/ws/chat}（{@code utils/realtime.ts}）——
 *       <b>永远照旧订阅</b>，不受任何开关影响；</li>
 *   <li>box-im im-server（{@code utils/imSocket.ts}）—— <b>仅当 {@code VITE_IM_ENABLED} 为真</b>时才连接。
 *       开关关闭时下面那段代码一行都不执行（{@code isImEnabled()} 为 {@code false} 直接跳过整块）。</li>
 * </ol>
 *
 * <p>两条通道投递到的是同一个 {@code handler}，且事件形状一致
 * （{@code imSocket} 负责把 box 的 {@code cmd} 映射成 {@code RealtimeEvent}），
 * 所以页面无需知道自己收到的是哪条通道来的。<b>关闭 IM 通道 = 回到现状</b>，这就是回滚手段。</p>
 */
export function useLiveUpdates(handler: (event?: RealtimeEvent) => void, pollMs = 8000) {
  let off: (() => void) | null = null;
  let imOff: (() => void) | null = null;
  let timer: ReturnType<typeof setInterval> | null = null;

  function bind() {
    unbind();
    off = onRealtime(handler);
    bindIm(handler);
    if (pollMs > 0) {
      timer = setInterval(() => handler(), pollMs);
    }
  }

  /**
   * 绑定 box-im 通道。开关关闭时立即返回 —— 不建立连接、不注册监听、不发 HTTP 请求。
   *
   * <p>IM 通道的事件里 {@code MESSAGE}/{@code NOTICE} 会刷新未读角标；
   * {@code READ} 与 {@code realtime.ts} 的口径一致（不刷角标，避免多打一轮未读统计）；
   * 强制下线（box {@code cmd 2}）在 {@code imSocket} 内部已经直接走
   * {@code handleSessionEnded}，不会到 handler 这里。</p>
   */
  function bindIm(target: (event?: RealtimeEvent) => void) {
    if (!isImEnabled()) {
      return;
    }
    imOff = onMapped((event) => {
      // ImRealtimeEvent 是 RealtimeEvent 的交叉收窄（imSocket 里声明），
      // 所以这里直接当 RealtimeEvent 用，页面侧不需要分支。
      if (event.type === "MESSAGE" || event.type === "NOTICE") {
        refreshMessageBadge();
      }
      target(event);
    });
    // 不在每次 onShow 都重新取票：imSocket 自己按 expireAt 判断（过 60s 余量才重取）。
    connectIm().catch(() => undefined);
  }

  function unbind() {
    off?.();
    off = null;
    if (timer) {
      clearInterval(timer);
      timer = null;
    }
    unbindIm();
  }

  /** 只解绑监听并停止重连；不清 token（token 由 imSocket 自己管过期）。 */
  function unbindIm() {
    imOff?.();
    imOff = null;
    if (isImEnabled()) {
      disconnectIm();
    }
  }

  onShow(bind);
  onHide(unbind);
  onUnload(unbind);
}

/**
 * 登出/换票时清掉缓存的 IM accessToken。
 *
 * <p>导出给登出入口调用（不改变任何既有登出逻辑：调用方不调用它也完全不影响功能，
 * 只是下次连接会复用可能已失效的 token 直到过期）。</p>
 */
export function resetImChannel(): void {
  if (!isImEnabled()) {
    return;
  }
  disconnectIm();
  resetImToken();
}
