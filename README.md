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
AI_PROVIDER
AI_MODEL
AI_API_BASE_URL
AI_API_KEY
AI_ENABLED
```

AI 调用统一经过后端，前端不会保存或暴露 AI API Key。

## 当前开发状态

当前已完成：

- 项目设计文档
- Git 仓库和 GitHub 远程仓库关联
- Spring Boot 后端项目骨架
- Vue 用户网页端项目骨架
- Vue 管理后台项目骨架
- 多环境配置模板

当前尚未实现：

- 登录注册业务
- 个人日程业务
- 团队和任务业务
- AI 服务接口
- 通知调度功能
- 管理后台业务页面

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

## 后续规划

1. 完成后端多环境配置和数据库连接
2. 完成用户登录注册
3. 完成个人日程管理
4. 完成团队和团队任务管理
5. 完成网页站内提醒
6. 接入 AI 智能助手
7. 完成管理后台
8. 后续扩展 PWA、Android App 和推送通知
