# Dayliane 日程提醒与任务协作系统

Dayliane 是一个面向个人和小团队的日程规划、任务协作与 AI 辅助系统，采用前后端分离的模块化单体架构。当前包含用户网页端、管理后台、Spring Boot 后端和 Tauri 桌面端。

## 项目定位

- 管理个人日程、分组、日历和提醒
- 创建团队并通过邀请码加入
- 创建、分配、接受、拒绝、完成和重新分配团队任务
- 普通成员创建的团队任务可进入“待管理员审批”状态，管理员批准后才正式安排
- 提供站内通知、提醒记录、浏览器通知和桌面原生通知
- 使用 AI 辅助解析自然语言日程、拆解团队任务和生成计划建议
- 通过管理后台维护系统资源、审计日志、管理员账号和 AI 配置

## 当前功能状态

### 已具备

- Access Token + Refresh Token 登录、刷新、退出和禁用账号即时失效
- 个人日程增删改查、持久化分组、日历、时区化今日/未来视图
- 团队成员角色和权限：`owner > admin > member`
- 团队任务审批、接受/拒绝、完成、重分配、取消/恢复和操作时间轴
- 日程与团队任务提醒、提醒历史、通知偏好和站内通知
- AI 日程草稿、任务拆解、今日建议、描述优化和隐私化调用日志
- 管理后台资源列表、详情、统计概览、审计日志和超级管理员权限控制
- AI 多 Key 配置池：管理员可新增、编辑、启用/停用、测试和调整优先级；调用失败时按优先级切换后备 Key，并保留环境变量 Key 作为兜底
- Tauri 桌面工作台：复用用户端页面，支持快捷时间轴、系统托盘、置顶、透明度和原生通知权限
- 用户端主要列表的服务端分页、筛选、逾期置顶、完成分组排序和移动端操作菜单

### 计划中

个人日程的紧急度、预计疲劳度和四种查看方式正在按计划设计，尚未把下列内容标记为当前已交付功能：

- 紧急程度 1～5 和预计疲劳程度 1～5
- 个人日程按时间、分组、紧急程度、疲劳程度切换查看
- 个人日程每日负荷摘要、日终 0～100 疲劳调查和个性化承受上限
- 桌面快捷小窗口中的同一套四视图、负荷摘要、调查入口和托盘联动

具体边界、接口、算法和两批桌面实施顺序见：

- [个人日程紧急度与疲劳评估实现计划](./计划/个人日程紧急度与疲劳评估实现计划.md)
- [桌面端个人日程多视图与疲劳评估适配计划](./计划/桌面端个人日程多视图与疲劳评估适配计划.md)

## 技术架构

```text
                         ┌────────────────────┐
                         │ Spring Boot API    │
                         │ 认证 / 业务 / AI /  │
                         │ 通知 / 管理接口    │
                         └─────────┬──────────┘
                                   │
                              MySQL 8.4
                                   │
       ┌───────────────────────────┼───────────────────────────┐
       │                           │                           │
  web-user                    admin-web                    desktop
  Vue 3 网页端                 Vue 管理后台                  Tauri 2 桌面端
  :5173                       :5174                       复用 @web
```

技术栈：

- 后端：Java 17、Spring Boot 3.4、Spring JDBC、Flyway、MySQL
- 用户端：Vue 3、Vite、Vue Router、Pinia、Vitest
- 管理后台：Vue 3、Vite、Element Plus、Pinia、Vitest
- 桌面端：Tauri 2、Vue 3、TypeScript、Vite、Rust/Cargo
- AI：由后端统一调用，支持管理端维护的加密 Key 池和环境变量兜底

## 目录结构

```text
.
├─ README.md
├─ 启动.md
├─ 计划/
│  ├─ 个人日程紧急度与疲劳评估实现计划.md
│  ├─ 桌面端个人日程多视图与疲劳评估适配计划.md
│  └─ 日程提醒App计划文档.md
├─ logs/                 运行日志和调试输出
└─ src/
   ├─ backend/           Spring Boot 后端
   ├─ web-user/          Vue 用户网页端
   ├─ admin-web/         Vue 管理后台
   └─ desktop/           Tauri 桌面端
```

完整桌面工作台通过 `@web` 复用用户端页面；快捷小窗口是同一主窗口的 `quickTimeline` 模式，不是独立的第二个窗口。

## AI 配置与安全

AI 调用统一经过后端，前端不会保存或暴露明文 AI API Key。

### 环境变量

参考 `src/backend/application-example.env` 配置后端环境变量：

