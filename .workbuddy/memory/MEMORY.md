# Dayliane 项目长期记忆

## 本机环境与启动约定

- 后端测试必须用 `./mvnw.cmd`（`mvn` 不在 PATH），先 `export JAVA_HOME="D:\JDK(java)"`、`PATH="/d/JDK(java)/bin:$PATH"`。
- MySQL 本机账号：`root` / `mysql`，库名 `dayline`，`jdbc:mysql://localhost:3306`。客户端在 `C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe`。
- Flyway 在 dev 与 prod 都开启，**重启后端即自动应用迁移**；测试 profile 关闭 Flyway，走 `src/test/resources/schema-test.sql`。
- 真实 MySQL 迁移路径测试需要环境变量：`DAYLIANE_TEST_MYSQL_URL` / `_USERNAME` / `_PASSWORD`，否则 `MySqlFlywayMigrationPathTests` 会被跳过。
- **沙箱里从 Bash 启动后端会被注入 `SERVER_PORT=0`**（`server.port: ${SERVER_PORT:8080}` 被覆盖），`env -u SERVER_PORT` 无效。有效做法是传命令行参数（优先级高于环境变量）：
  `cd src/backend && ./mvnw.cmd spring-boot:run -Dspring-boot.run.arguments=--server.port=8080`
- 端口：后端 8080、用户端 5173、管理后台 5174、桌面端 1420。三者都在跑时前端直连 8080，后端不在 8080 页面就会加载失败。

## 工单模块约定（2026-09-20 起生效）

- 迁移最高 **V29**（工单模块，8 张表）。模块开关在 `ticket_setting`（默认 FALSE），超管在管理端「工单管理」切换；关闭时后端所有用户工单接口返回 503 + `TICKET_FEATURE_DISABLED`。
- 工单通知 type='ticket'；用户通知列表/未读数已内置过滤（模块关闭或工单隐藏时不可见），改通知 SQL 时别丢掉 `USER_NOTIFICATION_VISIBILITY`。
- 附件存 `dayliane.ticket.storage-dir`（默认 `./data/tickets`），**生产部署必须挂宿主机卷**；multipart 上限 11MB/13MB 已在 application.yml 配置。
- 限流是进程内滑动窗口（`TicketRateLimiter`），单实例前提；多实例部署时需换集中存储。
- 浏览器验收注意：`onBeforeRouteLeave` 从 vue-router 导入；页面里 `window.confirm` 会卡死 CDP，自动化前先补丁 `window.confirm=()=>true`；el-select 要用真实鼠标事件。

## 前端结构约定

- `src/desktop` 通过 vite 别名 `@web` 复用 `src/web-user/src` 的组件与 store，改 web-user 后桌面端自动继承；桌面端自己的组件在 `src/desktop/src/components`。
- 「新建日程」的真实表单是 `src/web-user/src/views/SchedulesPage.vue` 里的内联 `<form>`；`src/web-user/src/components/ScheduleForm.vue` 是**无人引用的死组件**，改它不会生效。
- 页面大多不写 `<style>`，共用样式在 `src/web-user/src/style.css`；`.modal-panel label { display: grid }` 会覆盖组件的 flex，弹窗内自定义 label 布局要显式提高优先级。
- 桌面端有自己的 `src/desktop/src/desktop.css`。
- **多根节点（fragment）组件不能在父组件 scoped 样式里用类名控制布局**：父组件的 scope 属性只会落到组件的 fragment 锚点，不会落到根元素上。父级要用 `:deep(.xxx)`，或组件里 `defineOptions({ inheritAttrs: false })` + 把 `attrs.class` 显式绑到根元素（后者的额外好处是调用方传的 `class` 不再被 Vue 丢弃并报警告）。
- **「待办 + 累计进度 100%」是可达状态**（取消后恢复、或修正回退再补回），面板必须给出「标记完成」入口，不能只留「完成剩余进度」。判断逻辑在 `utils/helpers.ts` 的 `progressCompletionActionLabel` / `progressSubmitDialogTitle` / `isProgressComplete`，网页与桌面共用。

## 浏览器验收（agent-browser）

- 全局安装会失败（`EPERM` 写 `D:\Node_24\node_global`）。**装到托管工作区**：
  ```bash
  WS="/c/Users/ming/.workbuddy/binaries/node/workspace"
  cd "$WS" && npm install agent-browser --no-fund --no-audit && ./node_modules/.bin/agent-browser install
  ```
  调用用 `"$WS/node_modules/.bin/agent-browser"`；首次 `open` 若静默失败加 `--debug` 重试。
- 常用：`open/reload/close`、`snapshot -i`、`click eN`、`check eN`、`fill eN 值`、`select eN 值`、`set viewport 375 812`、`screenshot <绝对路径>`、`eval '<js>'`。
- 原生 `select` / `datetime-local` 在 snapshot 里常无名，用 `eval` 设 `value` 后 `dispatchEvent(new Event("input",{bubbles:true}))` 才能触发 Vue 的 v-model。
- 测试账号：`13800138000` / `Abc12345`；管理端 `admin` / `Admin12345`。**登录页已不再预填、也不再有「演示账号」提示**（2026-09-20 按用户要求移除），要用得手动输入；账号记录在 `启动.md`、`测试团队账号密码.md`。
- **登录页的「记住密码」**：只在用户勾选且登录成功后，才把账号密码写进 localStorage（`dayliane_login_remembered` / `dayliane_admin_login_remembered`，base64 编码、非加密）；取消勾选立即清除。为了压掉浏览器自带自动填充，密码框用 `autocomplete="new-password"`、账号框与表单用 `off`——改这里时别退回 `current-password`/`username`。
- 桌面端 `npm run dev` 只起 vite（1420），**可以在浏览器里打开并登录**，但快捷时间轴取不到日程数据（依赖 Tauri 侧初始化），所以桌面端的**列表/详情视觉验证仍需 Tauri 实机**；能验证的是「打开编辑器 → 开关存在 → 创建成功」这类动作链路。
- 页面错误与警告：`errors` / `console`（`console --clear` 先清空），`reload` 后再看一次最干净。Vue 的 fragment 警告会把组件名和栈一并打出来，是定位这类问题的好抓手。

## 文档与协作偏好

- 计划文档在 `计划/`。改动要**最小化**：只改用户明确要求的部分，核对/结论类内容放对话或 `D:\workbuddy\data`，不要写进用户的文档。
- 对同一文件**一次改完**，不要多轮返工编辑。
- `D:\workbuddy\data` 是用户指定的交付物存放目录（含浏览器截图）。
