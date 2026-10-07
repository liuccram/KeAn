# 课安 Phase 1 认证接口

统一响应：

```json
{ "code": 0, "message": "ok", "data": {} }
```

`code = 0` 表示成功。失败时 `data` 为空，`message` 为原因。

鉴权：登录后在请求头携带

```http
Authorization: Bearer <token>
```

本阶段仅 Access Token + Redis 黑名单，无 Refresh Token。

演示学校：`schoolId = 1`（演示大学）。校区为选填的手输文本（示例里填「主校区」）。

---

## POST /api/auth/register

公开接口。注册普通学生，`role` 固定为 `USER`。

请求：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| username | string | 是 | 4-32 位，字母数字下划线 |
| password | string | 是 | 8-32 位 |
| nickname | string | 是 | 1-32 位 |
| gender | string | 是 | `MALE` / `FEMALE` |
| schoolId | number | 是 | 学校 ID |
| campusText | string | 否 | 校区名称，由用户自行填写（1-50 字，服务端去首尾空格），不填也能注册 |
| email | string | 是 | QQ 号 |
| smsCode | string | 是 | 6 位邮箱验证码 |
| turnstileToken | string | 开启时必填 | Cloudflare 一次性 token |

请求示例：

```json
{
  "username": "student01",
  "password": "Passw0rd!",
  "nickname": "小安",
  "gender": "FEMALE",
  "schoolId": 1,
  "campusText": "主校区",
  "email": "123456",
  "smsCode": "123456",
  "turnstileToken": "0.xxxx"
}
```

成功 `200`：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "id": 2,
    "role": "USER",
    "username": "student01",
    "phone": "13800000001",
    "nickname": "小安",
    "gender": "FEMALE",
    "schoolId": 1,
    "campusId": null,
    "schoolName": "演示大学",
    "campusName": "主校区",
    "completedCount": 0,
    "cancelledCount": 0,
    "reportedCount": 0,
    "status": "NORMAL",
    "forbidPublish": 0,
    "forbidApply": 0,
    "muted": 0,
    "createdAt": "2026-09-16T23:00:00"
  }
}
```

失败：

| HTTP | code | 含义 |
|---|---|---|
| 400 | 40000 | 参数校验失败 |
| 400 | 40001 | 学校或校区无效 |
| 409 | 40901 | 用户名已存在 |
| 409 | 40902 | 手机号已被注册 |

---

## GET /api/auth/turnstile

公开接口。返回是否开启 Cloudflare Turnstile，以及前端 widget 使用的 `siteKey`（公钥，可暴露）。

成功 `200`：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "enabled": true,
    "siteKey": "0x4AAAAAAA..."
  }
}
```

`enabled=false` 时后端不校验 token，登录页可不展示组件。密钥未配置时 `enabled=true` 且 `siteKey` 为空，登录会被拒绝。测试密钥（见下）同样按"未配置"返回 `siteKey = null`，前端据此显示"人机验证未配置"。
`enabled=true` 但 `site-key` 或 `secret` 为空属于服务端配置错误：任何携带/不携带 token 的请求都会被拒，返回 `40027`（与用户验证失败的 `40025` 区分开）。
Cloudflare 官方测试密钥（`1x`/`2x`/`3x` 开头、后跟一长串 `0` 的 sitekey 或 secret）同样视为服务端配置错误：等于没有防护（测试 secret 只认公开的 dummy token），后端不会再调用 Cloudflare，直接返回 `40027`。本地联调请用 `TURNSTILE_ENABLED=false`，不要填测试密钥。

---

## POST /api/auth/login

公开接口。开启 Turnstile 时必须先完成真人验证，并把一次性 token 一并提交；后端会向 Cloudflare `siteverify` 校验，失败则不签发 JWT。

请求：

```json
{
  "username": "student01",
  "password": "Passw0rd!",
  "turnstileToken": "0.xxxx"
}
```

