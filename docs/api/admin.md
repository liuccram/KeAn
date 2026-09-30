# 课安管理端 API

统一响应：`{ "code": 0, "message": "ok", "data": {} }`。`code = 0` 成功。

除特别声明外，本文件接口均需：

```http
Authorization: Bearer <admin-token>
```

且 `role=ADMIN`。非管理员返回 `403 / 40300`。建议 `SecurityConfig` 对 `/api/admin/**` 做角色校验，不依赖各 Service 自行判断。

分页默认 `page=1, size=20`，`size` 最大 50。时间字段 ISO-8601。

管理员登录仍用 `POST /api/auth/login`，见 [`auth.md`](auth.md)。

超级管理员修改自己的密码：

`PUT /api/admin/me/password`  body `{ oldPassword, newPassword }`（8–32 位）。原密码错误返回 `40000`。当前登录会话保持有效。

---

## 鉴权与日志

写操作（POST / PUT / DELETE）成功后写入 `operation_log`：

| 字段 | 来源 |
|---|---|
| admin_id / admin_name | 当前管理员 |
| operation_type | 如 `USER_BAN`、`TASK_CANCEL`、`REPORT_HANDLE` |
| target_type / target_id | 用户 / 任务 / 举报等 |
| result | `SUCCESS` / `FAIL` |
| ip | 请求 IP |
| description | 简短说明 |

失败也可记 `FAIL`，不阻断主流程。

---

## 首页与统计

### GET /api/admin/dashboard

工作台一次返回。

`data`：

```json
{
  "userTotal": 3482,
  "substituteUserTotal": 2671,
  "pendingReportTotal": 23,
  "activeTaskTotal": 186,
  "taskTrend": [
    { "date": "2026-09-16", "published": 40, "completed": 28 }
  ],
  "taskStatus": [
    { "status": "WAITING", "count": 312 },
    { "status": "APPLYING", "count": 403 }
  ],
  "recentTasks": [],
  "pendingReports": []
}
```

口径：

| 字段 | 说明 |
|---|---|
| userTotal | 学生账号未删除数 |
| substituteUserTotal | `completed_count > 0` |
| pendingReportTotal | `PENDING` + `PROCESSING` |
| activeTaskTotal | `MATCHED` + `CONFIRMED` + `IN_PROGRESS` |
| taskTrend | 最近 7 天（含今天），按 `created_at` / 进入 `COMPLETED` 的时间 |
| recentTasks | 最近 8 条，结构同管理端任务列表项 |
| pendingReports | 最近 8 条未结案，结构同举报列表项 |
| onlineUserTotal | Redis 心跳在线用户数 |

### GET /api/admin/online

返回当前在线人数。

### GET /api/admin/online/users

当前在线学生列表，结构同用户管理列表项，含 `online=true`。点击首页「实时在线」或顶栏在线人数可查看。

### GET /api/admin/stats

| 参数 | 说明 |
|---|---|
| from / to | `yyyy-MM-dd`，默认近 30 天 |

`data`：

```json
{
  "userGrowth": [{ "date": "2026-09-01", "count": 12 }],
  "taskTrend": [{ "date": "2026-09-01", "published": 9, "completed": 6 }],
  "schoolRanking": [{ "schoolId": 1, "schoolName": "山东大学", "taskCount": 402 }],
  "taskStatus": [{ "status": "WAITING", "count": 312 }],
  "reportTypes": [{ "type": "FAKE", "label": "虚假信息", "count": 23 }]
}
```

`schoolRanking` 按区间内新发布任务数降序，最多 10 条。

---

## 用户

### GET /api/admin/users

| 参数 | 说明 |
|---|---|
| keyword | 用户名 / 昵称 / 手机号 |
| schoolId / campusId | 学校、校区 |
| gender | `MALE` / `FEMALE` |
| status | `NORMAL` / `BANNED` |
| page / size | 分页 |

只返回 `role=USER`。

