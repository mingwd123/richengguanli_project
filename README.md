# Dayliane 日程提醒与任务协作系统

> 更新日期：2026-08-19

Dayliane 是一个面向个人和小团队的日程规划、任务协作、疲劳评估与 AI 辅助系统，采用前后端分离的模块化单体架构。当前包含用户网页端、管理后台、Spring Boot 后端和 Tauri 桌面端。

## 项目定位

- 管理个人日程、分组、日历和提醒
- 为个人日程设置 1～5 级紧急程度与预计疲劳程度，并按时间、分组、紧急度或疲劳度查看
- 汇总每日计划/已完成负荷，通过日终调查逐步校准个人疲劳承受上限
- 创建团队并通过邀请码加入
- 创建、分配、接受、拒绝、完成和重新分配团队任务
- 普通成员创建的团队任务可进入“待管理员审批”状态，管理员批准后才正式安排
- 提供站内通知、提醒记录、浏览器通知和桌面原生通知
- 使用 AI 辅助解析自然语言日程、拆解团队任务和生成计划建议
- 通过管理后台维护系统资源、审计日志、管理员账号和 AI 配置

## 当前功能状态

### 已具备

- Access Token + Refresh Token 登录、刷新、退出和禁用账号即时失效
- 个人日程增删改查、持久化分组、日历、时区化今日/未来视图，以及紧急度和预计疲劳度字段
- 个人日程按时间、分组、紧急程度、疲劳程度四种模式查看，支持服务端分区统计、稳定排序和团队任务隔离
- 每日计划负荷、已完成负荷、个性化承受上限、创建前负荷试算、分级提醒和“今天不再提醒”
- 日终 0～100 疲劳调查、昨日补填、稍后提醒、跳过、异常因素、趋势与 CSV 导出
- 基于有效调查逐步校准个人承受上限和五档疲劳系数，支持锁定、重置、历史删除和数据保留期限
- 团队成员角色和权限：`owner > admin > member`
- 团队任务审批、接受/拒绝、完成、重分配、取消/恢复和操作时间轴
- 日程与团队任务提醒、提醒历史、通知偏好和站内通知
- AI 日程草稿、任务拆解、今日建议、描述优化和隐私化调用日志
- 管理后台资源列表、详情、统计概览、审计日志和超级管理员权限控制
- AI 多 Key 配置池：管理员可新增、编辑、启用/停用、测试和调整优先级；调用失败时按优先级切换后备 Key，并保留环境变量 Key 作为兜底
- Tauri 桌面工作台：复用用户端页面，支持四视图、负荷摘要、日终调查、快捷时间轴、系统托盘、置顶、透明度和原生通知
- 用户端主要列表的服务端分页、筛选、逾期置顶、完成分组排序和移动端操作菜单

### 当前交付边界

- 疲劳评估只处理个人日程；团队任务不参与个人负荷、调查训练或疲劳提醒。
- 疲劳结果只用于个人日程规划参考，不构成医疗诊断或健康建议。
- 当前按日程归属日期计算负荷。预计投入时间、实际投入时间和跨多天精确分摊仍属于后续增强。
- Web、后端和桌面源码已经完成回归；根目录现有 `Dayliane-timeline.exe` 是保留的旧版本，尚未用本次源码重新打包。
- 桌面端已在 Windows 150% 缩放下完成实机验收；100%/125% 缩放、版本化安装包和回滚演练仍是发布前门禁。
- 移动端目前只有开发计划，尚未交付独立 Android/iOS 应用。

业务边界、接口、算法与验收记录见：

- [个人日程紧急度与疲劳评估实现记录](./计划/个人日程紧急度与疲劳评估实现计划.md)
- [桌面端个人日程多视图与疲劳评估实现记录](./计划/桌面端个人日程多视图与疲劳评估适配计划.md)

## 技术架构

```text
 web-user :5173 ──────┐
 admin-web :5174 ─────┼── HTTP / JSON ──> Spring Boot API :8080 ──> MySQL 8.4
 desktop / Tauri 2 ───┘                         └───────────────> AI Provider
```

技术栈：

- 后端：Java 17、Spring Boot 3.4.5、Spring JDBC、Flyway、MySQL
- 用户端：Vue 3、Vite、Vue Router、Pinia、Vitest
- 管理后台：Vue 3、Vite、Element Plus、Pinia、Vitest
- 桌面端：Tauri 2、Vue 3、TypeScript、Vite、Rust/Cargo
- AI：由后端统一调用，支持管理端维护的加密 Key 池和环境变量兜底

## 目录结构

