# Dayliane 日程提醒与任务协作系统

> 更新日期：2026-09-23
> 数据库迁移版本：V31（Flyway 共 31 个迁移脚本）

Dayliane 是一个面向个人和小团队的日程规划、任务协作、疲劳评估与问题反馈系统，采用前后端分离的模块化单体架构。当前包含用户网页端、管理后台、Spring Boot 后端和 Tauri 桌面端四个交付物。

## 项目定位

- 管理个人日程、分组、日历、重复日程和提醒
- 为个人日程设置 1～5 级紧急程度与预计疲劳程度，并按时间、分组、紧急度或疲劳度查看
- 为跨天 / 长期日程记录每日进度（进度比例 + 当天疲劳），按天归集完成负荷
- 汇总每日计划/已完成负荷，通过日终调查逐步校准个人疲劳承受上限，并提供周 / 月回顾报告与 CSV 导出
- 通过 iCal 订阅把个人日程接入外部日历客户端
- 创建团队并通过邀请码加入，创建、分配、接受、拒绝、完成和重新分配团队任务
- 普通成员创建的团队任务可进入“待管理员审批”状态，管理员批准后才正式安排
- 团队任务完成时记录执行人的完成疲劳程度，只计入完成日展示负荷，不污染个人模型
- 提供站内通知、提醒记录、浏览器通知和桌面原生通知
- 使用 AI 辅助解析自然语言日程、生成排程建议、拆解团队任务和优化任务描述
- 「问题反馈」工单模块：用户提交带截图的问题，公开大厅可见、可回复与关注，管理员统一处理并公开进展（默认关闭，由超级管理员开启）
- 通过管理后台维护系统资源、审计日志、管理员账号、AI Key 池与工单处理

## 当前功能状态

### 已具备

**账号与认证**

- 邮箱注册（邮箱验证码）、手机号登录、邮箱/手机号 + 密码登录、忘记密码重置
- Access Token + Refresh Token 登录、刷新、退出和禁用账号即时失效
- 登录页「记住密码」：用户主动勾选且登录成功后记住账号密码并回填，取消勾选立即清除
- 注册成功后回到登录页并自动填好邮箱与密码，由用户显式登录
- 邮箱注册总开关 `EMAIL_REGISTRATION_ENABLED`，可临时回滚为“停止新注册”

**个人日程**

- 增删改查、持久化分组、日历、时区化今日/未来视图，以及紧急度和预计疲劳度字段
- 按时间、分组、紧急程度、疲劳程度四种模式查看，支持服务端分区统计和稳定排序
- 重复日程（rrule）、单次例外调整与整个序列的批量操作
- 每日进度记录（进度百分比 + 当天疲劳），进度可回退与修正，进度与完成状态可互相还原
- 完成、取消、恢复、手动排序（含已完成分组排序）与逾期置顶
- iCal 订阅令牌、订阅帮助页与导出

**疲劳与负荷**

- 每日计划负荷、已完成负荷、个性化承受上限、创建前负荷试算、分级提醒和“今天不再提醒”
- 日终 0～100 疲劳调查、昨日补填、稍后提醒、跳过、异常因素
- 基于有效调查逐步校准个人承受上限和五档疲劳系数，支持锁定、重置、历史删除和数据保留期限
- 周 / 月疲劳与负荷回顾报告和 CSV 导出

**团队协作**

- 团队成员角色和权限：`owner > admin > member`
- 团队任务审批、接受/拒绝、完成、重分配、取消/恢复和操作时间轴
- 团队任务完成疲劳按执行人记录，只计入完成日展示负荷

**通知与提醒**

- 日程与团队任务提醒、提醒历史、通知偏好和站内通知
- 通知类型：日程提醒、任务分配、任务状态、成员加入、任务审批、审批通过/未通过、任务待补位、计划疲劳提醒、实际疲劳提醒、疲劳调查、工单通知（提交确认、管理员回复、状态变更、置顶与合并）
- 浏览器通知与桌面原生通知

**AI**

- 自然语言日程解析、排程建议、团队任务拆解、今日建议和任务描述优化
- 隐私化调用日志；管理端多 Key 配置池（启用/停用、优先级、独立 Base URL、连通性测试），调用失败按优先级切换后备 Key，环境变量 Key 作为兜底

**工单（问题反馈）**

