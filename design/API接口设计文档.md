# 日程提醒 App — API 接口设计文档

> 基于 [日程提醒App计划文档.md](file:///d:/trae/dayliane/日程提醒App计划文档.md) 与 [数据库设计文档.md](file:///d:/trae/dayliane/design/数据库设计文档.md) 整理
> 最后更新：2026-07-23

---

## 1. 通用约定

### 1.1 基础路径

```
API 基础路径：/api/v1
后端管理路径：/api/v1/admin
```

### 1.2 统一响应格式

```json
{
  "code": 0,
  "message": "success",
  "data": {}
}
```

| code | message | 说明 |
| --- | --- | --- |
| 0 | success | 成功 |
| 400 | bad request | 参数错误 |
| 401 | unauthorized | 未登录或 Token 失效 |
| 403 | forbidden | 无权限 |
| 404 | not found | 资源不存在 |
| 409 | conflict | 资源冲突（重复等） |
| 429 | too many requests | 请求频率超限 |
| 500 | internal server error | 服务端错误 |

### 1.3 鉴权方式

**Header 传递：**
```
Authorization: Bearer {access_token}
```

**双 Token 机制：**
- Access Token：有效期 24 小时（或配置值 `JWT_ACCESS_TOKEN_EXPIRE_SECONDS`）
- Refresh Token：有效期 7 天（或配置值 `JWT_REFRESH_TOKEN_EXPIRE_SECONDS`）
- 前端保存两份 Token，Access Token 过期后使用 Refresh Token 自动刷新

### 1.4 统一分页参数

| 参数 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| page | int | 1 | 页码，从 1 开始 |
| size | int | 20 | 每页数量，最大 100 |
| sort | string | created_at:desc | 排序字段和方向，格式 `field:asc`/`field:desc` |
| keyword | string | — | 关键词搜索（可选） |
| date_from | string | — | 开始日期，格式 `yyyy-MM-dd HH:mm:ss`（可选） |
| date_to | string | — | 结束日期（可选） |
| status | string | — | 状态筛选（可选） |

**分页响应格式：**
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "list": [],
    "total": 100,
    "page": 1,
    "size": 20
  }
}
```

### 1.5 时间格式

- 请求和响应时间统一使用 ISO8601 格式：`2026-07-23T09:00:00+08:00`
- 后端统一转 UTC 存储
- 前端根据用户 `timezone` 配置转换显示

---

## 2. MVP 接口

---

### 2.1 用户认证

| 方法 | 路径 | 说明 | 鉴权 |
| --- | --- | --- | --- |
| POST | /api/v1/auth/register | 注册 | 否 |
| POST | /api/v1/auth/login | 登录 | 否 |
| POST | /api/v1/auth/logout | 退出登录 | 是 |
| POST | /api/v1/auth/refresh-token | 刷新 Token | 否（使用请求体 refreshToken 校验） |

#### POST /api/v1/auth/register

**请求：**
```json
{
  "phone": "13800138000",
  "password": "Abc12345",
  "nickname": "张三",
  "timezone": "Asia/Shanghai"
}
```

**响应：**
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "userId": 1,
    "accessToken": "eyJhbGci...",
    "refreshToken": "eyJhbGci...",
    "expiresIn": 86400
  }
}
```

**约束：**
- password 至少 8 位，必须包含字母和数字
- phone 全局唯一（包括软删除用户）
- 同一 IP 10 分钟内最多 5 次注册/登录请求

#### POST /api/v1/auth/login

**请求：**
```json
{
  "phone": "13800138000",
  "password": "Abc12345"
}
```

**响应：** 同注册响应

#### POST /api/v1/auth/refresh-token

**请求：**
```json
{
  "refreshToken": "eyJhbGci..."
}
```

