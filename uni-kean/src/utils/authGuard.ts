/**
 * 未登录访问守卫（全站登录门禁）。
 *
 * ───────────────────────────────────────────────────────────────────────────
 * 一句话口径
 * ───────────────────────────────────────────────────────────────────────────
 * 「未登录时，除了**公开页白名单**，进入任何页面都直接换栈到登录页。」
 *
 * ───────────────────────────────────────────────────────────────────────────
 * 判据：为什么用 getToken() 而不是 userStore.isLoggedIn
 * ───────────────────────────────────────────────────────────────────────────
 * 项目里有两套等价的登录态读法（都已在 grep 里确认存在）：
 *   ① `utils/storage.ts` 的 `getToken()` / `clearAuth()`（唯一持久化事实源，key = "kean_token"）；
 *   ② `store/user.ts` 的 `isLoggedIn`（= `computed(() => Boolean(state.token))`，初始值就是 `getToken()`）。
 * 本守卫取 **① `getToken()`**，理由是它永远不会和存储层不一致：
 *   · `request.ts` 里 401 / 40100 分支走的是 `clearAuth()`（只清存储、**没同步 store**），
 *     此时 `userStore.state.token` 仍可能是旧值 —— 用 store 判会误判为"已登录"；
 *   · `getToken()` 同时也覆盖了主动退出（`logoutLocal()` 内部就调 `clearAuth()`）、
 *     被顶下线 / 封禁（`handleSessionEnded` / `handleAccountBanned` 都会 `logoutLocal()`）。
 * 直接用 storage 还顺带避免了「utils 反向依赖 store」的循环（store 本身 import storage）。
 *
 * ───────────────────────────────────────────────────────────────────────────
 * 白名单（= 公开页）：**只有这 5 个前缀**
 * ───────────────────────────────────────────────────────────────────────────
 *   /pages/auth/login       登录页（守卫的落点，必须在白名单里，否则死循环）
 *   /pages/auth/register    注册页
 *   /pages/auth/forgot      忘记密码
 *   /pages/auth/turnstile   真人验证（注册流程的一环，上面三页都可能 navigateTo 过去）
 *   /pages/mine/legal       ⭐ 隐私政策页（实测路由，见下）
 * 其余页面一律要求登录。
 *
 * ⚠️ 隐私政策页路由的实测证据（别想当然）：
 *   · `pages/auth/register.vue` 第 257~259 行 `goPrivacy()` → `uni.navigateTo({ url: "/pages/mine/legal" })`
 *     —— 注册页（未登录）要勾选并打开它，所以它**必须**公开，否则注册流程会被自己的门禁打断。
 *   · `pages/mine/settings.vue` 第 36 行「隐私政策」也是 `/pages/mine/legal`。
 *   · ⚠️ 另有一个**同名易混**页面 `/pages/mine/privacy`：那是**账号隐私设置**
 *     （`wd-switch` 开关 + `updatePrivacy` 接口），是登录后功能，**不在白名单**，属于需要登录的页面。
 *   · `pages.json` 里 `pages/mine/legal` 的 navigationBarTitleText 就是「隐私政策」；
 *     `pages/mine/privacy` 的标题是「隐私」。两个都在，别搞混。
 *
 * ───────────────────────────────────────────────────────────────────────────
 * 挂在哪、为什么
 * ───────────────────────────────────────────────────────────────────────────
 * 两处，各管一段，互不重叠：
 *   ① `App.vue` 的 `onShow`（+ `onLaunch` 里注册拦截器）→ 覆盖**冷启动 / 直接打开 URL / 从后台切回**。
 *      uni-app H5 的启动顺序（已在 node_modules/@dcloudio/uni-h5/dist/uni-h5.es.js 的
 *      `setupApp` 第 16759~16795 行确认）：路由 `isReady()` 之后才依次调 App 的
 *      `onLaunch` → `onShow`，而首屏页的 `initPage(instance)` 发生在**挂载期**
 *      （同一文件 `setupPage` 第 16689~16721 行、`initPage` 第 7246 行），
 *      也就是 `onLaunch` 时页面栈还是空的 —— 所以"打开就要跳"这件事只能由 `onShow` 兜。
 *   ② `uni.addInterceptor`（navigateTo / redirectTo / reLaunch / switchTab）→ 覆盖**点击跳转**。
 *      ⚠️ 只拦"进入页面"的四个 API：**绝不拦 `navigateBack`**（返回是用户的退路，
 *      拦它会把用户困在页面里；而且返回目标页的 `onShow` 会被 `App.onShow` 之外
 *      的路径重新进入，本守卫在 `App.onShow` 上仍会兜住）。
 *      拦截器里 `return false` 会中止原跳转（uni-h5 `invokeApi`/`queue` 的既定行为，
 *      见 uni-h5.es.js 第 2845~2938 行），随后我们统一用 `uni.reLaunch` 换栈。
 *  为什么用 reLaunch 而不是 navigateTo/redirectTo：
 *    要"不留返回栈"—— 否则用户按返回又回到需要登录的页面，来回弹。
 *
 * ───────────────────────────────────────────────────────────────────────────
 * 怎么保证不死循环（三条，缺一不可）
 * ───────────────────────────────────────────────────────────────────────────
 *   1. 跳转前**先判当前页**：`redirectToLogin()` 第一件事就是 `needLogin(getCurrentRoute())`，
 *      当前页已经在白名单里（登录页当然在）就直接 return，不再跳；
 *   2. 白名单里含 `/pages/auth/login`（以及注册/忘记密码/真人验证/隐私政策），
 *      所以"跳到登录页"这个动作**永远不会**再次触发守卫；
 *   3. `isAuthRedirecting` 标志位：一次换栈没结束前，重复的守卫调用（并发请求 401、
 *      同一帧里多个导航）不会叠加第二次 reLaunch；在 success/fail 两个回调里都复位，
 *      保证"跳失败"时不会永久锁死守卫。
 *
 * ───────────────────────────────────────────────────────────────────────────
 * ⚠️ 一个需要产品确认的既有设计意图（本轮按用户原话先实施了全站门禁）
 * ───────────────────────────────────────────────────────────────────────────
 * 代码里确实存在「未登录也能看」的既有分支，加门禁后它们将**不再可达**（不是 bug，是取舍）：
 *   · `pages/home/index.vue`：`OngoingTasks v-if="userStore.isLoggedIn"`（未登录时首页
 *     只是少一块"进行中"，其余列表照常）—— 也就是说首页原本是**允许游客浏览**的。
 *   · `pages/publish/index.vue` 第 51 行、`pages/message/index.vue` 第 1342 行、
 *     `pages/mine/index.vue` 第 101 行：三处都有 `v-if="!loggedIn"` 的 guest 占位
 *     （「登录后才能发布代课」「登录后查看…」「未登录 / 登录后即可使用全部功能 + 登录按钮」）。
 * 用户本轮的原话是「用户进入后若未登录则需直接跳转到登录页」，所以按**全站登录门禁**实现。
 * 若产品其实想让首页/详情页对游客开放，只需要把对应页面加进本文件的 `PUBLIC_ROUTES` 即可，
 * 其余逻辑一行都不用动。
 * ───────────────────────────────────────────────────────────────────────────
 * 与现有 401 / 40102 处理的关系：**互不冲突、职责不重叠**
 * ───────────────────────────────────────────────────────────────────────────
 *   `utils/request.ts` 的 401/40100（`clearAuth()`）、40102（`handleSessionEnded`）、
 *   40301（`handleAccountBanned`）以及 `utils/imSocket.ts` 被顶下线的处理，都是在
 *   **请求/长连接返回之后**才发生的事，而且它们会弹 modal 说明原因再 `reLaunch` 登录页。
 *   本守卫只做"**进页面时**检查"，**一行都没改**请求层、长连接层、api 层。
 *   两者同时发生时也只是"都把人送到登录页"，是同一终点，不会互相顶掉。
 */