- 用户端公开大厅：搜索（防抖 + 请求序列保护）、分类/模块/状态筛选、全部/我的提交/我的关注视图、置顶优先和分页
- 提交表单：相似工单查重提示、截图上传（进度/重试/移除）、公开范围提示和离开确认
- 详情页：原文、解决摘要与修复版本、讨论分页、处理时间线、截图灯箱、关注、「我也遇到」、确认解决、撤回和重新处理
- 管理端：认领/转交处理人、优先级、置顶、隐藏与恢复、状态变更（带原因与解决摘要）、公开回复与内部备注隔离、重复工单合并与取消合并（可撤销，禁止循环与嵌套）、操作日志
- 模块开关：数据库单行配置，默认关闭，超级管理员在管理端切换；后端每次请求校验，关闭时用户端工单接口返回 503 `TICKET_FEATURE_DISABLED`，工单通知同步对用户隐藏
- 附件：随机存储键、扩展名 + 魔数 + 解码三重校验、40MP 上限、临时附件 24 小时未绑定自动清理、读取必须带鉴权

**管理后台**

- 用户、团队、日程、团队任务、通知、提醒记录、管理员账号的资源列表与详情
- 统计概览、操作审计日志、超级管理员权限控制
- AI 配置（Key 池、服务商参数）与 AI 调用日志、用量统计
- 工单管理与模块开关

**桌面端（Tauri 2）**

- 完整工作台通过 `@web` 复用用户端页面：四视图、负荷摘要、日终调查、工单、通知等
- 快捷时间轴小窗（同一主窗口的 `quickTimeline` 模式）与快捷日程/任务/团队任务编辑器
- 快捷团队任务拆解、快捷疲劳调查、快捷提醒选择
- 系统托盘与托盘菜单跳转、未填调查提醒、窗口置顶、透明度调节和原生通知
- 桌面偏好设置与自定义标题栏

### 当前交付边界

- 疲劳评估只处理个人日程；团队任务完成疲劳按执行人单独记录并只计入完成日展示负荷，不参与个人承受上限校准。
- 疲劳结果只用于个人日程规划参考，不构成医疗诊断或健康建议。
- 负荷按日程归属日期计算，预计投入时间、实际投入时间和跨多天精确分摊仍属于后续增强。
- 工单模块默认关闭；截图存放于后端本地目录（`DAYLIANE_TICKET_STORAGE_DIR`，默认 `./data/tickets`），容器部署必须挂宿主机卷，否则重建容器后截图丢失。
- 工单限流、登录失败限流和邮箱敏感操作限流都保存在**单个后端进程内存**中，多实例部署需要在可信网关补同等限流，或改用共享的分布式计数存储。
- 桌面端已在 Windows 150% 缩放下完成实机验收；版本化安装包、自动更新、100%/125% 缩放复核和回滚演练仍是发布前门禁。根目录现有 `Dayliane-timeline.exe` 是早前打包的版本，尚未用当前源码重新打包。
- 移动端尚未交付，`android-app/` 目前只是空目录占位。
- 当前服务器部署为公网 IP + HTTP（尚无域名与 HTTPS），异地自动备份和服务器重启恢复演练未完成，详见 [服务器部署记录](./部署记录.md)。

业务边界、接口、算法与验收记录见：

- [个人日程紧急度与疲劳评估实现记录](./计划/已完成/个人日程紧急度与疲劳评估实现计划.md)
- [用户端计划 v2.1-2：疲劳模型与团队完成负荷收敛版](./计划/已完成/用户端计划v2.1-2.md)
- [用户端疲劳度每日进度修复](./计划/已完成/用户端疲劳度每日进度修复.md)
- [工单模块开发计划](./计划/工单模块开发.md)
- [用户邮箱注册与认证改造计划](./计划/已完成/用户邮箱注册与认证改造计划.md)

## 技术架构

```text
 web-user :5173 ──────┐
 admin-web :5174 ─────┼── HTTP / JSON ──> Spring Boot API :8080 ──> MySQL 8.4
 desktop / Tauri 2 ───┘                         └───────────────> AI Provider
```

技术栈：

- 后端：Java 17、Spring Boot 3.4.5、Spring JDBC、Flyway、MySQL 8.4
- 用户端：Vue 3、Vite、Vue Router、Pinia、lucide 图标、Vitest
- 管理后台：Vue 3、Vite、Element Plus、Pinia、Vitest
- 桌面端：Tauri 2、Vue 3、TypeScript、Vite、Rust/Cargo
- AI：由后端统一调用，支持管理端维护的加密 Key 池和环境变量兜底
- 工单截图：后端本地目录存储，读取必须带鉴权，不做永久公开地址

## 目录结构