**响应：**
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "accessToken": "eyJhbGci...",
    "refreshToken": "eyJhbGci...",
    "expiresIn": 86400
  }
}
```

---

### 2.2 用户

| 方法 | 路径 | 说明 | 鉴权 |
| --- | --- | --- | --- |
| GET | /api/v1/user/profile | 获取个人资料 | 是 |
| PUT | /api/v1/user/profile | 修改个人资料 | 是 |
| PUT | /api/v1/user/password | 修改密码 | 是 |
| PUT | /api/v1/user/timezone | 设置时区 | 是 |

#### GET /api/v1/user/profile

**响应：**
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "id": 1,
    "phone": "13800138000",
    "nickname": "张三",
    "avatarUrl": null,
    "timezone": "Asia/Shanghai",
    "status": "active",
    "createdAt": "2026-07-23T08:00:00+08:00"
  }
}
```

#### PUT /api/v1/user/profile

**请求：**
```json
{
  "nickname": "李四",
  "avatarUrl": "https://example.com/avatar.png"
}
```

#### PUT /api/v1/user/password

**请求：**
```json
{
  "oldPassword": "Abc12345",
  "newPassword": "Def67890"
}
```

**约束：**
- newPassword 至少 8 位，必须包含字母和数字

#### PUT /api/v1/user/timezone

**请求：**
```json
{
  "timezone": "America/New_York"
}
```

---

### 2.3 个人日程

| 方法 | 路径 | 说明 | 鉴权 |
| --- | --- | --- | --- |
| POST | /api/v1/schedules | 创建日程 | 是 |
| GET | /api/v1/schedules | 查询日程列表 | 是 |
| GET | /api/v1/schedules/{id} | 查询日程详情 | 是 |
| PUT | /api/v1/schedules/{id} | 修改日程 | 是 |
| DELETE | /api/v1/schedules/{id} | 删除日程 | 是 |
| PUT | /api/v1/schedules/{id}/complete | 标记完成 | 是 |
| PUT | /api/v1/schedules/{id}/uncomplete | 取消完成 | 是 |
| PUT | /api/v1/schedules/{id}/cancel | 取消日程 | 是 |
| PUT | /api/v1/schedules/{id}/restore | 恢复已取消日程 | 是 |
| GET | /api/v1/schedules/calendar | 日历视图数据 | 是 |

#### POST /api/v1/schedules

**请求：**
```json
{
  "title": "项目冲刺",
  "description": "完成本周实现与验收",
  "groupName": "工作",
  "timeType": "duration_task",
  "startTime": "2026-08-06T10:00:00+08:00",
  "endTime": "2026-08-06T11:00:00+08:00",
  "remindAt": "2026-08-06T09:30:00+08:00"
}
```

**说明：**
- `timeType`：`point_event`（瞬时日程，如会议、活动）/ `deadline_task`（截止任务，如作业截至）/ `duration_task`（持续任务）
- `point_event`：仅使用 `startTime`，且为必填。
- `deadline_task`：仅使用 `deadlineTime`，且为必填。
- `duration_task`：仅使用 `startTime` 与 `endTime`，且均为必填；`endTime` 不得早于 `startTime`。
- `remindAts`：提醒时间数组，可选；每个时间生成一条 reminder 记录

**响应：**
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "id": 1,
    "title": "项目冲刺",
    "groupName": "工作",
    "timeType": "duration_task",
    "status": "pending",
    "startTime": "2026-08-06T10:00:00+08:00",
    "endTime": "2026-08-06T11:00:00+08:00",
    "createdAt": "2026-08-04T08:00:00+08:00"
  }
}
```

#### GET /api/v1/schedules

**请求参数：**
```
?page=1&size=20&sort=deadline_time:asc
&status=pending
&date_from=2026-07-01&date_to=2026-07-31
&group_name=工作
```

**查询参数：**
| 参数 | 说明 |
| --- | --- |
| status | 筛选：pending/completed/cancelled |
| date_from / date_to | 按日程关联时间范围筛选：瞬时日程按开始时间、截止任务按截止时间、持续任务按起止时间段 |
| group_name | 按分组名称筛选 |

**响应：**
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "list": [
      {
        "id": 1,
        "title": "项目冲刺",
        "groupName": "工作",
        "timeType": "duration_task",
        "startTime": "2026-08-06T10:00:00+08:00",
        "endTime": "2026-08-06T11:00:00+08:00",
        "status": "pending",
        "hasReminder": true
      }
    ],
    "total": 50,
    "page": 1,
    "size": 20
  }
}
```

