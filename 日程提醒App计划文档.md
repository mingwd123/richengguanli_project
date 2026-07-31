# 日程提醒 App 计划文档

## 1. 项目概述

### 1.1 App 名称

日程提醒 App

### 1.2 产品定位

日程提醒 App 是一款面向个人和小团队的日程规划与任务协作工具。

它既可以帮助个人安排每天的日程、避免遗忘重要事项，也可以支持团队成员之间分配任务、确认接收、拒绝任务、完成任务，并进行提醒通知。

### 1.3 目标用户

- 想对每天有清晰规划的人
- 容易忘记事情、需要提醒的人
- 学生小组、工作小组、创业团队
- 需要进行任务分工和协作的小型团队

### 1.4 解决的问题

- 个人容易忘记事情
- 每天没有清晰的计划安排
- 团队任务分配后缺少确认机制
- 创建者不知道成员是否接受任务、是否完成任务
- 团队任务和个人日程分散，不方便统一查看

---

## 2. 版本规划：三层演进

### 2.1 MVP (Minimum Viable Product) - 第一版

范围：
- 登录注册（手机号+密码）
- 个人日程管理（增删改查、日历、完成标记）
- 团队管理（创建、邀请码加入、成员管理）
- 团队任务管理（分配、接受/拒绝/完成、重新分配）
- 站内通知
- 基础后台管理

**不包含：**
- AI 功能
- 桌面端
- App 端
- 浏览器通知

### 2.2 V1.1 - 增强版

范围（在 MVP 基础上）：
- 浏览器通知
- AI 自然语言创建日程
- AI 拆解团队任务
- AI 今日计划建议
- AI 调用记录管理
- AI 配置和功能开关

### 2.3 后续演进方案

内容参见文档最末尾"第12章：未来演进方案"。

---

## 3. 技术方案

### 3.1 用户端（Web）

第一版只开发网页端：
- Vue 3 + TypeScript + Vite
- Element Plus 组件库
- Pinia 状态管理
- Vue Router 路由
- 响应式布局

### 3.2 管理后台

Vue Pure Admin（完整）
- https://github.com/pure-admin/vue-pure-admin

### 3.3 后端

Java Spring Boot

### 3.4 数据库

MySQL

数据库迁移方案：Flyway 或 Liquibase

数据库配置原则：
- 本地开发可以使用本地 MySQL 或 Docker MySQL
- 后续部署可以切换为云数据库
- 数据库连接信息不能写死在代码里
- 数据库地址、端口、库名、用户名、密码统一通过配置文件和环境变量管理
- 切换本地数据库、测试数据库、云数据库时，只修改环境配置，不修改业务代码

### 3.5 配置与部署管理

后端建议使用 Spring Boot 多环境配置：
- application-dev.yml：本地开发环境
- application-test.yml：测试环境
- application-prod.yml：生产环境

敏感信息通过环境变量注入：
```text
DB_HOST
DB_PORT
DB_NAME
DB_USERNAME
DB_PASSWORD
DB_SSL_ENABLED
JWT_SECRET
JWT_ACCESS_TOKEN_EXPIRE_SECONDS
JWT_REFRESH_TOKEN_EXPIRE_SECONDS
REMINDER_SCAN_TOKEN
```

配置原则：
- 不在代码里写死数据库连接地址
- 不把真实密钥提交到 Git 仓库
- 提供 .env.example 或 application-example.yml 作为配置模板
- 本地、服务器、云数据库只通过环境变量区分
- 云数据库如需 SSL 连接，通过 DB_SSL_ENABLED 控制

后端配置示例：
```yaml
spring:
  datasource:
    url: jdbc:mysql://${DB_HOST:localhost}:${DB_PORT:3306}/${DB_NAME:dayliane}
    username: ${DB_USERNAME:root}
    password: ${DB_PASSWORD:root}

app:
  jwt:
    secret: ${JWT_SECRET}
    access-token-expire-seconds: ${JWT_ACCESS_TOKEN_EXPIRE_SECONDS:86400}
    refresh-token-expire-seconds: ${JWT_REFRESH_TOKEN_EXPIRE_SECONDS:604800}
  internal:
    reminder-scan-token: ${REMINDER_SCAN_TOKEN}
```

### 3.6 时区规则

**重要：**
- 数据库统一存 UTC 时间
- 接口返回时间统一为 UTC + 时区偏移（ISO8601 格式）
- 前端根据用户配置的时区显示
- 用户可以在个人设置中配置默认时区
- AI 解析时，明确基于用户所在时区

---

## 4. MVP 核心功能

### 4.1 用户登录注册

功能说明：
- 用户可以注册账号
- 用户可以登录系统
- 用户可以退出登录
- 用户可以查看和修改个人资料
- 用户可以修改密码

登录方式：
- 手机号 + 密码

账号规则：
- 手机号作为用户唯一登录账号
- 第一版不支持用户自行换绑手机号
- 密码强度校验（至少 8 位，包含字母和数字）
- 登录失败限流（同一 IP 10 分钟内最多 5 次）

安全规则：
- 密码使用 BCrypt 加密存储
- JWT Token 分为 Access Token（有效期，例如 24 小时）和 Refresh Token（有效期，例如 7 天）
- Token 过期后使用 Refresh Token 刷新
- 接口统一鉴权，需要携带有效 Access Token
- 对象级权限校验：用户只能操作自己的日程，团队成员只能在权限范围内操作

### 4.2 个人日程管理

功能说明：
- 新建个人日程
- 编辑个人日程
- 删除个人日程（软删除）
- 取消个人日程（状态设为 cancelled，非删除）
- 设置日程时间（开始时间/截止时间）
- 设置提醒时间
- 标记完成/取消完成
- 查看今日日程
- 查看全部日程（支持分页、按日期范围筛选、按状态筛选）
- 在日历中查看指定日期的日程

### 4.3 团队管理

功能说明：
- 创建团队
- 系统生成团队邀请码
- 用户通过邀请码加入团队
- 查看我加入的团队
- 查看团队成员
- 团队创建者可以管理团队成员
- 团队创建者可以设置或取消团队管理员
- 团队创建者和团队管理员可以移除普通成员

团队角色说明：
- owner：团队创建者，拥有团队最高权限
- admin：团队管理员，用于团队多级管理
- member：普通成员，可以接受、拒绝和完成分配给自己的任务

团队角色归属规则：
- 同一个用户可以在不同团队拥有不同角色
- 同一个用户在同一个团队内只能拥有一个角色
- admin 已包含 member 的基础能力
- owner 已包含 admin 和 member 的能力
- 角色权限关系为 owner > admin > member

团队成员管理规则：
- 团队创建者可以设置管理员、取消管理员、移除成员
- 团队管理员可以移除普通成员
- 团队管理员不能移除团队创建者
- 普通成员不能管理其他成员
- 团队创建者第一版不允许直接退出团队
- 团队转让功能后续版本再做
- 团队成员移除采用软删除，保留历史记录
- 被移除成员后续重新加入同一团队时，复用原 team_member 记录，将 status 从 removed 改回 active，并更新 joined_at