import { getToken } from "./storage";

/** 登录页：守卫的唯一落点 */
export const LOGIN_ROUTE = "/pages/auth/login";

/**
 * 公开页白名单（不带前导斜杠，与 `getCurrentPages()[n].route` 的返回格式一致；
 * 比对前会统一去掉前导斜杠，所以两种写法都能命中）。
 */
export const PUBLIC_ROUTES: string[] = [
  "pages/auth/login",
  "pages/auth/register",
  "pages/auth/forgot",
  // 真人验证页：注册 / 登录流程里的中间页，未登录也会被 navigateTo 过去
  "pages/auth/turnstile",
  // ⭐ 隐私政策（注册页要勾选它，法律相关，必须公开）。注意不是 pages/mine/privacy
  "pages/mine/legal"
];

/** 规范形式：恒为「以单个 / 开头、不以 / 结尾」（白名单和 getCurrentPages().route 两种写法都能对上） */
function normalizeRoute(route: string): string {
  const trimmed = String(route || "").replace(/^\/+/, "").replace(/\/+$/, "");
  return trimmed ? "/" + trimmed : "";
}

/** 统一取"路径"部分：去掉 query / hash，再规范化 */
function routePath(url: string): string {
  return normalizeRoute(String(url || "").split("?")[0].split("#")[0]);
}