**倒计时展示规则：**
- API 不返回 countdown 字符串。截止任务由前端根据 `deadlineTime` 实时计算，持续任务根据 `endTime` 实时计算；瞬时日程不显示倒计时。
- 后端返回对应的时间字段，前端计算显示。
- 倒计时逻辑：>24h 显示"剩余 X 天"，≤24h 显示"X小时X分"，≤30分钟橙色，≤10分钟红色
- 逾期显示："已逾期 X分钟 / X小时 / X天"

#### GET /api/v1/schedules/calendar

**请求参数：**
```
?year=2026&month=7
```

**响应：**
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "year": 2026,
    "month": 7,
    "days": [
      {
        "date": "2026-07-23",
        "hasSchedule": true,
        "pendingCount": 2,
        "completedCount": 1,
        "schedules": [
          {
            "id": 1,
            "title": "项目周会",
            "timeType": "deadline_task",
            "deadlineTime": "2026-07-23T11:00:00+08:00",
            "status": "pending",
            "groupName": "工作"
          }
        ]
      }
    ]
  }
}
```

#### PUT /api/v1/schedules/{id}

**请求：** 同创建，可只传需要修改的字段

#### DELETE /api/v1/schedules/{id}

响应统一格式，软删除。

#### PUT /api/v1/schedules/{id}/complete

**说明：** 标记完成。该日程关联的 reminder 中 status=pending 的记录自动置为 cancelled。

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "id": 1,
    "status": "completed"
  }
}
```

#### PUT /api/v1/schedules/{id}/uncomplete

将状态改回 pending。

---

#### PUT /api/v1/schedules/{id}/cancel

**说明：** 将日程状态改为 `cancelled`，该日程关联的 pending reminder 自动置为 cancelled。

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "id": 1,
    "status": "cancelled"
  }
}
```

#### PUT /api/v1/schedules/{id}/restore

**说明：** 将已取消日程恢复为 `pending`。如果仍存在未来提醒时间，可重新生成 reminder；已过期提醒不自动恢复。

---

### 2.4 提醒（内部/定时任务）

| 方法 | 路径 | 说明 | 鉴权 |
| --- | --- | --- | --- |
| POST | /api/v1/internal/reminders/scan | 扫描待发送提醒 | 内部调用（需密钥） |
| GET | /api/v1/reminders/my | 查询我的提醒 | 是 |

#### POST /api/v1/internal/reminders/scan

**说明：**
- 仅供后端定时任务调用
- 扫描 `reminder` 表中 `status = pending` 且 `remind_at <= NOW()` 的记录
- 生成站内通知，状态置为 `sent`
- 需要内部鉴权密钥

---

### 2.5 团队

| 方法 | 路径 | 说明 | 鉴权 |
| --- | --- | --- | --- |
| POST | /api/v1/teams | 创建团队 | 是 |
| GET | /api/v1/teams | 查询我的团队 | 是 |
| GET | /api/v1/teams/{id} | 查询团队详情 | 是（成员） |
| POST | /api/v1/teams/join | 通过邀请码加入团队 | 是 |
| GET | /api/v1/teams/{id}/members | 查询团队成员 | 是（成员） |
| PUT | /api/v1/teams/{teamId}/members/{userId}/role | 修改团队成员角色 | 是（owner） |
| DELETE | /api/v1/teams/{teamId}/members/{userId} | 移除团队成员 | 是（owner/admin） |
| POST | /api/v1/teams/{id}/regenerate-invite-code | 重新生成邀请码 | 是（owner） |

#### POST /api/v1/teams

**请求：**
```json
{
  "name": "我的团队"
}
```

**响应：**
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "id": 1,
    "name": "我的团队",
    "inviteCode": "A3bC9x",
    "inviteCodeExpireAt": "2026-08-07T08:00:00+08:00",
    "memberCount": 1,
    "createdAt": "2026-07-23T08:00:00+08:00"
  }
}
```

**一致性规则：** 创建团队时必须同步创建 `team_member` 记录，`role = owner`，`status = active`。

#### GET /api/v1/teams