团队加入方式：
- 邀请码（6-8位随机字符，15天有效期）

### 4.4 团队任务管理

功能说明：
- 创建团队任务
- 选择一个或多个执行人
- 设置任务开始时间（可选，用于持续任务展示）
- 设置任务截止时间
- 设置提醒时间
- 执行人可以接受或拒绝任务
- 执行人可以标记任务完成
- 创建者可以查看所有执行人的处理状态
- 被拒绝的任务可以重新分配
- 任务截止时间允许修改，但只有任务分配者或团队管理员可以修改
- 普通执行人不能修改任务开始时间和截止时间
- 任务完成后如果误操作，由任务分配者或团队管理员把状态改回已接受
- 团队任务删除采用软删除
- 只有任务创建者、团队管理员或团队创建者可以删除团队任务

任务状态说明：
- 执行人状态：pending（待接受）、accepted（已接受）、rejected（已拒绝）、completed（已完成）
- 团队任务业务状态：active（进行中）、completed（已完成）、all_rejected（全部拒绝）、cancelled（已取消）
- 删除状态通过 deleted_at 判断，不和任务业务状态混用

团队任务整体状态统一计算规则：
- 只要存在 pending 或 accepted 的执行人：任务是 active
- 所有执行人都 completed：任务是 completed
- 所有执行人都 rejected：任务是 all_rejected
- 有 completed + rejected，但没有 pending/accepted：任务仍为 completed（表示无人继续处理且已有人完成）

团队任务取消与恢复规则：
- 任务创建者、团队管理员或团队 owner 可以取消任务（状态变为 cancelled）
- 已取消任务不允许执行人继续接受、拒绝或完成
- 已取消任务可以由团队 owner/admin 恢复
- **恢复规则**：
  - 恢复已取消任务后，不固定恢复为 active
  - 恢复后根据执行人状态重新计算整体状态
  - 如果有 pending/accepted，为 active
  - 如果全是 rejected，为 all_rejected
  - 如果全是 completed 或 completed + rejected，为 completed
  - 执行人原来的 pending/accepted/rejected/completed 状态不变

团队任务重新分配规则（MVP 简化）：
- 只允许对 rejected 的执行人进行重新分配操作
- 可以重新分配给原执行人或其他团队成员（必须是当前团队内 status = active 的成员）
- 原记录设为 is_active = false
- 新记录为 pending + is_active = true + assign_round 加 1
- assigned_by 和 assigned_at 记录操作者和时间
- reassigned_from_user_id：如果是换人，记录原执行人 ID
- 任务整体状态重新计算
- **all_rejected 后重新分配**：当任务为 all_rejected 时，只要重新分配给任意执行人并生成新的 pending 记录，任务整体状态自动变回 active

团队任务多人协作规则：
- MVP 只支持「协作型」：分配给多个人表示每个人都有一份待处理责任，默认希望所有执行人完成；如果部分执行人拒绝且剩余执行人已完成，则任务整体视为 completed，不再卡在 active
- 任务创建者不自动成为执行人
- 只有被选为执行人的用户才有接受/拒绝/完成按钮
- 创建者主要负责查看、取消、修改、重新分配

团队任务编辑权限规则：
- 任务创建者可以编辑：标题、描述、截止时间
- 团队 owner/admin 可以编辑团队内所有任务
- 普通执行人不能编辑任务内容，只能更新自己的执行状态

团队成员移除与任务处理规则：
- 被移除成员不能再访问团队
- 被移除成员不再在首页看到该团队任务
- 其未完成的执行记录状态仍保留，但任务详情中管理员/创建者仍能看到该成员历史执行状态
- 旧任务责任不自动恢复，需要管理员手动重新分配
- 成员重新加入团队时，仅恢复团队成员身份，不自动恢复旧任务责任

提醒与任务状态联动规则：
- 日程完成后，未发送提醒自动取消
- 团队任务完成/取消后，未发送提醒自动取消
- 执行人拒绝后，只取消该执行人的提醒，不影响其他人的提醒
- 任务重新分配后，为新执行人生成新提醒
- 团队任务为每个执行人分别生成提醒，每个执行人有独立的提醒记录
- MVP 只给执行人发送截止提醒，创建者只收状态变化通知（接受/拒绝/完成/取消），不收截止提醒

### 4.5 DeskHive 风格任务展示与提醒交互

该规则用于指导用户端 Web 的任务展示方式，重点参考 DeskHive 的任务列表体验。

#### 任务分组规则

- 首页、个人日程列表、团队任务列表均支持按模块/分类分组展示。
- 默认分组包括：生活、工作、学习、团队任务、未分组。
- 用户创建个人日程时可以选择分组。
- 团队任务默认归入团队任务分组，也可以按所属团队名称再次分组。
- 分组标题采用折叠面板样式：左侧展开/收起箭头，中间显示分组名称，右侧显示该分组未完成任务数量。
- MVP 支持分组折叠/展开。
- 已完成任务独立归入“已完成”分组，避免和待处理任务混在一起。
- MVP 支持在创建/编辑任务时选择已有分组。
- 快速输入 `/分组名` 创建分组、分组重命名/删除/排序放到 V1.1。

#### 倒计时显示规则

- 每条未完成任务旁边显示倒计时胶囊标签。
- 倒计时基于 deadline_time 或日程截止时间计算。
- 剩余时间大于 24 小时：显示“剩余 X 天”。
- 剩余时间小于等于 24 小时：显示“X小时X分”或“X分钟”。
- 剩余时间小于等于 30 分钟：使用橙色强调。
- 剩余时间小于等于 10 分钟：使用红色强调。
- 已完成任务不再显示倒计时，显示“已完成”。
- 已取消任务显示“已取消”。
- 鼠标悬停在倒计时标签上时，显示详细时间信息：创建时间、截止时间、剩余时间、提醒时间。

#### 逾期提示规则

- 任务超过截止时间且未完成时，状态自动展示为“已逾期”。
- 逾期任务使用红色边框、浅红背景或红色倒计时胶囊。
- 逾期时间显示为“已逾期 X分钟 / X小时 / X天”。
- 首页和任务列表中，逾期任务优先展示在对应分组顶部。
- 逾期不会自动改变任务业务状态，只作为展示状态和提醒状态。
- 后端提醒扫描到逾期未完成任务时，可以生成站内通知。

#### 时间轴展示规则

- 日历页、团队任务列表、团队任务详情页支持任务时间轴展示。
- 时间轴按任务截止时间从早到晚排序。
- 时间轴节点颜色规则：
  - 蓝色：今天或普通待处理任务
  - 橙色：即将到期任务
  - 红色：已逾期任务
  - 绿色：已完成任务
  - 灰色：已取消任务
- 时间轴节点旁展示任务标题、所属分组、截止时间和状态标签。
- 团队任务详情页的时间轴可展示任务创建、分配、接受、拒绝、完成、重新分配、取消等关键操作记录。
- MVP 阶段时间轴以展示为主，不要求拖拽调整任务时间。

