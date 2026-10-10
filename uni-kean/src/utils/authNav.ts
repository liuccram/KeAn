/**
 * auth 三页（登录 / 注册 / 忘记密码）共用的返回动作。
 *
 * 为什么必须单独抽出来：这三个页面在 pages.json 里都是 `navigationStyle: "custom"`，
 * 没有原生导航栏，**页面上那个返回按钮是唯一的出口**。而 uni 的页面栈有三种进法：
 *   1. navigateTo（正常压栈）                → 栈里有上一页，navigateBack 能用；
 *   2. redirectTo / reLaunch / 直接在浏览器打开 URL / App 冷启动直达
 *      → 当前页就是栈底，getCurrentPages().length === 1，此时 navigateBack() 会**静默失败**
 *        （H5 与小程序都不抛错、也不返回，只是什么也不发生）→ 用户就被困在页面里出不去；
 *   3. 栈里还有上一页，但上一页是同一套 auth 流程里的中间页。
 *
 * 所以统一成「先判断能不能返回，不能返回就换栈到登录页」：
 * 登录页是 auth 流程的入口页，任何入口进来都一定出得去。
 * 这里不额外挂 uni.navigateBack 的 fail 回调 —— 那是全局按键（H5 后退 / 安卓物理返回），
 * 挂上去会连带影响系统返回行为，不是本次要动的范围。
 */

/** auth 流程的入口页：退无可退时统一落到这里 */
const AUTH_ENTRY = "/pages/auth/login";

/**
 * 当前页面栈里是否还有上一页。
 * 用项目里既有的写法（pages/mine/crop.vue、utils/request.ts 都是 `getCurrentPages()`），
 * 不用 uni.getCurrentPages()，避免某些端上方法名不一致；并整体 try 兜住极端环境的异常。
 */
export function canGoBack(): boolean {
  try {
    return getCurrentPages().length > 1;
  } catch {
    return false;
  }
}

/**
 * 稳健返回：
 * - 能返回 → uni.navigateBack()
 * - 不能返回（栈底 / redirectTo 进来 / 直接打开 URL）→ uni.redirectTo 回登录页
 *
 * 用 redirectTo 而不是 reLaunch：登录页不是 tabBar 页，redirectTo 就能到；
 * 而且它只替换当前页、清掉这个已经没用的栈底，不会把别的页面一起清掉。
 * 注意 navigateBack 是同步调用，这里不返回它的返回值（不同端返回类型不一致，
 * 不依赖它来判断成功与否）。
 */
export function goBack(): void {
  if (canGoBack()) {
    uni.navigateBack();
    return;
  }
  uni.redirectTo({ url: AUTH_ENTRY });
}