`data.list[]` 在 `UserVO` 基础上增加 `lastLoginAt`。`data` 另带：

- `summary`：`{ total, male, female, banned }`（相对当前筛选）
- `genderGroups[]`：各学校男女人数，按人数降序
- `statusStats`：`{ normal, banned, restricted, forbidPublish, forbidApply, muted }`（全站学生；`restricted` 为未封禁但禁发/禁申/禁言任一）

### GET /api/admin/users/{id}

详情。不存在或目标是管理员：`404`。

`data`：

```json
{
  "user": {},
  "published": { "list": [], "total": 0 },
  "applied": { "list": [], "total": 0 },
  "reviews": { "given": [], "received": [] }
}
```

任务列表各取最近 10 条，结构同 `TaskVO` 摘要（id、课程、状态、上课时间、校区）。评价取最近 10 条。

### PUT /api/admin/users/{id}/status

```json
{ "status": "BANNED", "remark": "多次虚假代课" }
```

`status`：`NORMAL` / `BANNED`。封禁时同时置 `forbidPublish=1, forbidApply=1, muted=1`，并把该用户未过期 Token 拉黑。解封只改 `status`，三项限制需单独接口恢复。

### PUT /api/admin/users/{id}/restrictions

```json
{ "forbidPublish": 1, "forbidApply": 0, "muted": 1, "days": 7, "remark": "禁发禁言" }
```

三个限制字段均可选，至少传一个。**开启**某项时必须传 `days`：`1` / `3` / `7` / `30`，到期自动解除。关闭某项不必传 `days`。封禁用户上操作限制接口返回 `400`。`UserVO` 另带 `forbidPublishUntil` / `forbidApplyUntil` / `mutedUntil`。

### PUT /api/admin/users/{id}/password

```json
{ "password": "TempPassw0rd!" }
```

规则与注册相同（8–32）。成功后拉黑该用户全部 Token。响应不回传明文密码。

---

## 代课任务

管理端列表**不**按学校裁剪，**不**默认过滤状态。

### GET /api/admin/tasks

| 参数 | 说明 |
|---|---|
| keyword | 课程名快照 / 教学楼 / 教室 / 数字则兼搜任务 ID |
| schoolId / campusId / status / taskDate | 筛选 |
| page / size | 分页 |

列表项在 `TaskVO` 摘要上增加 `schoolName`、`publisherNickname`、`applicantNickname`。

### GET /api/admin/tasks/{id}

完整 `TaskVO` +：

```json
{
  "schoolName": "山东大学",
  "cancelReason": null,
  "cancelledBy": null,
  "timeline": [
    { "at": "2026-09-20T10:00:00", "event": "CREATED", "label": "发布任务" },
    { "at": "2026-09-20T10:12:00", "event": "APPLYING", "label": "进入申请中" }
  ]
}
```

时间轴用现有字段推算，不必新建流水表：

| event | 依据 |
|---|---|
| CREATED | `created_at` |
| APPLYING | 状态曾离开 WAITING（有申请则用最早申请时间） |
| MATCHED | 有 `accepted_application_id`，用该申请 `updated_at` |
| CONFIRMED | 双方 `*_confirmed=1` 时取较晚确认相关更新 |
| IN_PROGRESS | `start_at`（若状态已到或超过） |
| COMPLETED / CANCELLED / EXPIRED | `updated_at` + 当前终态 |

跨校必须能打开。不存在才 `40401`。

### GET /api/admin/tasks/{id}/applications

管理员查看该任务全部申请，结构同用户端 `ApplicationVO`，另加申请人 `schoolName`。不校验是否为发布者。

### POST /api/admin/tasks/{id}/cancel

```json
{ "reason": "联系不上双方，平台取消" }
```

`reason` 必填，1–255 字。允许状态：`WAITING` / `APPLYING` / `MATCHED` / `CONFIRMED` / `IN_PROGRESS`。`COMPLETED` / `CANCELLED` / `EXPIRED` 返回 `40005`。