#### 时间轴中的时间类型表达规则

时间轴需要明确区分三类时间对象，避免把长时间持续任务误展示为单个时间点。

##### 瞬时日程 point_event

适用场景：会议、上课、面试、打电话、某个具体时间点发生的事项。

判断规则：
- 有明确 start_time。
- end_time 可以为空，或仅表示短时间持续。
- 不以 deadline_time 作为主要字段。

展示方式：
- 时间轴左侧显示具体时间，例如 09:30。
- 使用普通圆点节点。
- 右侧展示普通日程卡片。
- 卡片内容显示标题、分组、开始时间、提醒状态。

示例：
```text
09:30  ●  项目周会
          工作 · 已过期30分钟
```

##### 截止任务 deadline_task

适用场景：只关心截止时间、没有明确开始时间的任务，例如“7月25日前完成报告”。

判断规则：
- 有 deadline_time。
- start_time 为空。
- 以截止时间作为排序和提醒依据。

展示方式：
- 时间轴左侧显示截止日期或截止时间，例如 7/25 或 23:59。
- 使用菱形、旗帜或更醒目的截止节点，不画持续条。
- 右侧卡片显示“截止时间、剩余时间或逾期时间”。

示例：
```text
7/25  ◆  完成项目设计
          截止 23:59 · 剩余2天
```

##### 持续任务 duration_task（前端展示类型）

持续任务不是新的业务状态，而是前端根据时间字段推导出的展示类型。

适用场景：有明确开始时间和截止时间、需要在一段时间内持续推进的任务，例如“7/20 到 7/25 完成复盘计划”。

判断规则：
- 个人日程：time_type = deadline_task，且 start_time 与 deadline_time 都不为空。
- 团队任务：start_time 与 deadline_time 都不为空。
- 前端展示时将其视为 duration_task。

展示方式：
- 从 start_time 到 deadline_time 之间画一条较粗的竖向持续条。
- 起点显示开始日期，终点显示截止日期。
- 卡片高度可以比普通卡片略高，并与持续条垂直居中。
- 卡片中显示时间范围、状态、剩余时间、团队任务完成进度。

示例：
```text
7/20  ●
      ┃  复盘计划
      ┃  7/20 - 7/25 · 团队任务 · 进行中
      ┃  剩余2天 · 2/4人已完成
7/25  ◆
```

视觉规则：
- 瞬时日程：圆点 + 普通卡片。
- 截止任务：菱形/旗帜节点 + 截止卡片。
- 持续任务：起点圆点 + 竖向持续条 + 终点截止节点 + 持续任务卡片。
- 已逾期：浅红背景、红色边框、红色标题或红色状态标签。
- 即将到期：浅橙背景、橙色边框或橙色倒计时标签。
- 进行中：蓝色或绿色。
- 已完成：绿色，并可降低卡片透明度。
- 已取消：灰色，并弱化展示。
- 当前时间：蓝色圆点 + 横线 + “现在”标签。

排序规则：
- 同一天内优先按实际时间从早到晚排列。
- 持续任务如果跨越当前日期，需要在开始日和截止日之间保持可见。
- 只有截止时间的任务按照 deadline_time 排序。
- 逾期未完成任务在同一分组内置顶，但不改变业务状态。
#### 任务交互与动画规则

- MVP 支持快速创建、编辑、删除任务。
- MVP 支持任务完成/取消完成状态切换，并提供轻量动画反馈。
- 删除任务必须有明确确认，不采用双击快速删除，避免误删。
- 右键菜单、拖拽排序、跨分组拖拽等高级交互放到 V1.1。
- 动画以轻量为主，包括任务完成划线、卡片淡入淡出、分组折叠展开过渡。

### 4.6 首页总览

首页是用户自己的任务总界面。

首页混合显示：
- 我的个人日程
- 我被分配的团队任务

首页建议分区：
- 今日待办
- 即将到期（7天内）
- 个人日程
- 团队任务

### 4.7 团队任务单独管理

除了首页总览，还需要单独的团队任务管理入口。

团队任务页面用于：
- 按团队查看任务
- 查看团队内所有任务（支持分页、按状态筛选、按时间范围筛选）
- 创建团队任务
- 查看成员接受、拒绝、完成情况

### 4.8 站内通知

通知对象：
- 被分配任务的执行人
- 任务创建者
- 团队成员

通知场景：
- 有人给我分配任务
- 我负责的任务即将到期
- 成员接受任务
- 成员拒绝任务
- 成员完成任务
- 个人日程即将开始
- 任务被重新分配
- 任务被取消

通知规则：
- 通知可以标记已读
- 可以全部标记已读
- 通知支持分页查询
- 用户可以分别关闭任务分配、任务状态和到期提醒通知
- 浏览器通知由独立偏好控制；关闭后仍保留站内通知记录

---

## 5. 网页端页面规划（MVP）

### 5.1 登录注册模块

页面：
- 登录页
- 注册页

### 5.2 首页模块

页面：
- 首页 / 我的任务总览

首页内容：
- 今日个人日程
- 今日团队任务
- 即将到期任务（7天内）
- 快速新建入口
- 未读消息提示
- DeskHive 风格分组任务列表（生活、工作、学习、团队任务等）
- 每条任务右侧显示倒计时/逾期胶囊标签
- 支持按截止时间展示简化时间轴

### 5.3 日历模块

页面：
- 日历页
- 某日任务列表页

功能：
- 按日期查看任务
- 同时显示个人日程和团队任务
- 点击某一天查看当天详细安排
- 右侧支持当日任务时间轴
- 时间轴节点显示任务状态、截止时间、倒计时或逾期提示

### 5.4 个人日程模块

页面：
- 个人日程列表页
- 新建日程页
- 编辑日程页
- 日程详情页

展示要求：
- 列表支持按生活、工作、学习、未分组等分类折叠展示
- 任务卡片右侧显示倒计时、即将到期或已逾期标签
- 逾期未完成任务置顶展示

### 5.5 团队模块

页面：
- 团队列表页
- 创建团队页
- 加入团队页
- 团队详情页
- 团队成员页

### 5.6 团队任务模块

页面：
- 团队任务列表页
- 创建团队任务页
- 团队任务详情页
- 执行人状态页

展示要求：
- 团队任务列表支持按团队、状态或任务分类分组展示
- 每条团队任务显示执行人头像组、截止时间、倒计时/逾期标签
- 团队任务详情页展示任务操作时间轴，包括创建、分配、接受、拒绝、完成、重新分配、取消等节点

### 5.7 消息通知模块

页面：
- 消息通知列表页
- 消息详情页

### 5.8 我的模块

页面：
- 我的页面
- 个人资料页
- 修改密码页
- 通知设置页
- 时区设置页
- 退出登录

---

## 6. 用户流程（MVP）

### 6.1 个人日程流程

```text
用户注册/登录
进入首页
点击新建
选择个人日程
填写标题、时间、提醒时间和备注
保存日程
首页和日历中显示该日程
到提醒时间后收到站内通知
完成后标记为已完成
```

