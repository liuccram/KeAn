import { computed, reactive } from "vue";
import { clearAuth, getToken, getUser, setAuth, type AuthUser } from "@/utils/storage";
import { stopRealtime } from "@/utils/realtime";
import { disconnect as disconnectIm, resetImToken } from "@/utils/imSocket";

const state = reactive({
  token: getToken(),
  user: getUser() as AuthUser | null
});

/**
 * 登出时断开两条实时通道。
 *
 * <p>登出出口很多（主动退出、被顶下线、封禁、账号注销、401/40102 token 失效），
 * 它们最终都收敛到 {@code logoutLocal()}（或 {@code clearAuth()}），所以统一在这里断，
 * 而不是在五六个调用点各写一遍。</p>
 *
 * <p>为什么以前不用管：自研 {@code utils/ws.ts} 那条通道自己会看 token（App.vue 的心跳里
 * {@code startRealtime} 拿不到 token 就 {@code stopRealtime}），而 box im-server 的连接
 * 由 {@code useLiveUpdates} 的 onHide/onUnload 管理，登出后如果停在当前页面就会一直挂着 ——
 * 已经是登录态之外还保持着一根连接，不合理。</p>
 *
 * <p>这里直接调 {@code imSocket} 的 {@code disconnect()} 而不是
 * {@code useLiveUpdates} 的 {@code resetImChannel()}：语义一样（后者就是
 * {@code disconnect() + resetImToken()} 的组合），但 store 不该依赖组合式函数
 * （组合式函数里是页面生命周期注册，在这里调用没有意义），少一层依赖。</p>
 *
 * <p><b>不能因为断连失败就登不出去</b>：每个断开动作都各自 try/catch，异常只吞掉。</p>
 */
function closeRealtimeChannels(): void {
  try {
    stopRealtime();
  } catch {
    // 断连失败不影响登出
  }
  try {
    disconnectIm();
  } catch {
    // 同上：IM 通道关不掉也要把登录态清干净
  }
  try {
    // 清掉缓存的 IM accessToken：否则下次登录可能复用上一个账号（或已过期被作废）的票据。
    resetImToken();
  } catch {
    // ignore
  }
}

export function useUserStore() {
  const isLoggedIn = computed(() => Boolean(state.token));
  return {
    state,
    isLoggedIn,
    setLogin(token: string, user: AuthUser) {
      state.token = token;
      state.user = user;
      setAuth(token, user);
    },
    logoutLocal() {
      closeRealtimeChannels();
      state.token = "";
      state.user = null;
      clearAuth();
    }
  };
}