```text
DB_HOST
DB_PORT
DB_NAME
DB_USERNAME
DB_PASSWORD
JWT_SECRET
REMINDER_SCAN_TOKEN
AI_ENABLED
AI_PROVIDER
AI_MODEL
AI_API_BASE_URL
AI_API_KEY                 # 环境变量兜底 Key
AI_API_KEY_ENCRYPTION_SECRET
AI_ALLOWED_HOSTS
AI_REQUEST_TIMEOUT
AI_TOTAL_TIMEOUT
```

用户端、管理端和桌面端的 API 地址分别参考各目录下的 `.env.example`，默认后端地址为 `http://localhost:8080/api/v1`（桌面端默认使用 `127.0.0.1`）。

### 管理端 Key 池

管理员可以在管理端的 AI 配置页面直接维护多个 Key：

1. 按优先级从前到后尝试启用的 Key。
2. 当前 Key 调用失败后自动尝试后面的 Key。
3. 数据库中只保存加密密文和脱敏值，页面不回显明文。
4. 环境变量 `AI_API_KEY` 作为最后兜底，不参与数据库 Key 的优先级排序。
5. Key 的启用状态、优先级调整、测试结果和变更操作写入审计日志。

历史上使用过的密钥即使已从仓库或配置文件移除，也应在服务商控制台完成轮换后再部署生产环境。`AI_API_KEY_ENCRYPTION_SECRET` 在已有加密数据的环境中必须保持稳定。

## 本地开发

### 前置条件

- JDK 17
- Node.js 与 npm
- MySQL 8.0+，或使用 Docker Compose 启动 MySQL
- 开发桌面端或打包桌面端时，需要 Rust/Cargo 和 Tauri 所需的 Windows 构建工具

### 启动后端

```powershell
cd D:\trae\dayliane\src\backend
.\mvnw.cmd spring-boot:run
```

默认地址：`http://localhost:8080`

### 启动用户网页端

```powershell
cd D:\trae\dayliane\src\web-user
npm install
npm run dev
```

默认地址：`http://localhost:5173`

### 启动管理后台

```powershell
cd D:\trae\dayliane\src\admin-web
npm install
npm run dev
```

默认地址：`http://localhost:5174`

### 启动桌面端

桌面端开发配置位于 `src/desktop`：

```powershell
cd D:\trae\dayliane\src\desktop
npm install
npm run tauri:dev
```

如只需在浏览器中预览桌面布局：

```powershell
npm run dev -- --port 1420
```

Tauri 窗口默认尺寸为 `1280 × 800`，完整工作台最小尺寸约为 `1024 × 680`；快捷时间轴约为 `468 × 760`，最小约为 `420 × 600`。

## 自动化检查

```powershell
# 后端
cd D:\trae\dayliane\src\backend
.\mvnw.cmd test

# 用户端
cd ..\web-user
npm test
npm run typecheck
npm run build

# 管理端
cd ..\admin-web
npm test
npm run build

# 桌面端
cd ..\desktop
npm test
npm run typecheck
npm run build
npm run tauri:build
```

打包前请确认构建产物不会覆盖根目录当前版本的 `Dayliane-timeline.exe`。新桌面包应输出到独立的版本目录，完成 Windows 实机验证后再决定是否替换默认启动程序。

## Docker 部署

在项目根目录准备 Docker 环境变量文件，至少设置数据库密码、JWT 密钥和内部提醒扫描密钥，然后运行：

```powershell
docker compose up --build -d
```

启动后访问：

- 用户端：`http://127.0.0.1:5173`
- 管理端：`http://127.0.0.1:5174`
- 后端健康检查：`http://127.0.0.1:8080/actuator/health`

Docker Compose 当前负责后端、MySQL、用户端和管理端；桌面端需要在 Windows 客户端单独构建和运行。内部提醒扫描接口必须携带 `X-Internal-Token`，值为部署环境中的 `REMINDER_SCAN_TOKEN`。

## 相关文档

- [启动说明](./启动.md)
- [总体产品与版本计划](./计划/日程提醒App计划文档.md)
- [个人日程紧急度与疲劳评估实现计划](./计划/个人日程紧急度与疲劳评估实现计划.md)
- [桌面端个人日程多视图与疲劳评估适配计划](./计划/桌面端个人日程多视图与疲劳评估适配计划.md)

## 后续规划

1. 完成个人日程紧急度、疲劳度和四种查看方式
2. 将上述能力同步到桌面完整工作台和快捷小窗口
3. 完成日终疲劳调查、个性化负荷上限和桌面原生提醒闭环
4. PWA、移动端和原生推送通知
5. 文件附件、评论、周期任务、支付与实时聊天等扩展能力