/**
 * 是否公开页。
 * 用「相等 或 以 `白名单项 + '/'` 开头」判前缀（而不是裸 startsWith）：
 * `pages/auth/login` 不会误命中 `pages/auth/loginSomething`。
 * ⚠️ 两侧都过一遍 `normalizeRoute`，所以白名单写成 `pages/auth/login` 或
 *    `/pages/auth/login` 都能命中（route 与 url 两种来源因此不会互相漏判）。
 */
export function isPublicRoute(url: string): boolean {
  const path = routePath(url);
  if (!path) {
    return false;
  }
  return PUBLIC_ROUTES.some((route) => {
    const base = normalizeRoute(route);
    return Boolean(base) && (path === base || path.indexOf(base + "/") === 0);
  });
}

/** 取当前页路由（空串 = 拿不到，此时**不跳**，交给下一次 onShow / 拦截器） */
export function getCurrentRoute(): string {
  try {
    const pages = getCurrentPages();
    const last = pages[pages.length - 1] as { route?: string } | undefined;
    return last?.route || "";
  } catch {
    return "";
  }
}

/**
 * 当前是否"需要登录却被放行"。
 * 没有任何 token → 未登录；`getToken()` 永远返回字符串（没有时是空串）。
 */
export function needLogin(route: string): boolean {
  if (!route) {
    return false;
  }
  if (isPublicRoute(route)) {
    return false;
  }
  return !getToken();
}

/** 正在换栈到登录页；结束时复位（成功/失败都复位，避免跳失败后守卫永久失效） */
let isAuthRedirecting = false;

/**
 * 统一出口：换栈到登录页（不留返回栈）。
 * ⚠️ 进来的第一件事是**再判一次当前页**（死循环防护的第 1 条）。
 */
export function redirectToLogin(): void {
  if (isAuthRedirecting) {
    return;
  }
  if (!needLogin(getCurrentRoute())) {
    return;
  }
  isAuthRedirecting = true;
  const done = () => {
    isAuthRedirecting = false;
  };
  uni.reLaunch({
    url: LOGIN_ROUTE,
    success: done,
    fail: done
  });
}

/** 守卫主入口：需要登录却未登录 → 立即换栈到登录页 */
export function guardCurrentPage(): void {
  if (needLogin(getCurrentRoute())) {
    redirectToLogin();
  }
}

/** 拦截器可拦的"进入页面"API（不含 navigateBack，见文件头说明） */
const GUARDED_APIS = ["navigateTo", "redirectTo", "reLaunch", "switchTab"] as const;

/**
 * 注册导航拦截器。只做一件事：原跳转的目标页需要登录而当前未登录 → 中止原跳转 + 换栈到登录页。
 * 任何异常都吞掉并放行原跳转 —— 守卫**绝不能**成为"点不动"的原因。
 */
export function installAuthGuard(): void {
  if (typeof uni.addInterceptor !== "function") {
    return;
  }
  GUARDED_APIS.forEach((api) => {
    try {
      // ⚠️ 官方 `InterceptorOptions.invoke` 的签名声明为 `(result: any) => void`，
      //    但运行时 `invokeApi`/`queue` 明确把 `false` 当作"中止本次调用"
      //    （node_modules/@dcloudio/uni-h5/dist/uni-h5.es.js 第 2845~2938 行）。
      //    这里用 `as unknown as` 收窄：`void | false` 无法赋给 `void`，又不值得为此
      //    去改第三方类型声明。运行时行为以 uni-h5 的实现为准（已在文件头写明证据）。
      const interceptor = {
        invoke(args: { url?: string }) {
          try {
            if (needLogin(String(args?.url || ""))) {
              redirectToLogin();
              return false;
            }
          } catch {
            // 守卫自身出错时放行，不要拦住用户
          }
          return undefined;
        }
      } as unknown as UniNamespace.InterceptorOptions;
      uni.addInterceptor(api, interceptor);
    } catch {
      // 某端不支持该 API 的拦截器：跳过，App.onShow 仍然兜着
    }
  });
}