成功 `200`：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "token": "<jwt>",
    "user": { "id": 2, "role": "USER", "username": "student01" }
  }
}
```

`user` 字段与注册返回的用户对象相同。管理员若 `mustChangePassword=true`，除改密外管理接口返回 `40307`。

失败：

| HTTP | code | 含义 |
|---|---|---|
| 400 | 40000 | 参数校验失败 |
| 400 | 40024 | 未完成真人验证 |
| 400 | 40025 | 真人验证失败（含 Cloudflare 校验失败、超时或异常） |
| 400 | 40027 | 真人验证未正确配置（服务端 `TURNSTILE_SITE_KEY` / `TURNSTILE_SECRET` 缺失或仍为 Cloudflare 测试密钥），需联系管理员 |
| 401 | 40101 | 用户名或密码错误 |
| 403 | 40301 | 账号已被封禁 |
| 429 | 42901 | 登录失败次数过多（同账号 15 分钟 5 次或同 IP 20 次） |

---

## GET /api/auth/me

需登录。返回当前用户最新资料（查库，不以 Token 声明为准）。

成功 `200`：`data` 为用户对象，含 `schoolChangeCount`（学校已修改次数，最多 3 次）。

失败：

| HTTP | code | 含义 |
|---|---|---|
| 401 | 40100 | 未登录、Token 无效或已登出 |
| 403 | 40301 | 账号已被封禁 |

---

## PUT /api/me/profile

需登录。修改昵称、性别、学校、校区。校区为选填、由用户自行填写（1-50 字，去首尾空格），传空或不传表示清空校区。

学校最多改 3 次（仅学校 ID 变化才计数）；校区为选填文本、可随时修改，清空即不再展示。修改学校后，首页只展示该校各校区发布的代课（同校内不再按校区筛选）。

请求：

```json
{
  "nickname": "小安",
  "gender": "FEMALE",
  "schoolId": 2,
  "campusText": "西校区"
}
```

成功 `200`：`data` 为最新用户对象。

失败：

| HTTP | code | 含义 |
|---|---|---|
| 400 | 40000 | 参数校验失败 |
| 400 | 40001 | 学校或校区无效 |
| 400 | 40009 | 学校最多只能修改 3 次 |
| 401 | 40100 | 未登录 |

---

## PUT /api/me/privacy

需登录。切换隐私账号开关。

请求：

```json
{ "privateAccount": 1 }
```

`privateAccount` 只能为 `0`（公开）或 `1`（隐私）。成功 `200`，`data` 为最新用户对象。

### 隐私账号语义（明确边界）

**开启后隐藏：**

- 完整主页的统计字段：性别、校区、完成数、发布/接单评分与评价列表。
  对应 `GET /api/users/{id}` 返回 `limited=true`，上述字段为 `null`。
- 可发现性：其他用户**不能主动向你发起新的私信**（`403 / 40300`，仅在新建会话时校验）；
  你们之间**已有的会话不受影响**，仍可继续聊天。私信已不区分学校，该规则对所有用户一致适用。

**开启后仍然公开（刻意保留）：**

- **头像始终公开。** 任务列表（`GET /api/tasks` 为公开接口）、聊天/黑名单/评价列表，
  以及私密账号的精简主页都会照常返回头像 URL；`/api/files/avatar/**` 允许匿名读取。
  原因是头像要在列表里渲染，而 `<img>` 无法携带 `Authorization` 头，只能走公开或签名 URL。
- 昵称、学校名。
- 封面（`coverUrl`）**从不出现在他人可见的接口里**，只有本人（`GET /api/auth/me`）和管理员能看到。

**不受影响：** 已经建立的私聊会话仍然可用；关闭开关后其他用户可再次看到完整主页。

失败：

| HTTP | code | 含义 |
|---|---|---|
| 400 | 40000 | 参数校验失败（只能 0 或 1） |
| 401 | 40100 | 未登录 |

---

## PUT /api/me/single-device

需登录。切换「仅允许一台设备在线」开关。

请求：

```json
{ "singleDevice": 1 }
```

`singleDevice` 只能为 `0`（关闭，默认）或 `1`（打开）。成功 `200`，`data` 为最新用户对象（含 `singleDevice`）。

**语义：**

- `0`（默认）：多端可同时在线；新设备登录只发「新设备登录提醒」，不踢任何设备。与引入本开关之前的行为完全一致。
  关闭开关本身不做任何清理，不会踢掉任何设备。
- `1`：**两个触发点**都会把该用户**其他**设备的登录态 `jti` 全部拉黑（TTL 按该设备 `expire_at` 计算），
  并把对应的 `login_device` 行软删（`deleted = 1`），所以「登录设备」列表此时只剩当前这一台：
  1. **打开开关的那一刻**——立即顶掉其他设备，不必等下次登录；
  2. 之后**每次登录成功**（在记录完本次设备之后）。
- 被顶掉的设备下次请求收到 `40102`，客户端提示后回到登录页；**当前设备永远不受影响**
  （打开开关时保留发起请求的这台设备，登录时保留刚登录的这台）。
- 真的顶掉了设备时会给该用户发一条 `SYSTEM` 站内通知。

失败：

| HTTP | code | 含义 |
|---|---|---|
| 400 | 40000 | 参数校验失败（只能 0 或 1） |
| 401 | 40100 | 未登录 |

---

## POST /api/me/delete-account

需登录。**注销账号，不可逆。** 必须提交当前密码，服务端用 `PasswordEncoder` 校验，
密码不对直接失败且不做任何修改。

请求：

```json
{ "password": "当前登录密码" }
```

成功 `200`，`data` 为空。**执行顺序（固定）：**

1. **校验当前密码。** 失败返回 `400 / 40000` + `当前密码不正确`，一个字节都不改。
   （刻意不用 `40101`：客户端把 401 当登录态失效，会让「密码填错」触发登出流程。）
2. **拉黑该用户全部登录设备的 `jti`**，并把这些 `login_device` 行软删（`deleted = 1`）。
   含当前请求所在的这台设备 —— 注销后没有任何设备该留下。被拉黑的设备下次请求收到 `40102`。
   TTL 与 `DELETE /api/me/devices/{id}`、`PUT /api/me/single-device` 同一套算法
   （按 device 行 `expire_at` 计算，为空兜底 7 天、下限 60 秒）。
   同时用 `TokenRevokeService.revoke(userId)` 把该用户此前签发的**所有** Token 整体作废
   （与封禁、管理员改密同一套机制）—— 只靠 jti 黑名单会漏掉「设备行早已软删、Redis 黑名单键
   又被淘汰」的旧 Token，这一层兜底把这个口子堵上。
3. **匿名化 `sys_user` 行：**

   | 列 | 注销后 |
   |---|---|
   | `email` | `NULL`（唯一索引 `uk_sys_user_email` 允许多个 NULL，不会冲突；也把邮箱释放出来可以再次注册） |
   | `phone` | `NULL`（同上，`uk_sys_user_phone`） |
   | `nickname` | `已注销用户` |
   | `avatar_url` | `NULL` |
   | `cover_url` | `NULL` |
   | `password_hash` | 随机 UUID 的 BCrypt 哈希（原文不落库，**原密码不可能再登录**） |
   | `username` | `deleted-user-{id}`（不含原邮箱；前缀 + 主键全局唯一；含 `-`，而注册/建管理员的用户名校验只允许 `[a-zA-Z0-9_]`，所以谁都注册不出来重名） |

   其余列（学校、校区、各项统计、`status` 等）不动。
4. **逻辑删除**：`deleted = 1`。**不做物理删除。**
5. **清理只属于本人、不影响他人的数据**：`notification`（本人通知）、`user_favorite`（本人收藏）、
   `user_blacklist` 中 `user_id = 本人` 的行（本人拉黑别人的记录）。
   `login_device` 已在第 2 步处理。

**刻意保留（一行都不删）：** `substitute_task`、`chat_message`、`review`、`report`。
对方的任务记录、聊天记录与信用评价都依赖它们，隐私政策六（信息保留期限）也写明
「与交易对方或平台信用体系相关的记录，注销后可能以去标识化方式保留」。
作者本人已按第 3 步匿名化。`user_blacklist` 中 `blocked_user_id = 本人` 的行属于**别人**的黑名单，
同样不动。

**副作用（已确认不会崩，但表现会降级）：** 第 4 步的 `deleted = 1` 是 MyBatis-Plus 的
`@TableLogic`，所以此后 `sysUserMapper.selectById(...)` 查不到这个用户：
任务详情里的发布者信息、聊天会话里的对方昵称、评价里的来源昵称都会走各自既有的
「用户不存在」兜底（`null` / `同学` / `发布者`），不会抛异常；已注销用户的主页
`GET /api/users/{id}` 返回 `404 / 40408`。

失败：

| HTTP | code | 含义 |
|---|---|---|
| 400 | 40000 | 参数校验失败，或 `当前密码不正确` |
| 401 | 40100 | 未登录 |
| 401 | 40102 | 登录态已被终止（例如已在其他设备退出） |
| 403 | 40301 | 账号已被封禁 |

---

## POST /api/auth/logout

需登录。将当前 Token 的 `jti` 写入 Redis 黑名单，TTL 为 Token 剩余有效期。

成功 `200`：

```json
{ "code": 0, "message": "ok", "data": null }
```

失败：`401 / 40100` 未登录或 Token 无效。

登出后再用同一 Token 访问 `/api/auth/me` 应返回 401。

---

## 种子管理员

启动时若库中没有 `role=ADMIN` 的用户，则创建：

- 用户名：`ADMIN_USERNAME`（默认 `admin`）
- 密码：`ADMIN_PASSWORD`（默认见 `.env.example`）

管理员登录同样走 `POST /api/auth/login`。
