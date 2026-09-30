# 课安 Phase 3 申请与状态流转

统一响应：`{ "code": 0, "message": "ok", "data": {} }`。`code = 0` 成功。

除列表/详情浏览外，本文件接口均需登录：`Authorization: Bearer <token>`。

状态只由 `TaskStatusService` 写入。

主路径：`WAITING → APPLYING → MATCHED → CONFIRMED → IN_PROGRESS → COMPLETED`

- 第一条待处理申请：`WAITING → APPLYING`
- 待处理申请清空：`APPLYING → WAITING`
- 发布者接受一人，其余待处理自动拒绝：`APPLYING → MATCHED`
- 双方履约确认：`MATCHED → CONFIRMED`（若已过 `start_at` 则直接 `IN_PROGRESS`）
- 定时扫描：`CONFIRMED` 且 `now >= start_at` → `IN_PROGRESS`
- 双方完成确认：`IN_PROGRESS → COMPLETED`
- `WAITING` / `APPLYING` 且已到开始时间 → `EXPIRED`
- 取消：`WAITING`/`APPLYING` 仅发布者；`MATCHED` 发布者或已选代课者；`CONFIRMED` 双方；`IN_PROGRESS` 用户不可取消

---

## POST /api/tasks/{id}/applications

申请代课。不能申请自己的任务。仅 `WAITING`/`APPLYING`。同一任务已有待处理/已接受/已拒绝申请时不能再申请；**撤回后（`CANCELLED`）可再次申请**，会复活原记录。

任务若设置 `genderRequirement` 为 `MALE`/`FEMALE`，申请人性别必须匹配。

请求：

```json
{ "message": "我可以代这节课" }
```

`message` 选填，最长 500。响应为任务详情。

---

## GET /api/tasks/{id}/applications

发布者查看该任务全部申请。

```json
[
  {
    "id": 1,
    "taskId": 2,
    "applicantId": 6,
    "nickname": "phase2",
    "message": "我可以代这节课",
    "status": "PENDING",
    "createdAt": "2026-09-18T12:00:00"
  }
]
```

`status`：`PENDING` / `ACCEPTED` / `REJECTED` / `CANCELLED`

---

## POST /api/applications/{id}/accept

发布者接受一条待处理申请，任务进入 `MATCHED`，其余待处理申请自动 `REJECTED`。

---

## POST /api/applications/{id}/reject

发布者拒绝一条待处理申请。若已无待处理申请，任务回到 `WAITING`。

---

## POST /api/applications/{id}/withdraw

申请人撤回自己的待处理申请。撤回后可再次申请该任务。

---

## POST /api/tasks/{id}/confirm

`MATCHED` 阶段，发布者或已选代课者确认履约。双方都确认后进入 `CONFIRMED`（若已到上课时间则进入 `IN_PROGRESS`），并给**代课者**的 `completedCount` +1。双方会在消息 Tab 收到履约通知。

---

## POST /api/tasks/{id}/complete

`IN_PROGRESS` 阶段，双方确认完成。都确认后进入 `COMPLETED`，并给**发布者**的 `completedCount` +1。

---

## POST /api/tasks/{id}/cancel

请求：`{ "reason": "有事去不了" }`，`reason` 选填。

---

## GET /api/me/published

我发布的任务。`page`、`size` 可选。

---

## GET /api/me/applied

我申请中且未撤回的任务（申请状态 `PENDING`）。`page`、`size` 可选。撤回、被拒绝、已被接受的申请不出现在此列表。

---

## 详情额外字段

`GET /api/tasks/{id}` 增加：

| 字段 | 说明 |
|---|---|
| publisherConfirmed | 发布者履约确认 0/1 |
| applicantConfirmed | 代课者履约确认 0/1 |
| publisherCompleted | 发布者完成确认 0/1 |
| applicantCompleted | 代课者完成确认 0/1 |
| acceptedApplicationId | 选中的申请 |
| myApplicationStatus | 当前登录用户在该任务的申请状态，可能为空 |
| myApplicationId | 当前用户的申请 ID，可能为空 |
| matchedApplicant | 当前用户是否为已选代课者 |
| matchedApplicantNickname | 已选代课者昵称，未选人为空 |

---

## 错误码（本阶段新增）

| code | 说明 |
|---|---|
| 40005 | 当前状态不允许该操作 |
| 40006 | 不能申请自己发布的代课 |
| 40008 | 不符合该任务的性别要求 |
| 40303 | 账号已被禁止申请 |
| 40402 | 申请不存在 |
| 40903 | 已经申请过该任务 |
