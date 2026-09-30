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

演示学校：`schoolId = 1`（演示大学），`campusId = 1`（主校区）。

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
| campusId | number | 是 | 校区 ID，须属于该学校 |
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
  "campusId": 1,
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
    "campusId": 1,
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

`enabled=false` 时后端不校验 token，登录页可不展示组件。密钥未配置时 `enabled=true` 且 `siteKey` 为空，登录会被拒绝。

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
| 400 | 40025 | 真人验证失败或未配置 |
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

需登录。修改昵称、性别、学校、校区。

学校最多改 3 次（仅学校 ID 变化才计数）；校区可随时改。修改学校后，首页只展示该校各校区发布的代课。

请求：

```json
{
  "nickname": "小安",
  "gender": "FEMALE",
  "schoolId": 2,
  "campusId": 8
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