**响应：**
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "list": [
      {
        "id": 1,
        "name": "我的团队",
        "role": "owner",
        "memberCount": 5,
        "activeTaskCount": 3
      }
    ],
    "total": 5,
    "page": 1,
    "size": 20
  }
}
```

#### POST /api/v1/teams/join

**请求：**
```json
{
  "inviteCode": "A3bC9x"
}
```

**约束：**
- 邀请码必须在有效期内
- 用户不能重复加入同一个团队（status = active 时返回 409）

#### PUT /api/v1/teams/{teamId}/members/{userId}/role

**请求：**
```json
{
  "role": "admin"
}
```

**权限：** 只有团队创建者（owner）可以设置/取消管理员

#### DELETE /api/v1/teams/{teamId}/members/{userId}

**权限：**
- 团队创建者可以移除任何成员（包括管理员和普通成员）
- 团队管理员可以移除普通成员
- 团队管理员不能移除团队创建者
- 普通成员不能移除任何成员

**业务规则：**
- 被移除成员的 team_member.status 设为 removed，记录 removed_at 和 removed_by
- 被移除成员不能再访问团队
- 被移除成员不再在首页看到该团队的任务
- 被移除成员在该团队任务中的执行人记录 is_active 设为 false
- 管理员可手动重新分配给 active 成员
- 被移除成员重新加入时，复用原 team_member 记录，将 status 改回 active，更新 joined_at

---

### 2.6 团队任务

| 方法 | 路径 | 说明 | 鉴权 |
| --- | --- | --- | --- |
| POST | /api/v1/team-tasks | 创建团队任务 | 是 |
| GET | /api/v1/team-tasks/my | 查询我负责的团队任务 | 是 |
| GET | /api/v1/teams/{teamId}/tasks | 查询某团队任务 | 是（成员） |
| GET | /api/v1/team-tasks/{id} | 查询任务详情 | 是（成员） |
| PUT | /api/v1/team-tasks/{id} | 修改任务 | 是 |
| PUT | /api/v1/team-tasks/{id}/time | 修改任务开始/截止时间 | 是 |
| DELETE | /api/v1/team-tasks/{id} | 删除任务 | 是 |
| POST | /api/v1/team-tasks/{id}/accept | 接受任务 | 是 |
| POST | /api/v1/team-tasks/{id}/reject | 拒绝任务 | 是 |
| POST | /api/v1/team-tasks/{id}/complete | 完成任务 | 是 |
| POST | /api/v1/team-tasks/{id}/cancel | 取消任务 | 是 |
| POST | /api/v1/team-tasks/{id}/restore | 恢复已取消任务 | 是 |
| POST | /api/v1/team-tasks/{id}/reassign | 重新分配任务 | 是 |
| PUT | /api/v1/team-tasks/{id}/assignees/{assigneeId}/status | 修正执行人状态 | 是 |

#### POST /api/v1/team-tasks

**请求：**
```json
{
  "teamId": 1,
  "title": "完成需求文档",
  "description": "本周五前完成",
  "groupName": "团队任务",
  "startTime": "2026-07-23T00:00:00+08:00",
  "deadlineTime": "2026-07-25T18:00:00+08:00",
  "assigneeUserIds": [2, 3, 4],
  "remindBeforeMinutes": 30
}
```

**约束：**
- `assigneeUserIds` 必须为当前团队 active 成员
- 创建者不自动成为执行人
- 最少选择 1 个执行人
- `remindBeforeMinutes`：可选；传入时后端按每个 active 执行人分别生成一条 reminder

**响应：**
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "id": 1,
    "teamId": 1,
    "title": "完成需求文档",
    "status": "active",
    "assigneeCount": 3,
    "deadlineTime": "2026-07-25T18:00:00+08:00",
    "createdAt": "2026-07-23T08:00:00+08:00"
  }
}
```

#### GET /api/v1/team-tasks/my

**请求参数：**
```
?page=1&size=20&status=pending
```

**额外参数：** 支持 `status` 筛选（pending/accepted/rejected/completed）

#### GET /api/v1/teams/{teamId}/tasks

**请求参数：**
```
?page=1&size=20&sort=deadline_time:asc&status=active
&date_from=2026-07-01&date_to=2026-07-31
```