```text
.
├─ .github/workflows/ci.yml   CI：后端测试、用户端与管理端测试/类型检查/构建、Compose 校验与镜像构建（桌面端未纳入 CI）
├─ .env.docker.example        Docker 环境变量模板
├─ README.md
├─ 启动.md                    本机启动指南（端口约定与排障）
├─ 部署记录.md                服务器部署记录与维护手册
├─ 项目提示.md
├─ start-backend-java17.cmd   本机后端启动脚本（自动加载根目录 .env）
├─ docker-compose.yml
├─ deploy/                    SPA 容器内的 Nginx 配置
├─ design/                    架构、数据库与 API 设计文档
├─ 计划/
│  ├─ 后续计划.md
│  ├─ 日程提醒App计划文档.md
│  ├─ 桌面端计划v2.2.1.md
│  ├─ 管理端计划v2.3.1.md
│  ├─ 移动端App开发计划.md
│  ├─ 工单模块开发.md
│  └─ 已完成/                  已交付并归档的计划
├─ android-app/               移动端占位目录（尚未开发）
├─ logs/                      运行时日志和调试输出
└─ src/
   ├─ backend/                Spring Boot 后端
   ├─ web-user/               Vue 用户网页端
   ├─ admin-web/              Vue 管理后台
   └─ desktop/               Tauri 桌面端（Rust 层位于 src/desktop/src-tauri）
```

完整桌面工作台通过 `@web` 复用用户端页面；快捷小窗口是同一主窗口的 `quickTimeline` 模式，不是独立的第二个窗口。

## 数据库与迁移

- Flyway 在 dev 与 prod 都启用，**重启后端即自动应用迁移**；测试 profile 关闭 Flyway，改用 `src/test/resources/schema-test.sql`。
- 迁移脚本只增不改：已执行过的版本不得修改，新变更追加新的版本号。
- 当前最高版本 **V31**：V30 增加管理端数据视图、系统状态、AI 配额和安全遥测基础表；V31 增加风险复核、AI 配额覆盖规则、告警分类，以及 AI 团队 / 失败类型 / 延迟字段。工单模块仍由 V29 提供 8 张表（`ticket_setting`、`ticket`、`ticket_message`、`ticket_attachment`、`ticket_follow`、`ticket_reaction`、`ticket_event`、`ticket_idempotency`）。
- 真实 MySQL 迁移路径测试需要环境变量 `DAYLIANE_TEST_MYSQL_URL` / `DAYLIANE_TEST_MYSQL_USERNAME` / `DAYLIANE_TEST_MYSQL_PASSWORD`，未提供时相关用例会被跳过。
- 演示/种子账号（V2、V4、V6）在生产配置下已由 V11 改写为 BCrypt 哈希并置为停用；开发环境由 `DEMO_DATA_ENABLED` 重新初始化本地演示数据。
- 数据库名称统一使用 `dayline`；`dayliane` 仅保留为产品标识、Java 包名和历史目录名。

## 配置与安全

AI 调用统一经过后端，前端不会保存或暴露明文 AI Key。

### 环境变量

