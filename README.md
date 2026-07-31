# 日程提醒 Web

日程提醒 Web 是一个面向个人和小团队的日程规划与任务协作系统。

第一版优先开发网页端，帮助用户管理个人日程、创建团队、分配团队任务，并通过 AI 辅助创建日程和拆解任务。后续可以在共用同一套后端接口的基础上扩展 Android App。

## 项目定位

- 管理个人日程和待办事项
- 创建团队并通过邀请码加入
- 分配团队任务并跟踪成员状态
- 支持接受、拒绝和完成任务
- 提供网页站内通知和浏览器提醒
- 使用 AI 辅助解析自然语言日程
- 使用 AI 拆解团队任务并生成今日计划建议

## 技术架构

项目采用前后端分离的模块化单体架构：

```text
Vue 用户网页端
Vue 管理后台
        ↓
Spring Boot 后端 API
        ↓
MySQL 数据库
```

技术栈：

- 用户端：Vue 3 + Vite
- 管理后台：Vue 3 + Vite
- 后端：Java Spring Boot
- 数据库：MySQL
- AI：由 Spring Boot 后端统一调用
- 后续客户端：Kotlin Android

## 目录结构

```text
.
├─ README.md
├─ 日程提醒App计划文档.md
└─ src
   ├─ backend       Spring Boot 后端
   ├─ web-user      Vue 用户网页端
   └─ admin-web     Vue 管理后台
```

## 核心模块

### 用户网页端

- 手机号 + 密码登录
- 个人日程增删改查
- 日历查看
- 首页今日任务总览
- 团队创建和邀请码加入
- 团队任务接受、拒绝和完成
- 网页站内通知

### 团队权限

同一个用户可以在不同团队拥有不同角色，但在同一个团队内只能拥有一个角色。

```text
owner > admin > member
```

- `owner`：团队创建者，拥有最高权限
- `admin`：团队管理员，可管理普通成员和团队任务
- `member`：普通成员，可处理分配给自己的任务

### AI 智能助手

- 自然语言创建日程
- 区分持续型任务和瞬时型日程
- AI 拆解团队任务
- AI 生成今日计划建议
- AI 优化任务描述

AI 生成内容只作为草稿或建议，必须经过用户确认后才保存。

## 安全与配置

数据库信息和 AI 密钥均采用环境变量配置，不写死在业务代码中。

主要配置项：

```text
DB_HOST
DB_PORT
DB_NAME
DB_USERNAME
DB_PASSWORD
DB_SSL_ENABLED
JWT_SECRET
REMINDER_SCAN_TOKEN
AI_PROVIDER
AI_MODEL
AI_API_BASE_URL
AI_API_KEY
AI_ENABLED
```

AI 调用统一经过后端，前端不会保存或暴露 AI API Key。

历史上使用过的 AI 服务密钥即使已从仓库移除，也应在服务商控制台完成轮换后再部署生产环境。

## 当前开发状态

当前已完成：

- Access Token + Refresh Token 登录、刷新、退出与禁用账号即时失效
- 个人日程、持久化分组、日历、时区化今日/未来七天视图
- 团队、成员权限、任务分配/接受/拒绝/完成/重分配和操作时间轴
- 日程与团队任务提醒、提醒历史、通知偏好、站内通知和低频浏览器通知轮询
- AI 日程草稿、任务拆解、今日建议、描述优化及隐私化调用日志
- 用户端主要列表的服务端分页、筛选、逾期置顶、完成分组排序和移动端操作菜单
- 管理后台资源列表、详情、统计概览、审计日志和超级管理员权限控制
- 后端验收测试、两套前端自动化测试、用户端 TypeScript 检查及两个前端生产构建

后续版本范围：PWA/Android 原生推送、桌面端、支付和实时聊天等原生或商业化能力。

## 本地开发

### 启动后端

进入后端目录：

```powershell
cd src/backend
```

使用 Maven 启动：

```powershell
mvn spring-boot:run
```

默认端口：

```text
http://localhost:8080
```

### 启动用户网页端

```powershell
cd src/web-user
npm install
npm run dev
```

默认端口：

```text
http://localhost:5173
```

### 启动管理后台

```powershell
cd src/admin-web
npm install
npm run dev
```

默认端口：

```text
http://localhost:5174
```

## 自动化检查

```powershell
cd src/backend
mvn test

cd ../web-user
npm test
npm run typecheck
npm run build

cd ../admin-web
npm test
npm run build
```

## Docker 部署

在项目根目录根据 `.env.docker.example` 创建 `.env`，替换数据库密码、JWT 密钥和内部提醒扫描密钥，然后运行：

```powershell
docker compose up --build -d
```

启动后访问：

- 用户端：`http://127.0.0.1:5173`
- 管理端：`http://127.0.0.1:5174`
- 后端健康检查：`http://127.0.0.1:8080/actuator/health`

内部扫描接口必须携带请求头 `X-Internal-Token`，值为部署环境中的 `REMINDER_SCAN_TOKEN`。

## 后续规划

1. PWA 与原生推送通知
2. Android、iOS 与桌面客户端
3. 文件附件、评论和周期任务
4. 支付与实时聊天
