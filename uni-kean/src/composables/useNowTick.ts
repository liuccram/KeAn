import { onHide, onShow, onUnload } from "@dcloudio/uni-app";
import { onMounted, onUnmounted, ref } from "vue";

export function useNowTick(ms = 30000) {
  const now = ref(Date.now());
  let timer: ReturnType<typeof setInterval> | null = null;

  function stop() {
    if (timer) {
      clearInterval(timer);
      timer = null;
    }
  }

  function start() {
    now.value = Date.now();
    stop();
    timer = setInterval(() => {
      now.value = Date.now();
    }, ms);
  }

  onMounted(start);
  onUnmounted(stop);
  onShow(start);
  onHide(stop);
  onUnload(stop);

  return now;
}