`src/backend/application-example.env` 提供后端基础变量模板，`.env.docker.example` 提供 Docker 模板。Spring Boot 不会自动读取前者，请通过终端、IDE 或部署环境显式注入（本机可用 `start-backend-java17.cmd` 自动加载根目录 `.env`）。主要变量如下：

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
TRUST_FORWARDED_HEADERS            # 是否信任代理转发的客户端 IP，默认 false
EMAIL_REGISTRATION_ENABLED         # 新邮箱注册总开关，默认 true；临时回滚时设为 false
EMAIL_OTP_SECRET                   # 邮箱验证码摘要密钥，生产环境使用独立随机值
EMAIL_OTP_TTL                      # 验证码有效期，默认 5m
EMAIL_OTP_RESEND_COOLDOWN          # 同邮箱重发冷却，默认 60s
EMAIL_OTP_MAX_ATTEMPTS             # 单个验证码最大尝试次数，默认 5
EMAIL_OTP_MAX_PER_EMAIL_PER_DAY    # 同邮箱每日发送上限，默认 10
EMAIL_OTP_MAX_PER_IP_PER_HOUR      # 同 IP 每小时发送上限，默认 30
TENCENT_SES_ENABLED                # 腾讯云邮件发送开关，默认 false
TENCENT_SES_REGION
TENCENT_SES_SECRET_ID
TENCENT_SES_SECRET_KEY
TENCENT_SES_FROM_EMAIL
TENCENT_SES_TEMPLATE_ID
TENCENT_SES_REPLY_TO
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
DAYLIANE_TICKET_STORAGE_DIR        # 工单截图存储目录，默认 ./data/tickets；生产必须挂宿主机卷
DAYLIANE_TICKET_MAX_FILE_SIZE      # 工单单文件上限，默认 11MB（业务校验为 10MiB）
DAYLIANE_TICKET_MAX_REQUEST_SIZE   # 工单单请求上限，默认 13MB
DAYLIANE_FATIGUE_ENABLED          # 疲劳模块总开关，默认 true
DAYLIANE_FATIGUE_ALERTS_ENABLED   # 负荷提醒开关，默认 true
DAYLIANE_FATIGUE_SURVEYS_ENABLED  # 日终调查开关，默认 true
DAYLIANE_FATIGUE_LEARNING_ENABLED # 个性化校准开关，默认 true
DAYLIANE_FATIGUE_RETENTION_DAYS   # 调查数据保留天数，默认 180
DAYLIANE_FATIGUE_ALLOWED_USER_IDS # 可选灰度用户 ID 列表；留空表示不限制
```

`TRUST_FORWARDED_HEADERS` 只能在后端端口不对公网开放、且仅允许可信反向代理直连时设为 `true`，并由该代理覆盖客户端传入的 `X-Forwarded-For`。Docker Compose 默认将后端 `8080` 端口映射到宿主机，因此模板保持 `false`，避免客户端伪造来源 IP 绕过验证码和登录限流。

`EMAIL_REGISTRATION_ENABLED=false` 是新邮箱注册的临时回滚开关：会同时停止注册验证码发送和公开邮箱注册，并返回 HTTP 503 `registration unavailable`；既有邮箱/手机号登录、绑定邮箱和找回密码不受影响。内部兼容用的手机号注册服务方法不受此开关影响。

工单的限流阈值（建单 2 次/分、20 次/天，回复 10 次/分、100 次/天，上传 200MB/天，读取 120 次/分）与图片像素上限（4000 万像素）目前写在 `application.yml`，不是环境变量，需要调整时直接改配置。

公开部署前请确认：演示账号已在 V11 中停用、`DEMO_DATA_ENABLED=false`、`JWT_SECRET` 与 `EMAIL_OTP_SECRET` 为独立随机值、`TRUST_FORWARDED_HEADERS` 保持 `false`（或已由可信代理覆盖来源 IP）。历史上使用过的密钥即使已从仓库或配置文件移除，也应在服务商控制台完成轮换后再部署生产环境；`AI_API_KEY_ENCRYPTION_SECRET` 在已有加密数据的环境中必须保持稳定。

### 管理端 Key 池

管理员可以在管理端的 AI 配置页面直接维护多个 Key：

1. 按优先级从前到后尝试启用的 Key。
2. 当前 Key 调用失败后自动尝试后面的 Key。
3. 每个数据库 Key 可选配置独立 Base URL；留空时继承服务商配置中的默认地址。
4. Base URL 会自动补全 `/v1`；传入完整的 `/v1/chat/completions` 地址时会规范化为 Base URL。
5. 数据库中只保存加密密文和脱敏值，页面不回显明文。
6. 环境变量 `AI_API_KEY` 作为最后兜底，不参与数据库 Key 的优先级排序。
7. Key 的启用状态、优先级调整、测试结果和变更操作写入审计日志。

独立 Key 地址仅允许 HTTPS 且必须解析到公网地址；默认/环境变量地址仍受 `AI_ALLOWED_HOSTS` 限制。

### 限流与并发约定

- 登录失败限流、邮箱敏感操作的当前密码校验限流、工单提交/回复/上传限流都保存在单个后端进程内存中，适用于当前的单实例部署。
- 管理端修改工单状态、优先级、隐藏等操作使用乐观版本号，版本不一致返回冲突，由管理端提示刷新后重试。

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

仓库另提供本机辅助脚本 `start-backend-java17.cmd`，它会自动加载根目录 `.env` 并把 `JAVA_HOME` 指向 `D:\JDK(java)`。如果本机路径不同，请先调整脚本内的 `JAVA_HOME`：

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

### 工单模块本地调试

- 模块默认关闭：用超级管理员登录管理端「工单管理」开启，或直接改 `ticket_setting` 表的 `enabled` 字段。
- 截图落盘目录默认 `src/backend/data/tickets`（相对启动目录），本地调试可直接查看。
- 上传上限依赖 `spring.servlet.multipart` 配置（11MB / 13MB），默认值已配置在 `application.yml`。

## 自动化检查

```powershell
# 后端（带 MySQL 环境变量时会额外跑真实迁移路径用例）
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