写入 `status=CANCELLED`、`cancelled_by=ADMIN`、`cancel_reason`。通知发布者；若已匹配再通知代课者。用户端 `IN_PROGRESS` 不可自取消，本接口可以。

实现落在 `TaskStatusService` 的管理员取消路径，不要复用用户取消的 3 小时锁。

---

## 举报

现有：

- `GET /api/admin/reports?status&page&size`
- `PUT /api/admin/reports/{id}`  body `{ result, remark }`

### 补齐列表参数

`type`、`targetType`（`USER` / `TASK` / `MESSAGE` / `FEEDBACK`）、`kind`（`REPORT` 仅举报 / `FEEDBACK` 仅反馈）、`keyword`（提交人昵称或对象说明）。

反馈复用 `report` 表：`targetType=FEEDBACK`、`targetId=0`，不校验举报对象、不限制重复提交。

列表 `ReportVO` 增加 `handlerId`、`handlerNickname`。

### GET /api/admin/reports/{id}

单条详情，字段同列表，必须带 `images`、`description`、`handleRemark`。不存在 `40406`。

### 处理结果（已有，补副作用）

| result | 行为 |
|---|---|
| WARN | `reported_count+1`，给目标用户发系统通知（仅举报） |
| RESTRICT | 目标用户禁发+禁申+禁言，并通知（仅举报） |
| BAN | 封禁 + 三项限制 + Token 拉黑 + 通知（仅举报） |
| DELETE | 任务走管理员取消；消息逻辑删除；用户类型不删账号，按 WARN 处理并通知（仅举报） |
| REJECT | 仅结案；举报通知「未予处理」，反馈通知「未予采纳」 |
| REPLY | 仅反馈：结案并通知用户「已回复」，备注作为回复内容 |

`status`：`REJECT` → `REJECTED`，其余 → `RESOLVED`。已结案不可再处理。

---

## 学校 / 校区 / 课程

公开 `GET /api/schools` 等保持只返回 `status=1`。管理端返回全部未删除记录，带 `status`。

### 学校

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/admin/schools` | 列表，可选 `keyword`、`province`、`status`、分页 |
| GET | `/api/admin/schools/provinces` | 已有省份列表，供筛选 |
| POST | `/api/admin/schools` | `{ "name": "山东大学", "province": "山东" }`，`province` 缺省为山东 |
| PUT | `/api/admin/schools/{id}` | `{ "name"?, "province"?, "status"? }`  status：`1` 启用 `0` 停用 |

学校 VO 含 `province`。管理端学校管理按省份筛选。

重名返回 `409`。停用后用户端选校列表消失。有关联用户或任务时仍可停用、不可物理删除。

### 校区

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/admin/campuses` | `schoolId` 必填；可选 `status` |
| POST | `/api/admin/campuses` | `{ "schoolId", "name" }` |
| PUT | `/api/admin/campuses/{id}` | `{ "name"?, "status"? }` |

校区必须属于有效学校。VO 增加 `status`、`schoolName`。

### 课程

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/admin/courses` | `schoolId` 必填 |
| POST | `/api/admin/courses` | `{ "schoolId", "courseCode", "courseName" }` |
| PUT | `/api/admin/courses/{id}` | 改名 / 改代码 / 启停 |

同一学校 `courseCode` 唯一。用户发布任务目前自填课程名，本目录不回写历史任务。

---

## 公告

新建表（Flyway `V11__announcement.sql`）：

```sql
CREATE TABLE announcement (
    id            BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    title         VARCHAR(100)  NOT NULL,
    content       VARCHAR(2000) NOT NULL,
    status        VARCHAR(16)   NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT / PUBLISHED / OFFLINE',
    scope         VARCHAR(16)   NOT NULL DEFAULT 'ALL' COMMENT 'ALL / SCHOOL',
    school_id     BIGINT        NULL,
    publisher_id  BIGINT        NOT NULL,
    published_at  DATETIME      NULL,
    deleted       TINYINT       NOT NULL DEFAULT 0,
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_announcement_status (status, published_at)
);
```

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/admin/announcements` | `status`、`keyword`、分页 |
| POST | `/api/admin/announcements` | 创建草稿或直接发布 `publish=true` |
| PUT | `/api/admin/announcements/{id}` | 仅 `DRAFT` 可改 |
| POST | `/api/admin/announcements/{id}/publish` | 草稿 → 已发布，并推通知 |
| POST | `/api/admin/announcements/{id}/offline` | 已发布 → `OFFLINE` |