```text
.
├─ .github/workflows/     CI 配置
├─ .env.docker.example    Docker 环境变量模板
├─ README.md
├─ 启动.md
├─ start-backend-java17.cmd
├─ docker-compose.yml
├─ deploy/                SPA 的 Nginx 配置
├─ design/                架构、数据库与 API 设计文档
├─ 计划/
│  ├─ 个人日程紧急度与疲劳评估实现计划.md
│  ├─ 桌面端个人日程多视图与疲劳评估适配计划.md
│  ├─ 移动端App开发计划.md
│  └─ 日程提醒App计划文档.md
├─ logs/                 运行日志和调试输出
└─ src/
   ├─ backend/           Spring Boot 后端
   ├─ web-user/          Vue 用户网页端
   ├─ admin-web/         Vue 管理后台
   └─ desktop/           Tauri 桌面端
```

完整桌面工作台通过 `@web` 复用用户端页面；快捷小窗口是同一主窗口的 `quickTimeline` 模式，不是独立的第二个窗口。

## 配置与安全

AI 调用统一经过后端，前端不会保存或暴露明文 AI API Key。

### 环境变量

`src/backend/application-example.env` 提供后端基础变量模板，`.env.docker.example` 提供 Docker 模板。Spring Boot 不会自动读取前者，请通过终端、IDE 或部署环境显式注入。主要变量如下：

```text
SPRING_PROFILES_ACTIVE
DEMO_DATA_ENABLED
SERVER_PORT
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
DAYLIANE_FATIGUE_ENABLED          # 疲劳模块总开关，默认 true
DAYLIANE_FATIGUE_ALERTS_ENABLED   # 负荷提醒开关，默认 true
DAYLIANE_FATIGUE_SURVEYS_ENABLED  # 日终调查开关，默认 true
DAYLIANE_FATIGUE_LEARNING_ENABLED # 个性化校准开关，默认 true
DAYLIANE_FATIGUE_RETENTION_DAYS   # 调查数据保留天数，默认 180
DAYLIANE_FATIGUE_ALLOWED_USER_IDS # 可选灰度用户 ID 列表；留空表示不限制
```

六个 `DAYLIANE_FATIGUE_*` 变量当前未由 `docker-compose.yml` 显式透传，容器部署默认使用上方标注的默认值；如需覆盖，应将对应变量加入后端服务的 `environment` 配置。

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

以下相对路径命令均假定当前目录为仓库根目录。

### 前置条件

- JDK 17
- Node.js 与 npm
- MySQL 8.0+，或使用 Docker Compose 启动 MySQL
- 开发桌面端或打包桌面端时，需要 Rust/Cargo 和 Tauri 所需的 Windows 构建工具

### 启动后端

```powershell
cd .\src\backend
.\mvnw.cmd spring-boot:run
```

仓库另提供本机辅助脚本 `start-backend-java17.cmd`。它当前将 JDK 路径设为 `D:\JDK(java)`；如果本机路径不同，请先调整脚本内的 `JAVA_HOME`：

```powershell
.\start-backend-java17.cmd
```

默认地址：`http://localhost:8080`

### 启动用户网页端

```powershell
cd .\src\web-user
npm install
npm run dev
```

默认地址：`http://localhost:5173`

### 启动管理后台

```powershell
cd .\src\admin-web
npm install
npm run dev
```

默认地址：`http://localhost:5174`

### 启动桌面端

桌面端开发配置位于 `src/desktop`：

```powershell
cd .\src\desktop
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
cd .\src\backend
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

# 桌面端 Rust 层（当前位于 src\desktop）
cd .\src-tauri
cargo check
cargo test
```

打包前请确认构建产物不会覆盖根目录当前版本的 `Dayliane-timeline.exe`。新桌面包应输出到独立的版本目录，完成 Windows 实机验证后再决定是否替换默认启动程序。

最近一次紧急度与疲劳功能回归基线（2026-08-17）：后端 72 项、用户端 28 项、桌面端 25 项测试通过；用户端和桌面端类型检查、生产构建以及 Rust `cargo check`、`cargo test` 均通过。

## Docker 部署

在项目根目录准备 Docker 环境变量文件，至少设置数据库密码、JWT 密钥和内部提醒扫描密钥，然后运行：

```powershell
Copy-Item .env.docker.example .env
# 编辑 .env，至少替换 DB_PASSWORD、JWT_SECRET 和 REMINDER_SCAN_TOKEN
docker compose up --build -d
```

默认数据库名为 `dayline`，与 `docker-compose.yml`、后端示例环境变量保持一致。

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
- [移动端 App 开发计划](./计划/移动端App开发计划.md)

## 后续规划

1. 为个人日程增加预计投入时间、实际投入时间和跨多天负荷精确分摊
2. 生成版本化桌面安装包，完成 Windows 100%/125% 缩放复核、安装验证和回滚演练
3. 推进 PWA、移动端 App、设备注册、深链接和原生推送通知
4. 扩展文件附件、评论、周期任务、支付与实时聊天等能力
