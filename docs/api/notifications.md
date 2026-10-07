# 课安 Phase 4 站内消息

统一响应：`{ "code": 0, "message": "ok", "data": {} }`。`code = 0` 成功。

本文件接口均需登录：`Authorization: Bearer <token>`。

Phase 4 本阶段只落地站内消息通知；当时未做的对象存储与私聊均已实现（对象存储见 [对象存储：MinIO → RustFS 迁移与运维手册](../ops/rustfs.md)，私聊见 `ChatController`（`/api/chats`））。消息 Tab 展示系统通知：有人申请代课、选人结果、履约确认。

---

## GET /api/notifications

当前用户的消息列表，按时间倒序。

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| page | number | 否 | 默认 1 |
| size | number | 否 | 默认 20，最大 50 |

`data`：

```json
{
  "list": [
    {
      "id": 1,
      "type": "APPLICATION",
      "title": "有人申请了你的代课",
      "content": "小安 申请了「高等数学」，请在消息中查看并处理。",
      "bizType": "TASK",
      "bizId": 12,
      "readFlag": 0,
      "createdAt": "2026-09-18T21:00:00"
    }
  ],
  "total": 1,
  "page": 1,
  "size": 20
}
```

`type`：`APPLICATION` / `TASK`。点击 `bizType=TASK` 的消息进入任务详情。

---

## GET /api/notifications/unread-count

未读数量，`data` 为数字。

---

## POST /api/notifications/{id}/read

将一条消息标为已读。只能操作自己的消息。

---

## POST /api/notifications/read-all

将当前用户全部未读标为已读。

---

## 触发时机

| 事件 | 接收人 | 标题 |
|---|---|---|
| 有人申请代课 | 发布者 | 有人申请了你的代课 |
| 发布者接受申请 | 被选中的申请人 | 申请已被接受 |
| 发布者选人后其余申请 | 未被选中的申请人 | 申请未被选中 |
| 发布者拒绝申请 | 该申请人 | 申请已被拒绝 |
| 一方确认履约 | 另一方 | 发布者/代课者已确认履约 |
| 双方都确认履约 | 双方 | 双方已确认履约 |

---

## 错误码

| code | 说明 |
|---|---|
| 40403 | 消息不存在 |