### 6.2 创建团队流程

```text
用户登录
进入团队页面
点击创建团队
填写团队名称
创建成功
系统生成邀请码（15天有效期）
用户把邀请码发给其他成员
```

### 6.3 加入团队流程

```text
用户登录
进入团队页面
点击加入团队
输入邀请码
系统校验邀请码（存在且未过期）
加入成功
团队出现在团队列表中
```

### 6.4 团队任务分配流程

```text
创建者进入团队详情页
点击创建团队任务
填写任务标题、描述、截止时间、提醒时间
选择一个或多个执行人
提交任务
系统给执行人发送任务通知
执行人在消息或首页中看到任务
执行人选择接受或拒绝
创建者收到成员接受或拒绝的通知
执行人完成任务后标记完成
创建者收到完成通知
```

---

## 7. 数据库设计（MVP）

### 7.1 user 用户表

约束说明：
- phone 唯一索引（软删除情况下，只允许一个 active 用户使用同一手机号）

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | bigint | 用户 ID |
| phone | varchar | 手机号，唯一 |
| password_hash | varchar | 密码加密值（BCrypt） |
| nickname | varchar | 昵称 |
| avatar_url | varchar | 头像 |
| timezone | varchar | 用户默认时区，例如 Asia/Shanghai |
| status | varchar | 用户状态：active/disabled |
| deleted_at | datetime | 删除时间，软删除使用（UTC） |
| created_at | datetime | 创建时间（UTC） |
| updated_at | datetime | 更新时间（UTC） |

### 7.2 team 团队表

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | bigint | 团队 ID |
| name | varchar | 团队名称 |
| invite_code | varchar | 邀请码 |
| invite_code_expire_at | datetime | 邀请码过期时间（UTC） |
| owner_id | bigint | 创建者用户 ID |
| status | varchar | 团队状态：active/deleted |
| deleted_at | datetime | 删除时间，软删除使用（UTC） |
| deleted_by | bigint | 删除操作者用户 ID |
| created_at | datetime | 创建时间（UTC） |
| updated_at | datetime | 更新时间（UTC） |

### 7.3 team_member 团队成员表

约束说明：
- team_id + user_id 唯一索引，避免同一个用户在同一个团队中出现多条成员记录
- 用户在不同团队可以拥有不同角色
- 用户在同一个团队内只能拥有一个角色

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | bigint | 记录 ID |
| team_id | bigint | 团队 ID |
| user_id | bigint | 用户 ID |
| role | varchar | 角色：owner/admin/member |
| status | varchar | 成员状态：active/removed |
| joined_at | datetime | 加入时间（UTC） |
| removed_at | datetime | 移除时间（UTC） |
| removed_by | bigint | 移除操作者用户 ID |
| created_at | datetime | 创建时间（UTC） |
| updated_at | datetime | 更新时间（UTC） |

### 7.4 schedule 个人日程表

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | bigint | 日程 ID |
| user_id | bigint | 用户 ID |
| title | varchar | 标题 |
| description | text | 描述 |
| group_name | varchar | 分组名称，例如生活/工作/学习/未分组 |
| time_type | varchar | 时间类型：point_event/deadline_task |
| start_time | datetime | 开始时间（UTC） |
| end_time | datetime | 结束时间（UTC） |
| deadline_time | datetime | 截止时间（UTC） |
| status | varchar | 状态：pending/completed/cancelled |
| deleted_at | datetime | 删除时间，软删除使用（UTC） |
| deleted_by | bigint | 删除操作者用户 ID |
| created_at | datetime | 创建时间（UTC） |
| updated_at | datetime | 更新时间（UTC） |

### 7.5 reminder 提醒表（独立表，避免 JSON 查询）

**重要：** 将提醒从 schedule 表独立出来，每个提醒时间一行，便于查询和建索引。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | bigint | 提醒 ID |
| user_id | bigint | 接收提醒用户 ID |
| target_type | varchar | 关联对象类型：schedule/team_task |
| target_id | bigint | 关联对象 ID |
| remind_at | datetime | 计划提醒时间（UTC） |
| status | varchar | 提醒状态：pending/sent/cancelled/failed |
| sent_at | datetime | 实际发送时间（UTC） |
| error_message | text | 失败原因 |
| created_at | datetime | 创建时间（UTC） |
| updated_at | datetime | 更新时间（UTC） |

索引建议：
- idx_user_id_status_remind_at (user_id, status, remind_at)
- idx_target_type_target_id (target_type, target_id)

### 7.6 team_task 团队任务表

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | bigint | 任务 ID |
| team_id | bigint | 团队 ID |
| creator_id | bigint | 创建者用户 ID |
| title | varchar | 任务标题 |
| description | text | 任务描述 |
| group_name | varchar | 任务分组名称，可为空，默认按团队任务分组 |
| start_time | datetime | 团队任务开始时间（UTC），可为空；用于持续任务时间轴展示 |
| deadline_time | datetime | 截止时间（UTC） |
| status | varchar | 任务业务状态：active/completed/all_rejected/cancelled |
| updated_by | bigint | 最近修改任务的用户 ID |
| deadline_updated_at | datetime | 截止时间最近修改时间（UTC） |
| deleted_at | datetime | 删除时间，软删除使用（UTC） |
| deleted_by | bigint | 删除操作者用户 ID |
| created_at | datetime | 创建时间（UTC） |
| updated_at | datetime | 更新时间（UTC） |

### 7.7 team_task_assignee 团队任务执行人表

约束说明：
- 每个 task_id + user_id 只能有一条 is_active = true 的记录
- task_id + user_id + assign_round 唯一

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | bigint | 记录 ID |
| task_id | bigint | 任务 ID |
| user_id | bigint | 执行人用户 ID |
| assign_round | int | 分配轮次，从 1 开始 |
| is_active | boolean | 是否当前有效记录 |
| status | varchar | 状态：pending/accepted/rejected/completed |
| accepted_at | datetime | 接受时间（UTC） |
| rejected_at | datetime | 拒绝时间（UTC） |
| completed_at | datetime | 完成时间（UTC） |
| reassigned_from_user_id | bigint | 重新分配来源用户 ID，可为空 |
| assigned_by | bigint | 分配或重新分配操作者用户 ID |
| assigned_at | datetime | 分配/重新分配时间（UTC） |
| status_updated_by | bigint | 最近修改状态的用户 ID |
| status_updated_at | datetime | 状态最近更新时间（UTC） |
| created_at | datetime | 创建时间（UTC） |
| updated_at | datetime | 更新时间（UTC） |

### 7.8 notification 通知表

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | bigint | 通知 ID |
| user_id | bigint | 接收通知的用户 ID |
| type | varchar | 通知类型 |
| title | varchar | 通知标题 |
| content | text | 通知内容 |
| related_type | varchar | 关联对象类型 |
| related_id | bigint | 关联对象 ID |
| is_read | boolean | 是否已读 |
| read_at | datetime | 已读时间（UTC） |
| deleted_at | datetime | 删除时间，软删除使用（UTC） |
| created_at | datetime | 创建时间（UTC） |