#### GET /api/v1/team-tasks/{id}

**响应：**
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "id": 1,
    "teamId": 1,
    "title": "完成需求文档",
    "description": "本周五前完成",
    "groupName": "团队任务",
    "status": "active",
    "deadlineTime": "2026-07-25T18:00:00+08:00",
    "startTime": "2026-07-23T00:00:00+08:00",
    "creator": {
      "id": 1,
      "nickname": "张三"
    },
    "assignees": [
      {
        "userId": 2,
        "nickname": "李四",
        "role": "member",
        "assignStatus": "pending",
        "isActive": true,
        "assignRound": 1,
        "assignedAt": "2026-07-23T08:00:00+08:00"
      }
    ],
    "timeline": [
      {
        "event": "created",
        "userId": 1,
        "nickname": "张三",
        "time": "2026-07-23T08:00:00+08:00"
      }
    ]
  }
}
```

**timeline 说明：**
- MVP 的 timeline 由 `team_task` 和 `team_task_assignee` 现有字段拼接，仅展示创建、分配、接受、拒绝、完成等基础节点。
- 如需完整记录取消、恢复、重新分配、管理员修正状态等历史，后续可新增 `team_task_event_log` 表。

**倒计时规则**（前端计算）：

| 条件 | 显示 |
| --- | --- |
| 剩余 > 24h | 剩余 X 天 |
| 剩余 ≤ 24h | X小时X分 |
| 剩余 ≤ 30 分钟 | 橙色强调 |
| 剩余 ≤ 10 分钟 | 红色强调 |
| 已逾期 | 已逾期 X分钟 / X小时 / X天 |

#### PUT /api/v1/team-tasks/{id}

**权限：** 创建者和 owner/admin 可以编辑标题、描述、groupName；开始时间和截止时间通过 `/time` 接口修改

#### PUT /api/v1/team-tasks/{id}/time

**说明：** 修改任务开始时间/截止时间。`time_updated_at` 自动更新。

**权限：** 任务创建者、团队 owner/admin

**请求：**
```json
{
  "startTime": "2026-07-24T00:00:00+08:00",
  "deadlineTime": "2026-07-26T18:00:00+08:00"
}
```

#### POST /api/v1/team-tasks/{id}/accept

**说明：** 当前登录用户接受任务。仅自己可操作。

#### POST /api/v1/team-tasks/{id}/reject

**说明：** 当前登录用户拒绝任务。仅自己可操作。

#### POST /api/v1/team-tasks/{id}/complete

**说明：** 当前登录用户完成任务。仅自己可操作。

#### POST /api/v1/team-tasks/{id}/cancel

**权限：** 任务创建者、团队管理员或团队 owner

**说明：** 任务取消后，关联的未发送 reminder 置为 cancelled。已取消任务不允许执行人继续操作。

#### POST /api/v1/team-tasks/{id}/restore

**权限：** 团队 owner/admin

**说明：**
- 恢复后不固定为 active，根据执行人状态重新计算整体状态
- 执行人状态不变

#### POST /api/v1/team-tasks/{id}/reassign

**权限：** 任务分配者或团队管理员

**请求：**
```json
{
  "originalAssigneeUserId": 3,
  "newAssigneeUserId": 5
}
```

**约束：**
- 只允许对 `rejected` 的执行人重新分配
- 默认操作 `originalAssigneeUserId` 对应的 `is_active = true` 记录；如果传入 `originalAssignRound`，则校验该轮次必须是当前 active 记录
- `newAssigneeUserId` 必须为当前团队 active 成员
- 原记录 `is_active = false`，新记录 `pending + is_active = true` + `assign_round` 加 1
- `all_rejected` 后重新分配，任务状态自动变回 `active`
- 为新执行人生成新提醒

#### PUT /api/v1/team-tasks/{id}/assignees/{assigneeId}/status

**权限：** 任务分配者或团队管理员

**请求：**
```json
{
  "status": "completed"
}
```

**说明：** 修正执行人任务状态。使用 `assigneeId` 定位具体执行人记录，避免同一用户多轮分配时歧义。修正后重新计算任务整体状态。

---

### 2.7 首页

| 方法 | 路径 | 说明 | 鉴权 |
| --- | --- | --- | --- |
| GET | /api/v1/home/today | 今日总览 | 是 |
| GET | /api/v1/home/upcoming | 即将到期任务 | 是 |

#### GET /api/v1/home/today

**响应：**
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "personalSchedules": [],
    "teamTasks": [],
    "unreadNotificationCount": 3,
    "groups": [
      {
        "name": "工作",
        "pendingCount": 2,
        "schedules": []
      }
    ]
  }
}
```