创建/更新请求：

```json
{
  "title": "平台规则更新",
  "content": "禁止转让账号。",
  "scope": "ALL",
  "schoolId": null,
  "publish": false
}
```

`scope=SCHOOL` 时 `schoolId` 必填。发布时对范围内 `role=USER` 且未删除用户插入：

- `type=SYSTEM`
- `title` = 公告标题
- `content` = 公告正文（截断至 500 字）
- `bizType=ANNOUNCEMENT`，`bizId` = 公告 ID

批量插入可同步；用户量变大后再改为异步，接口形态不变。

---

## 系统

### GET /api/admin/admins

管理员列表：`id、username、nickname、status、lastLoginAt、createdAt`。不含密码。

### POST /api/admin/admins

```json
{ "username": "ops01", "password": "ChangeMe_Ops_123", "nickname": "运营一" }
```

`role` 固定 `ADMIN`。`schoolId/campusId` 空。用户名冲突 `40901`。

### PUT /api/admin/admins/{id}/status

`{ "status": "BANNED" }`。不能操作自己。库中启用中的管理员少于 1 人时不能再封。

### GET /api/admin/logs

| 参数 | 说明 |
|---|---|
| adminId | 操作人 |
| operationType | 精确匹配 |
| from / to | 时间 |
| page / size | 分页 |

只读。

### GET /api/admin/config

返回 `[{ "key", "value", "remark" }]`。键：`site.name`、`register.enabled`、`upload.image.enabled`。

### PUT /api/admin/config

```json
{ "items": [{ "key": "register.enabled", "value": "0" }, { "key": "upload.image.enabled", "value": "1" }] }
```

未知 key 返回 `400`。`register.enabled=0` 时 `POST /api/auth/register` 返回 `40305`，文案「暂未开放注册」。`upload.image.enabled=0` 时头像/聊天/封面/举报/申诉图片上传返回 `40306`，文案「暂未开放图片上传」（履约拍照仍可用）。

---

## 错误码（新增/复用）

| HTTP | code | 含义 |
|---|---|---|
| 401 | 40100 | 未登录 |
| 403 | 40300 | 非管理员或无权限 |
| 403 | 40301 | 目标账号已封禁（用户端） |
| 400 | 40000 | 参数错误 |
| 400 | 40005 | 当前状态不允许该操作 |
| 404 | 40401 / 40406 | 任务 / 举报不存在 |
| 404 | 40408 | 用户不存在（建议新增 `USER_NOT_FOUND`） |
| 404 | 40409 | 公告不存在 |
| 409 | 40901 | 用户名已存在 |
| 409 | 40910 | 学校或课程编码重复 |

---

## 与现有代码的衔接

| 现成能力 | 怎么用 |
|---|---|
| `SysUser.status / forbidPublish / forbidApply / muted` | 用户治理直接改这些字段 |
| `ReportService.handle` | 保留结果枚举，补通知与 Token 拉黑 |
| `NotificationService.notifyUser` | 处罚、取消任务、公告推送 |
| `TokenBlacklistService` | 封禁、重置密码后作废 Token |
| `TaskStatusService.isAdmin` | 接上管理员取消，绕过用户 3 小时锁 |
| `operation_log` / `sys_config` | 补 Entity / Mapper / Service |
| 公开目录 GET | 不改行为；后台另开 `/api/admin/schools` 等 |