索引建议：
- idx_user_id_is_read_created_at (user_id, is_read, created_at DESC)

### 7.9 admin_user 后台管理员表

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | bigint | 管理员 ID |
| username | varchar | 用户名 |
| password_hash | varchar | 密码加密值（BCrypt） |
| role | varchar | 管理员角色：super_admin/admin |
| status | varchar | 状态：active/disabled |
| last_login_at | datetime | 最后登录时间（UTC） |
| last_login_ip | varchar | 最后登录 IP |
| created_at | datetime | 创建时间（UTC） |
| updated_at | datetime | 更新时间（UTC） |

### 7.10 admin_operation_log 管理员操作日志表

用于记录后台管理员的重要操作，方便审计和问题追踪。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | bigint | 日志 ID |
| admin_id | bigint | 操作管理员 ID |
| action | varchar | 操作类型 |
| target_type | varchar | 操作对象类型 |
| target_id | bigint | 操作对象 ID |
| before_data | json | 修改前数据 |
| after_data | json | 修改后数据 |
| ip_address | varchar | 操作 IP |
| user_agent | varchar | 操作 User Agent |
| created_at | datetime | 创建时间（UTC） |

### 7.11 flyway_schema_history Flyway 迁移历史表

由 Flyway 自动维护。

---

## 8. 后端接口设计（MVP）

### 8.1 统一分页、筛选、排序约定

列表接口统一参数：
- page：页码，从 1 开始
- size：每页数量，默认 20，最大 100
- sort：排序字段，例如 "created_at:desc"
- keyword：关键词搜索（可选）
- date_from：开始日期（可选）
- date_to：结束日期（可选）
- status：状态筛选（可选）

统一响应格式：
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

### 8.2 用户认证接口

- POST /api/v1/auth/register 注册
- POST /api/v1/auth/login 登录
- POST /api/v1/auth/logout 退出登录
- POST /api/v1/auth/refresh-token 刷新 Token

### 8.3 用户接口

- GET /api/v1/user/profile 获取个人资料
- PUT /api/v1/user/profile 修改个人资料
- PUT /api/v1/user/password 修改密码
- PUT /api/v1/user/timezone 设置时区

### 8.4 个人日程接口

- POST /api/v1/schedules 创建日程
- GET /api/v1/schedules 查询日程列表（支持分页、日期范围筛选、状态筛选）
- GET /api/v1/schedules/{id} 查询日程详情
- PUT /api/v1/schedules/{id} 修改日程
- DELETE /api/v1/schedules/{id} 删除日程（软删除）
- PUT /api/v1/schedules/{id}/complete 标记完成
- PUT /api/v1/schedules/{id}/uncomplete 取消完成
- GET /api/v1/schedules/calendar 日历视图数据

### 8.5 提醒接口（由后端定时任务触发）

- POST /api/v1/internal/reminders/scan 扫描待发送提醒（内部接口，仅供定时任务调用，必须携带 `X-Internal-Token: <REMINDER_SCAN_TOKEN>`）
- GET /api/v1/reminders/my 查询我的提醒记录（可选）

### 8.6 团队接口

- POST /api/v1/teams 创建团队
- GET /api/v1/teams 查询我的团队
- GET /api/v1/teams/{id} 查询团队详情
- POST /api/v1/teams/join 通过邀请码加入团队
- GET /api/v1/teams/{id}/members 查询团队成员
- PUT /api/v1/teams/{teamId}/members/{userId}/role 修改团队成员角色
- DELETE /api/v1/teams/{teamId}/members/{userId} 移除团队成员
- POST /api/v1/teams/{id}/regenerate-invite-code 重新生成邀请码

权限规则：
- 只有团队创建者可以设置或取消管理员
- 团队创建者和团队管理员可以移除普通成员
- 团队管理员不能移除团队创建者
- 普通成员不能修改团队成员角色，也不能移除成员
- 只有团队创建者可以重新生成邀请码

### 8.7 团队任务接口

- POST /api/v1/team-tasks 创建团队任务
- GET /api/v1/team-tasks/my 查询我负责的团队任务（支持分页、状态筛选）
- GET /api/v1/teams/{teamId}/tasks 查询某团队任务（支持分页、状态筛选、时间范围筛选）
- GET /api/v1/team-tasks/{id} 查询任务详情
- PUT /api/v1/team-tasks/{id} 修改任务
- PUT /api/v1/team-tasks/{id}/deadline 修改任务开始时间/截止时间
- DELETE /api/v1/team-tasks/{id} 删除任务（软删除）
- POST /api/v1/team-tasks/{id}/accept 接受任务
- POST /api/v1/team-tasks/{id}/reject 拒绝任务
- POST /api/v1/team-tasks/{id}/complete 完成任务
- POST /api/v1/team-tasks/{id}/cancel 取消任务
- POST /api/v1/team-tasks/{id}/restore 恢复已取消任务
- POST /api/v1/team-tasks/{id}/reassign 重新分配任务
- PUT /api/v1/team-tasks/{id}/assignees/{userId}/status 管理员修正执行人任务状态

权限规则：
- 任务分配者和团队管理员可以修改任务开始时间和截止时间
- 普通执行人不能修改任务开始时间和截止时间
- 被拒绝的任务可以由任务分配者或团队管理员重新分配
- 普通成员完成任务后，第一版不支持自行撤回
- 如需修正已完成状态，由任务分配者或团队管理员通过状态修正接口处理
- 任务创建者、团队管理员或团队创建者可以取消任务
- 已取消任务可以由团队 owner/admin 恢复为 active

### 8.8 首页接口

- GET /api/v1/home/today 查询今日总览
- GET /api/v1/home/upcoming 查询即将到期任务（7天内）

### 8.9 通知接口

- GET /api/v1/notifications 查询通知列表（支持分页、已读/未读筛选）
- GET /api/v1/notifications/unread-count 查询未读消息数
- PUT /api/v1/notifications/{id}/read 标记已读
- PUT /api/v1/notifications/read-all 全部标记已读
- GET /api/v1/notifications/preferences 查询浏览器、任务分配、任务状态和到期提醒偏好
- PUT /api/v1/notifications/preferences 更新通知偏好

---

## 9. 管理后台规划（MVP）

后台第一版主要用于管理员查看和处理数据。

### 9.1 后台页面

- 管理员登录页
- 首页数据概览
- 用户管理
- 团队管理
- 团队成员管理
- 个人日程管理
- 团队任务管理
- 通知记录管理
- 提醒记录管理
- 管理员操作日志
- 管理员账号管理

### 9.2 后台功能

- 查看用户列表（支持分页、关键词搜索、状态筛选）
- 禁用或启用用户
- 查看团队列表（支持分页、关键词搜索）
- 查看团队成员
- 查看个人日程
- 查看团队任务
- 查看通知记录
- 查看提醒记录
- 查看管理员操作日志
- 管理后台管理员账号（仅 super_admin）