# 桌面端 Rust 层
cd .\src-tauri
cargo check
cargo test
```

打包前请确认构建产物不会覆盖根目录当前版本的 `Dayliane-timeline.exe`。新桌面包应输出到独立的版本目录，完成 Windows 实机验证后再决定是否替换默认启动程序。

最近一次全量回归基线（2026-09-23）：

| 目标 | 结果 |
| --- | --- |
| 后端 `mvn clean test`（含真实 MySQL 迁移 V1→V31） | 241 项，240 通过，1 跳过 |
| 用户端 | 76 项通过，`typecheck` 无报错 |
| 管理端 | 55 项通过，`build` 通过 |
| 桌面端 | 25 项通过，`typecheck` 无报错 |

## Docker 部署

在项目根目录准备 Docker 环境变量文件，至少设置数据库密码、JWT 密钥、邮箱验证码摘要密钥和内部提醒扫描密钥，然后运行：

```powershell
Copy-Item .env.docker.example .env
# 编辑 .env，至少替换 DB_PASSWORD、JWT_SECRET、EMAIL_OTP_SECRET 和 REMINDER_SCAN_TOKEN
docker compose up --build -d
```

默认数据库名为 `dayline`，与 `docker-compose.yml`、后端示例环境变量保持一致。

启动后访问：

- 用户端：`http://127.0.0.1:5173`
- 管理端：`http://127.0.0.1:5174`
- 后端健康检查：`http://127.0.0.1:8080/actuator/health`

Docker Compose 当前负责后端、MySQL、用户端和管理端；桌面端需要在 Windows 客户端单独构建和运行。内部提醒扫描接口必须携带 `X-Internal-Token`，值为部署环境中的 `REMINDER_SCAN_TOKEN`。

### 启用工单模块前必须补齐两处部署配置

1. **截图持久化**：`docker-compose.yml` 目前没有为后端挂载附件目录，也没有透传 `DAYLIANE_TICKET_STORAGE_DIR`。使用工单模块前需要给 `backend` 增加卷映射（例如 `./data/tickets:/app/data/tickets`）并设置 `DAYLIANE_TICKET_STORAGE_DIR=/app/data/tickets`，否则重建容器后所有截图丢失。
2. **Nginx 上传上限**：Docker 中用户端以 `VITE_API_BASE_URL=/api/v1` 构建，上传请求经容器内 Nginx（`deploy/spa-nginx.conf`）转发到后端；该配置未设置 `client_max_body_size`，Nginx 默认 1MB 会让超过 1MB 的截图返回 413。启用工单前需要在 `location /api/` 中加入 `client_max_body_size 13m;`。

六个 `DAYLIANE_FATIGUE_*` 变量当前未由 `docker-compose.yml` 显式透传，容器部署默认使用上方标注的默认值；如需覆盖，应将对应变量加入后端服务的 `environment` 配置。腾讯云 SES 默认关闭，启用前必须完成发信域名、发信地址和模板审核，并通过环境变量注入凭证，不能把密钥提交到仓库。

用户端、管理端和桌面端的 API 地址分别参考各目录下的 `.env.example`，默认后端地址为 `http://localhost:8080/api/v1`（桌面端默认使用 `127.0.0.1`）。

## 相关文档

- [启动说明](./启动.md)
- [服务器部署记录](./部署记录.md)
- [总体产品与版本计划](./计划/日程提醒App计划文档.md)
- [后续计划（公开部署前门禁）](./计划/后续计划.md)
- [工单模块开发计划](./计划/工单模块开发.md)
- [桌面端计划 v2.2.1](./计划/桌面端计划v2.2.1.md)
- [管理端计划 v2.3.1](./计划/管理端计划v2.3.1.md)
- [移动端 App 开发计划](./计划/移动端App开发计划.md)
- 已完成计划归档：[计划/已完成](./计划/已完成)

## 后续规划

1. 推进桌面端 v2.2.1：常驻工作台、全局搜索启动器、悬浮待办、任务栏角标、专注联动勿扰与崩溃日志，并补齐版本化安装包与自动更新
2. 推进管理端 v2.3.1：图表式数据视图（趋势、分布、下钻与预警）与账号风控、备份监控、AI 额度控制
3. 移动端 App 方案落地（`android-app/` 目前为空占位），覆盖设备注册、深链接和原生推送
4. 为个人日程增加预计投入时间、实际投入时间和跨多天负荷精确分摊
5. 完成公开部署收尾：域名、HTTPS、数据库异地备份、重启恢复演练与安全验收
6. 扩展文件附件、评论、周期任务的更细粒度配置等能力
