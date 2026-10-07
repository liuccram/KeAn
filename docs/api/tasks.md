# 课安 Phase 2 代课任务

统一响应：`{ "code": 0, "message": "ok", "data": {} }`。`code = 0` 成功。

鉴权：发布 / 修改 / 删除必须登录，请求头 `Authorization: Bearer <token>`。列表与详情可匿名浏览，仅展示本校任务。

课程名写入 `course_name_snapshot`，不随目录改名。状态机仅由 `TaskStatusService` 写入：发布后为 `WAITING`。仅 `WAITING` 且 `applyCount = 0` 可改可删。

酬谢金额纯展示，允许 `0`。

---

## GET /api/tasks

首页列表。默认只返回本校、未开始、状态为 `WAITING` / `APPLYING` 的任务。

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| keyword | string | 否 | 搜课程名快照 / 教学楼 / 教室 |
| taskDate | string | 否 | `yyyy-MM-dd` |
| timeSlot | string | 否 | 上课开始时刻所在点段：`6-8` / `8-10` / `10-12` / `13-15` / `15-17` / `17-20` / `20-22` |
| courseId | number | 否 | 课程目录 ID |
| campusId | number | 否 | 已废弃：校区不再作为筛选条件，传了也不过滤 |
| status | string | 否 | 精确状态；不传则为 WAITING+APPLYING |
| schoolId | number | 否 | 仅未登录生效；已登录强制本校 |
| page | number | 否 | 默认 1 |
| size | number | 否 | 默认 10，最大 50 |

响应 `data`：

```json
{
  "list": [
    {
      "id": 1,
      "courseName": "高等数学",
      "taskDate": "2026-09-20",
      "startTime": "08:00",
      "endTime": "09:40",
      "campusName": "主校区",
      "building": "教学楼A",
      "classroom": "101",
      "reward": 20.00,
      "applyCount": 0,
      "status": "WAITING",
      "createdAt": "2026-09-17T11:00:00"
    }
  ],
  "total": 1,
  "page": 1,
  "size": 10
}
```

---

## GET /api/tasks/{id}

详情。跨校按不存在处理。

额外字段：`reason`、`requirement`、`remark`、`publisher`、`mine`。

```json
{
  "id": 1,
  "courseId": 2,
  "courseName": "高等数学",
  "publisher": { "id": 3, "nickname": "小明", "avatarUrl": null, "completedCount": 0 },
  "mine": false,
  "status": "WAITING"
}
```

---

## POST /api/tasks

登录用户发布。课程名、教学楼由用户自行填写。校区选填、由发布者自行填写（1-50 字，服务端去首尾空格），不填也能发布。学校不接受请求传入，一律取发布者资料里的学校；资料里没有学校会被拒绝（`40001`）。`startAt` 必须晚于当前时间，结束时间必须晚于开始时间。

请求：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| courseName | string | 是 | 课程名称，最长 128 |
| taskDate | string | 是 | `yyyy-MM-dd` |
| startTime | string | 是 | `HH:mm` |
| endTime | string | 是 | `HH:mm` |
| campusText | string | 否 | 校区名称，由发布者自行填写，最长 50 字；不填也能发布 |
| building | string | 是 | 教学楼，最长 64 |
| classroom | string | 是 | 教室，最长 64 |
| computerLab | boolean | 是 | 是否上机 |
| requirePhoto | boolean | 否 | 是否要求现场拍照，默认否 |
| genderRequirement | string | 是 | `ANY` / `MALE` / `FEMALE` |
| reward | number | 是 | ≥ 0 |
| reason | string | 否 | 最长 500 |
| requirement | string | 否 | 最长 500 |
| remark | string | 否 | 最长 500 |

请求示例：

```json
{
  "courseName": "高等数学",
  "taskDate": "2026-09-20",
  "startTime": "08:00",
  "endTime": "09:40",
  "campusText": "主校区",
  "building": "教学楼A",
  "classroom": "101",
  "computerLab": false,
  "requirePhoto": false,
  "genderRequirement": "ANY",
  "reward": 20,
  "reason": "临时有事",
  "requirement": "需要过这门课",
  "remark": ""
}
```

响应：任务详情，`status = WAITING`。

---

## PUT /api/tasks/{id}

仅发布者，且当前为 `WAITING`、无人申请。请求体同发布。课程名快照随填写内容更新。

---

## DELETE /api/tasks/{id}

逻辑删除。条件同修改。

---

## 错误码（本阶段新增）

| code | 说明 |
|---|---|
| 40003 | 上课时间不合法 |
| 40004 | 当前状态不允许修改或删除 |
| 40302 | 账号已被禁止发布 |
| 40401 | 代课任务不存在 |