### 9.3 后台管理员权限审计

- 所有重要操作记录 admin_operation_log
- 包括：修改用户状态、修改团队任务状态、修改执行人状态、修改管理员账号等
- 日志包含：操作人、操作类型、操作对象、修改前后数据、IP、User Agent

---

## 10. 开发阶段规划（MVP）

### 阶段 1：基础架构

目标：
- 搭建 Spring Boot 后端项目
- 配置 Flyway 数据库迁移
- 搭建 MySQL 数据库
- 完成数据库表设计和初始迁移脚本
- 搭建 Vue 3 + Element Plus 用户端项目
- 搭建 Vue Pure Admin 后台项目
- 完成后端多环境配置
- 完成数据库环境变量配置模板
- 实现 JWT 认证机制
- 实现统一响应格式和异常处理
- 实现登录限流

### 阶段 2：用户系统

目标：
- 完成用户注册（密码强度校验）
- 完成用户登录（手机号+密码）
- 完成 Token 刷新机制
- 完成用户个人资料查看和修改
- 完成密码修改
- 完成时区设置
- 完成后台用户管理（查看、禁用/启用）

### 阶段 3：个人日程

目标：
- 完成个人日程增删改查
- 完成首页今日待办
- 完成日历查看
- 完成独立 reminder 表
- 完成提醒定时扫描任务
- 完成站内通知

### 阶段 4：团队功能

目标：
- 完成创建团队
- 完成邀请码生成（15天有效期）
- 完成邀请码加入团队
- 完成团队成员列表
- 完成团队管理员角色管理
- 完成团队成员移除（软删除）
- 完成后台团队管理

### 阶段 5：团队任务

目标：
- 完成团队任务创建
- 支持选择多个执行人
- 支持执行人接受、拒绝、完成
- 支持创建者查看所有执行人的状态
- 支持被拒绝任务重新分配（assign_round、is_active）
- 支持分配者或管理员修改任务开始时间和截止时间
- 支持管理员修正执行人任务状态
- 支持任务取消和恢复
- 团队任务整体状态自动计算（active/completed/all_rejected）
- 完成后台团队任务管理

### 阶段 6：通知与提醒

目标：
- 完成网页站内通知
- 完成任务分配通知
- 完成状态变化通知
- 完成任务到期提醒
- 完成 reminder 后端定时提醒扫描
- 完成提醒记录保存，避免重复提醒
- 完成通知已读和全部已读
- 完成未读消息数统计
- 完成通知偏好持久化和用户设置页面
- 完成内部提醒扫描接口 Token 鉴权
- 完成后台通知记录、提醒记录管理

### 阶段 7：测试和优化

目标：
- 修复功能问题
- 优化页面体验
- 测试登录、日程、团队、任务、通知完整流程
- 测试权限控制
- 测试时区处理
- 完成后端验收测试、用户端类型检查和两套前端自动化测试
- 完成用户端与管理端生产构建和桌面/手机视口浏览器验收
- 准备网页端上线部署

---

## 11. V1.1 规划：AI 与体验增强版

### 11.1 AI 功能（V1.1 新增）

功能：
- 自然语言创建日程
- AI 拆解团队任务
- AI 今日计划建议
- AI 任务描述优化

### 11.2 DeskHive 风格体验增强（V1.1 新增）

#### 快捷分组能力

- 支持输入 `/分组名` 快速创建分组。
- 支持通过“+”按钮右键菜单创建分组。
- 支持分组重命名、删除、折叠/展开。
- 支持分组顺序调整（上移/下移）。

#### 右键菜单能力

- 任务卡片支持右键菜单。
- 右键菜单包含：编辑任务、修改截止时间、移除截止时间、移动到分组、删除任务。
- 分组标题支持右键菜单。
- 分组右键菜单包含：重命名分组、删除分组、上移、下移、折叠/展开。
- Web 端支持右键菜单，移动端使用长按或更多按钮替代。

#### 拖拽排序能力

- 支持分组内任务拖拽排序。
- 支持跨分组拖拽任务，拖拽后自动更新任务 group_name。
- 支持已完成任务独立分组内排序。
- 后端需要增加 sort_order 字段或排序接口来保存排序结果。

#### 主题与动效能力

- 支持日间/夜间主题切换。
- 支持任务完成动画、拖拽动画、分组折叠展开动画。
- 动画风格保持轻量，不影响操作效率。

### 11.3 数据库表新增（V1.1）

#### task_group 任务分组表

用于支持分组重命名、删除、排序和跨分组拖拽。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | bigint | 分组 ID |
| user_id | bigint | 所属用户 ID |
| team_id | bigint | 所属团队 ID，个人分组为空 |
| scope | varchar | 分组范围：personal/team |
| name | varchar | 分组名称 |
| sort_order | int | 分组排序 |
| is_default | boolean | 是否默认分组 |
| deleted_at | datetime | 删除时间（UTC） |
| created_at | datetime | 创建时间（UTC） |
| updated_at | datetime | 更新时间（UTC） |

#### schedule/team_task 字段扩展

V1.1 为支持拖拽排序，个人日程表 schedule 和团队任务表 team_task 增加：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| group_id | bigint | 分组 ID，可为空，兼容 MVP 的 group_name |
| sort_order | int | 分组内排序值 |

#### ai_usage_log AI 调用记录表

用于记录 AI 功能调用情况。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | bigint | 记录 ID |
| user_id | bigint | 调用用户 ID |
| feature_type | varchar | AI 功能类型：schedule_parse/task_breakdown/daily_plan/description_optimize |
| input_text | text | 用户输入内容（已脱敏） |
| output_text | text | AI 返回内容（已脱敏） |
| status | varchar | 调用状态：success/failed |
| error_message | text | 失败原因 |
| created_at | datetime | 创建时间（UTC） |

**隐私规则：**
- 日志保留天数：30 天（可配置）
- 敏感信息自动脱敏（手机号、邮箱等）
- 仅后台管理员可查看
- 用户可以在设置中关闭 AI 数据记录（关闭后不保存 input_text 和 output_text）

#### ai_config AI 配置表

用于管理 AI 服务基础配置。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | bigint | 配置 ID |
| provider | varchar | AI 服务商，例如 openai/qwen/zhipu/deepseek |
| model_name | varchar | 模型名称 |
| api_base_url | varchar | AI 接口基础地址 |
| api_key_masked | varchar | 脱敏后的密钥展示，例如 sk-****abcd |
| enabled | boolean | 是否启用 |
| remark | varchar | 备注 |
| created_at | datetime | 创建时间（UTC） |
| updated_at | datetime | 更新时间（UTC） |

安全说明：
- API Key 使用服务器环境变量管理，不存数据库
- 数据库只存 api_key_masked，用于后台展示

### 11.4 后端接口新增（V1.1）

#### 分组与排序接口

