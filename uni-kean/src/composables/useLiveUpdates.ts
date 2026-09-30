import { onHide, onShow, onUnload } from "@dcloudio/uni-app";
import { onRealtime, type RealtimeEvent } from "@/utils/realtime";

export function useLiveUpdates(handler: (event?: RealtimeEvent) => void, pollMs = 8000) {
  let off: (() => void) | null = null;
  let timer: ReturnType<typeof setInterval> | null = null;

  function bind() {
    unbind();
    off = onRealtime(handler);
    if (pollMs > 0) {
      timer = setInterval(() => handler(), pollMs);
    }
  }

  function unbind() {
    off?.();
    off = null;
    if (timer) {
      clearInterval(timer);
      timer = null;
    }
  }

  onShow(bind);
  onHide(unbind);
  onUnload(unbind);
}