**返回内容：** 今天的个人日程 + 今天的团队任务 + 未读通知数 + 分组列表

#### GET /api/v1/home/upcoming

**响应：**
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "list": [
      {
        "type": "schedule",
        "id": 1,
        "title": "项目周会",
        "deadlineTime": "2026-07-23T11:00:00+08:00",
        "groupName": "工作"
      }
    ]
  }
}
```

**返回范围：** 7 天内即将到期但未完成的任务

---

### 2.8 通知

| 方法 | 路径 | 说明 | 鉴权 |
| --- | --- | --- | --- |
| GET | /api/v1/notifications | 通知列表 | 是 |
| GET | /api/v1/notifications/unread-count | 未读消息数 | 是 |
| PUT | /api/v1/notifications/{id}/read | 标记已读 | 是 |
| PUT | /api/v1/notifications/read-all | 全部标记已读 | 是 |

#### GET /api/v1/notifications

**请求参数：**
```
?page=1&size=20&is_read=false&sort=created_at:desc
```

**额外参数：** 支持 `is_read`（true/false）筛选

**响应：**
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "list": [
      {
        "id": 1,
        "type": "task_assigned",
        "title": "新任务通知",
        "content": "张三给您分配了任务：完成需求文档",
        "relatedType": "team_task",
        "relatedId": 1,
        "reminderId": null,
        "isRead": false,
        "createdAt": "2026-07-23T08:00:00+08:00"
      }
    ],
    "total": 20,
    "page": 1,
    "size": 20
  }
}
```