- POST /api/v1/task-groups 创建分组
- GET /api/v1/task-groups 查询分组列表
- PUT /api/v1/task-groups/{id} 重命名分组
- DELETE /api/v1/task-groups/{id} 删除分组
- PUT /api/v1/task-groups/sort 调整分组顺序
- PUT /api/v1/schedules/sort 调整个人日程排序
- PUT /api/v1/team-tasks/sort 调整团队任务排序
- PUT /api/v1/schedules/{id}/move-group 移动个人日程到分组
- PUT /api/v1/team-tasks/{id}/move-group 移动团队任务到分组

#### AI 智能助手接口

- POST /api/v1/ai/schedules/parse 自然语言解析日程
- POST /api/v1/ai/team-tasks/breakdown 拆解团队任务
- POST /api/v1/ai/home/daily-plan 生成今日计划建议
- POST /api/v1/ai/text/optimize-task-description 优化任务描述

接口设计建议：
- AI 接口只返回草稿或建议，不直接修改数据库中的日程和任务
- 用户确认后，再调用普通日程或任务接口保存
- 后端需要记录 AI 调用日志

#### AI 管理接口（后台）

- GET /api/v1/admin/ai/config 查询 AI 配置状态
- PUT /api/v1/admin/ai/config 修改 AI 配置
- PUT /api/v1/admin/ai/enabled 启用或关闭 AI 功能
- GET /api/v1/admin/ai/usage-logs 查询 AI 调用记录（支持分页、日期范围筛选）
- GET /api/v1/admin/ai/usage-stats 查询 AI 调用统计
- POST /api/v1/admin/ai/test 测试 AI 服务是否可用

权限规则：
- AI 管理接口只允许后台管理员调用
- 后台不返回完整 API Key，只返回脱敏后的密钥信息
- 修改 AI 配置需要记录管理员操作日志

### 11.5 后台页面新增（V1.1）

- AI 调用记录管理
- AI 配置管理

### 11.6 前端页面新增（V1.1）

- AI 快速创建日程入口
- 创建团队任务页中的 AI 拆解任务按钮
- 任务描述输入框中的 AI 优化按钮
- 首页 AI 今日计划建议区域
- 用户设置中的"AI 数据记录"开关
- 任务列表右键菜单
- 分组管理入口（重命名、删除、排序）
- 拖拽排序交互
- 日间/夜间主题切换入口

### 11.7 AI 实现策略

参考项目：Now & Again
- https://github.com/dezhishen/now-and-again

实现方式：
- 前端输入自然语言
- 后端 AiService 封装调用
- 调用第三方 AI API（可配置）
- 返回结构化数据给前端
- 用户确认后保存
- 记录 AI 调用日志（考虑隐私规则）

### 11.8 浏览器通知（V1.1 新增）

- 前端实现浏览器通知授权
- 网页打开时，收到站内通知同时弹出浏览器通知
- 要求 HTTPS（生产环境）

---

## 12. 未来演进方案（非 MVP）

以下内容为后续版本规划，不包含在 MVP 中。

### 12.1 参考项目

| 项目 | GitHub 地址 | 用途 |
| --- | --- | --- |
| **Vue Pure Admin** | https://github.com/pure-admin/vue-pure-admin | 提供后台管理系统架构 |
| **DeskHive** | https://github.com/IAMLZY2018/DeskHive | 提供桌面端任务管理 UI 设计灵感 |
| **Now & Again** | https://github.com/dezhishen/now-and-again | 提供 AI 功能集成的参考实现 |

### 12.2 多端架构（后续）

#### 整体架构

```
┌───────────────────────────────────────────────────────────────┐
│              核心业务层（可复用）                          │
│  ┌─────────────────────────────────────────────────────┐    │
│  │  Vue 3 页面组件 / 业务逻辑 / 状态管理 (Pinia)      │    │
│  └─────────────────────────────────────────────────────┘    │
└──────────────────────┬────────────────────────────────────────┘
                       │
           ┌───────────┼───────────┐
           │           │           │
           ▼           ▼           ▼
    ┌──────────┐ ┌──────────┐ ┌──────────┐
    │ web-user │ │ desktop  │ │ mobile   │ ← 多端适配层
    │ (网页版)  │ │ (桌面版)  │ │ (App版)  │
    └──────────┘ └──────────┘ └──────────┘
           │           │           │
           └───────────┼───────────┘
                       │
                       ▼
              ┌───────────────┐
              │   backend    │ ← 后端 API
              │ (Spring Boot)│
              └───────────────┘
```

#### 各端说明

| 端 | 技术选型 | 说明 | 优先级 |
| --- | --- | --- | --- |
| **web-user** | Vue 3 + Element Plus + Vite | 网页版，MVP 已完成 | 已完成 |
| **desktop** | Tauri 2.0 + Vue 3 | 桌面版，复用 web-user 代码 | 后续 |
| **mobile** | Capacitor + Vue 3 | App版 (iOS/Android)，复用 web-user 代码 | 后续 |
| **admin-web** | Vue Pure Admin | 管理后台 | 与 MVP 并行 |

### 12.3 技术栈（后续）

| 层级 | 技术选型 | 来源 |
| --- | --- | --- |
| **前端框架** | Vue 3 + TypeScript + Vite | 现有 MVP |
| **UI 组件库** | Element Plus | 现有 MVP |
| **状态管理** | Pinia | 现有 MVP |
| **路由** | Vue Router | 现有 MVP |
| **桌面端框架** | Tauri 2.0 | DeskHive |
| **后端** | Spring Boot | 现有 MVP |
| **数据库** | MySQL | 现有 MVP |

### 12.4 项目目录结构（后续）

```
dayliane/
├── src/
│   ├── web-user/          # 用户端网页（MVP 已完成）
│   │   ├── src/
│   │   │   ├── components/    # 通用组件（供其他端复用）
│   │   │   ├── views/         # 页面组件
│   │   │   ├── stores/        # 状态管理 (Pinia)
│   │   │   ├── api/           # API 封装
│   │   │   └── utils/         # 工具函数
│   │   └── ...
│   ├── desktop/         # 桌面端（后续，Tauri + Vue 3，复用 web-user）
│   ├── mobile/          # App 端（后续，Capacitor + Vue 3，复用 web-user）
│   ├── admin-web/       # 管理后台（Vue Pure Admin）
│   └── backend/         # 后端（Spring Boot）
├── docs/
│   └── 日程提醒App计划文档.md
└── ...
```

#### 代码复用策略

- `web-user/src/components/` - 通用 UI 组件，desktop 和 mobile 可复用
- `web-user/src/stores/` - 状态管理，各端一致
- `web-user/src/api/` - API 封装，各端共用
- `web-user/src/utils/` - 工具函数，各端共用

### 12.5 桌面端（Tauri）规划（后续）

目标：基于 Tauri 开发桌面端，复用 web-user 代码

任务清单：
1. 搭建 Tauri 项目
2. 复用 web-user 的 Vue 代码
3. 实现系统托盘功能
4. 实现原生桌面通知
5. 实现窗口透明度自定义
6. 打包生成 Windows/Mac/Linux 安装包