#### GET /api/v1/notifications/unread-count

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "count": 3
  }
}
```

---

## 3. 后台管理接口（MVP）

后台接口统一前缀 `/api/v1/admin`，需要管理员 Token 鉴权。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | /api/v1/admin/auth/login | 管理员登录 |
| POST | /api/v1/admin/auth/logout | 管理员退出登录 |
| GET | /api/v1/admin/profile | 当前管理员资料 |
| GET | /api/v1/admin/users | 用户列表 |
| PUT | /api/v1/admin/users/{id}/status | 禁用/启用用户 |
| GET | /api/v1/admin/teams | 团队列表 |
| GET | /api/v1/admin/teams/{id}/members | 团队成员 |
| GET | /api/v1/admin/schedules | 个人日程查看 |
| GET | /api/v1/admin/team-tasks | 团队任务查看 |
| GET | /api/v1/admin/notifications | 通知记录 |
| GET | /api/v1/admin/reminders | 提醒记录 |
| GET | /api/v1/admin/operation-logs | 操作日志 |
| GET | /api/v1/admin/admin-users | 管理员列表 |
| POST | /api/v1/admin/admin-users | 创建管理员 |
| PUT | /api/v1/admin/admin-users/{id}/status | 禁用/启用管理员 |

**后台列表接口统一支持：**
- 分页、关键词搜索、状态筛选、日期范围筛选
- 管理员的 `admin_operation_log` 记录所有重要操作

---

## 4. V1.1 新增接口

---

### 4.1 分组与排序

| 方法 | 路径 | 说明 | 鉴权 |
| --- | --- | --- | --- |
| POST | /api/v1/task-groups | 创建分组 | 是 |
| GET | /api/v1/task-groups | 查询分组列表 | 是 |
| PUT | /api/v1/task-groups/{id} | 重命名分组 | 是 |
| DELETE | /api/v1/task-groups/{id} | 删除分组 | 是 |
| PUT | /api/v1/task-groups/sort | 调整分组顺序 | 是 |
| PUT | /api/v1/schedules/sort | 调整个人日程排序 | 是 |
| PUT | /api/v1/teams/{teamId}/tasks/sort | 调整团队任务排序 | 是 |
| PUT | /api/v1/schedules/{id}/move-group | 移动个人日程到分组 | 是 |
| PUT | /api/v1/team-tasks/{id}/move-group | 移动团队任务到分组 | 是 |

### 4.2 AI 智能助手

| 方法 | 路径 | 说明 | 鉴权 |
| --- | --- | --- | --- |
| POST | /api/v1/ai/schedules/parse | 自然语言解析日程 | 是 |
| POST | /api/v1/ai/team-tasks/breakdown | 拆解团队任务 | 是 |
| POST | /api/v1/ai/home/daily-plan | 生成今日计划建议 | 是 |
| POST | /api/v1/ai/text/optimize-task-description | 优化任务描述 | 是 |

**设计原则：**
- AI 接口只返回草稿或建议，不直接修改数据库
- 用户确认后，再调用普通日程或任务接口保存
- 后端记录 AI 调用日志

### 4.3 AI 管理（后台）

| 方法 | 路径 | 说明 | 鉴权 |
| --- | --- | --- | --- |
| GET | /api/v1/admin/ai/config | 查询 AI 配置状态 | 管理员 |
| PUT | /api/v1/admin/ai/config | 修改 AI 配置 | 管理员 |
| PUT | /api/v1/admin/ai/enabled | 启用或关闭 AI | 管理员 |
| GET | /api/v1/admin/ai/usage-logs | 查询 AI 调用记录 | 管理员 |
| GET | /api/v1/admin/ai/usage-stats | 查询 AI 调用统计 | 管理员 |
| POST | /api/v1/admin/ai/test | 测试 AI 服务 | 管理员 |

**权限：** 仅后台管理员
**安全：** 不返回完整 API Key，只返回脱敏后的密钥信息

---

## 5. 接口分组总览

### MVP

```
用户认证    4 个接口
用户        4 个接口
个人日程   10 个接口
提醒        2 个接口
团队        8 个接口
团队任务   14 个接口
首页        2 个接口
通知        4 个接口
后台管理   15+ 个接口
```

### V1.1

```
分组排序   9 个接口
AI 助手    4 个接口
AI 管理    6 个接口
```

---

## 6. 通用错误码示例

```json
// 401 未登录
{
  "code": 401,
  "message": "unauthorized",
  "data": "请先登录"
}

// 403 无权限
{
  "code": 403,
  "message": "forbidden",
  "data": "只有团队创建者才能执行此操作"
}

// 404 资源不存在
{
  "code": 404,
  "message": "not found",
  "data": "日程不存在"
}

// 429 限流
{
  "code": 429,
  "message": "too many requests",
  "data": "请求过于频繁，请 10 分钟后再试"
}

// 409 冲突
{
  "code": 409,
  "message": "conflict",
  "data": "您已在当前团队中"
}
```

---

## 7. 权限矩阵（MVP）

### 团队权限

| 操作 | owner | admin | member |
| --- | --- | --- | --- |
| 修改成员角色 | ✅ | ❌ | ❌ |
| 移除普通成员 | ✅ | ✅ | ❌ |
| 移除 admin | ✅ | ❌ | ❌ |
| 移除 owner | ❌ | ❌ | ❌ |
| 重新生成邀请码 | ✅ | ❌ | ❌ |

### 团队任务权限

| 操作 | 创建者 | owner/admin | 执行人 |
| --- | --- | --- | --- |
| 编辑标题/描述/截止时间 | ✅ | ✅ | ❌ |
| 修改开始/截止时间 | ✅ | ✅ | ❌ |
| 取消任务 | ✅ | ✅ | ❌ |
| 恢复已取消任务 | ❌ | ✅ | ❌ |
| 重新分配 | ✅ | ✅ | ❌ |
| 修正执行人状态 | ✅ | ✅ | ❌ |
| 删除任务 | ✅ | ✅ | ❌ |
| 接受/拒绝/完成任务 | ❌ | ❌ | ✅（仅自己） |