参考项目：DeskHive
- https://github.com/IAMLZY2018/DeskHive

UI/UX 设计要点：
- 简洁的任务列表布局（参考 DeskHive）
- 流畅的拖拽排序
- 直观的分组管理
- 清晰的时间指示器
- 日间/夜间主题切换
- 窗口透明度自定义（桌面端专属）

### 12.6 App 端（Capacitor）规划（后续）

目标：基于 Capacitor 开发 iOS/Android App，复用 web-user 代码

任务清单：
1. 搭建 Capacitor 项目
2. 复用 web-user 的 Vue 代码
3. 优化移动端 UI 适配
4. 实现推送通知
5. 打包生成 iOS/Android App

### 12.7 其他后续功能

- PWA 推送通知
- 短信验证码登录
- 微信登录
- 邮箱登录
- 邮箱验证
- 忘记密码
- 文件附件
- 复杂权限系统
- 项目甘特图
- 复杂 AI 自动排程
- 企业级审批流程
- 多语言
- 团队转让

---

## 13. MVP 暂不开发的功能

为了控制开发难度，以下功能建议后续版本再做：

- iOS 版本
- Android 版本
- 桌面端（Tauri）
- AI 功能
- 浏览器通知
- PWA 离线能力
- 微信登录
- 短信验证码登录
- 支付功能
- 聊天功能
- 文件附件
- 复杂权限系统
- 项目甘特图
- 复杂 AI 自动排程
- 企业级审批流程
- 多语言
- 团队转让
- 忘记密码

---

## 14. MVP 已确认规则

### 14.1 产品范围

- MVP 范围：登录、个人日程、团队/成员、团队任务、站内通知、基础后台管理
- AI 功能、桌面端、App 端放到后续版本
- 用户端不用完整 Vue Pure Admin，单独用 Vue 3 + Element Plus
- 后台用 Vue Pure Admin

### 14.2 团队任务规则

团队任务整体状态统一计算规则：
- 只要存在 pending 或 accepted 的执行人：任务是 active
- 所有执行人都 completed：任务是 completed
- 所有执行人都 rejected：任务是 all_rejected
- 有 completed + rejected，但没有 pending/accepted：任务仍为 completed（表示无人继续处理且已有人完成）

团队任务取消与恢复规则：
- 恢复已取消任务后，不固定恢复为 active，根据执行人状态重新计算整体状态
- 执行人原来的 pending/accepted/rejected/completed 状态不变

团队任务重新分配规则（MVP 简化）：
- 只允许对 rejected 的执行人重新分配
- 可以分配给原执行人或其他人
- 原记录 is_active = false，新记录 pending + is_active = true
- all_rejected 后重新分配，任务整体状态自动变回 active

团队任务多人协作规则：
- MVP 只支持「协作型」：分配给多个人表示每个人都有一份待处理责任；默认希望所有执行人完成；如果部分执行人拒绝且剩余执行人已完成，则任务整体视为 completed
- 创建者不自动成为执行人
- 只有被选为执行人的用户才有接受/拒绝/完成按钮

团队任务编辑权限：
- 创建者和 owner/admin 可以编辑标题、描述、截止时间
- 普通执行人不能编辑任务内容

团队成员移除与任务处理：
- 被移除成员不再在首页看到该团队任务，但管理端保留历史执行记录
- 旧任务责任不自动恢复，需要管理员手动重新分配

提醒与任务状态联动：
- 日程/任务完成/取消后，未发送提醒自动取消
- 执行人拒绝后，只取消该执行人的提醒
- 任务重新分配后，为新执行人生成新提醒
- 团队任务为每个执行人分别生成提醒，每人有独立提醒记录
- MVP 只给执行人发截止提醒，创建者只收状态变化通知

### 14.3 数据规则

- 提醒独立成 reminder 表，每个提醒时间一行
- 数据库统一存 UTC 时间
- 接口返回带时区的 ISO8601 格式
- 数据库迁移使用 Flyway/Liquibase
- 软删除场景下的唯一索引需要考虑 deleted_at

### 14.4 安全规则

- JWT 分 Access Token 和 Refresh Token
- 密码强度校验（至少 8 位，字母+数字）
- 登录失败限流（同一 IP 10分钟5次）
- 接口鉴权 + 对象级权限校验
- 所有重要后台操作记录 admin_operation_log

### 14.5 接口规则

- 列表接口统一支持 page, size, sort, keyword, date_from, date_to, status
- 个人日程和团队任务列表额外支持 group_name 分组筛选
- 统一响应格式

### 14.6 DeskHive 风格交互规则

- 首页、个人日程列表、团队任务列表支持按生活、工作、学习、团队任务、未分组等模块化分组展示
- 每条未完成任务右侧显示倒计时胶囊标签
- 逾期未完成任务显示“已逾期 X分钟 / X小时 / X天”，并使用红色视觉强调
- 逾期仅作为展示状态和提醒状态，不自动改变任务业务状态
- 日历页和团队任务详情页支持时间轴展示
- 时间轴区分 point_event、deadline_task 和 duration_task：瞬时日程用圆点，截止任务用截止节点，持续任务用起止节点和竖向持续条
- 团队任务详情时间轴展示创建、分配、接受、拒绝、完成、重新分配、取消等关键节点
- MVP 支持分组折叠/展开、已完成任务独立分组、倒计时悬停详情和轻量动画
- V1.1 支持 `/分组名` 快速创建分组、右键菜单、拖拽排序、跨分组拖拽、分组重命名/删除/排序、日间/夜间主题切换
- 桌面端后续支持窗口透明度自定义

### 14.7 AI 规则（V1.1）

- AI 调用日志保留 30 天
- 敏感信息脱敏
- 用户可以关闭 AI 数据记录
- 仅后台管理员可查看 AI 日志

---

## 工程模块化约束

为了保证 MVP 后续可维护，前端和后端代码必须按业务模块拆分，禁止把主要业务逻辑长期堆在单个大文件中。

### 前端模块化

- `src/web-user/src/App.vue` 只负责应用装配和顶层布局，不承载 API 调用、复杂状态流转和大段业务函数。
- API 请求统一放在 `src/web-user/src/api/`。
- 页面状态、加载、提交、列表刷新等组合逻辑放在 `src/web-user/src/composables/`。
- 可复用 UI 控件放在 `src/web-user/src/components/`，页面级视图后续放在 `src/web-user/src/views/`。
- 后续新增页面时优先新增模块文件，不继续扩大 `App.vue`。

### 后端模块化

- Controller 只处理 HTTP 入参、鉴权用户提取和响应包装。
- 业务逻辑按领域放在 `auth`、`user`、`schedule`、`team`、`teamtask`、`notification`、`reminder`、`admin` 等模块。
- 数据访问逐步从当前临时 `DbStore` 拆分到各领域 Repository/Service，避免形成新的全局大对象。
- 旧的内存演示实现不得作为 Spring Bean 参与运行。
- 安全、Token、时间转换、分页响应等通用能力放在 `common` 或 `config`，不散落到业务控制器。
