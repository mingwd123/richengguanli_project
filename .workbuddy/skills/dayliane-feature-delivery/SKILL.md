---
name: dayliane-feature-delivery
description: 在 Dayliane 项目里端到端落地一个功能（Flyway 迁移 + Spring 后端 + Vue 用户端 + 测试 + 回填计划文档）的验证过的流程与本机环境约定。当用户要求实现 `计划/*.md` 里的待办项、修复后端/前端缺陷、或需要在本机跑后端 mvn 测试与前端测试时使用。关键词：Dayliane、dayliane、实现计划、待办项、mvnw、Flyway、schema-test.sql、ScheduleService、FatigueService、web-user、回填勾选。
agent_created: true
---

# Dayliane 功能落地流程

项目根：`D:\trae\dayliane`，模块：`src/backend`（Spring Boot 3.4.5 + JDK 17）、`src/web-user`（Vue3 + Vite）、`src/desktop`（Tauri，通过 vite 别名 `@web` 复用 web-user 源码）、`src/admin-web`。

## 0. 先看计划文档的既有写法

- 每个功能都有对应 `计划/*.md`，含「数据库变更 / 阶段 / 接口清单 / 验收标准」。
- 开工前先确认哪些条目未勾选；完成后回填 `[x]` 并在文末「完成状态核对」章节补落地依据。

## 1. 环境（重要，否则命令直接失败）

```bash
export JAVA_HOME="D:\\JDK(java)"          # 系统 JAVA_HOME 是 JDK 8，会用错
export PATH="/d/JDK(java)/bin:$PATH"
cd /d/trae/dayliane/src/backend            # mvn 不在 PATH，必须用 wrapper
./mvnw.cmd clean test
```

- 想连带验证 MySQL 迁移路径（默认会被跳过）：
  ```bash
  export DAYLIANE_TEST_MYSQL_URL="jdbc:mysql://localhost:3306"
  export DAYLIANE_TEST_MYSQL_USERNAME="root"
  export DAYLIANE_TEST_MYSQL_PASSWORD="mysql"   # 见 application-dev.yml 默认值
  ```
  设上后 `MySqlFlywayMigrationPathTests` 会建临时库跑完全部迁移再 drop。
- 前端：`cd /d/trae/dayliane/src/web-user && npm test && npm run typecheck`。
- `npm run build` 在沙箱内会因 vite 清空 `dist/assets` 触发批量删除守卫 / EPERM 而失败，属沙箱限制；改用 `npx vite build --emptyOutDir=false` 也可能因写权限失败，直接在最终回复里说明未复跑即可。
- 项目根 `.env` 由 `start-backend-java17.cmd` 自动加载；**沙箱从 Bash 启动后端会被注入 `SERVER_PORT=0`**（`server.port: ${SERVER_PORT:8080}` 被覆盖），`env -u SERVER_PORT` 无效，必须用命令行参数压过它：
  `cd src/backend && ./mvnw.cmd spring-boot:run -Dspring-boot.run.arguments=--server.port=8080`
- 端口固定为 后端 8080 / 用户端 5173 / 管理后台 5174 / 桌面端 1420；前端直连 8080，后端不在 8080 页面就是白的。
- **Flyway 只在启动那一刻跑迁移**：改了后端代码又加了迁移，必须重启后端（库里没迁移、代码已 select 新列 → `Unknown column`）。重启属「环境改动」，动手前要拿到用户明确同意。

## 2. 常见的假失败

- **`mvn test` 报某个测试类的源文件已不存在** → `target/test-classes` 里有陈旧 `.class`（历史上是 `RecentFlywayMigrationTests`，断言 H2 不支持 V23 的 MySQL 多列 `ALTER TABLE`）。跑 `mvn clean test` 即恢复；不要改生产代码迁就它。
- **大量测试同时报 `ApplicationContext failure threshold exceeded`** → 真实原因通常是 `DataSourceInitializer` 初始化脚本失败。去 `target/surefire-reports/*.txt` 里 grep `Caused by`。最常见：`schema-test.sql` 新增了表但忘了在文件顶部加 `DROP TABLE IF EXISTS`（H2 内存库 `DB_CLOSE_DELAY=-1` 跨上下文复用，重复 CREATE 会报 already exists）。

## 3. 加数据库变更

1. `src/backend/src/main/resources/db/migration/` 新增 `V<下一个序号>__<snake_case_描述>.sql`（当前最高 V28）。生产用 MySQL 语法，可参考 V23/V24 的写法（`ADD COLUMN` 多列、`CONSTRAINT chk_... CHECK (...)`、`UNIQUE KEY`）。
2. **同步** `src/backend/src/test/resources/schema-test.sql`：顶部 `DROP TABLE IF EXISTS` 列表 + 表结构（H2 里唯一约束写成 `CONSTRAINT uk_x UNIQUE (...)`）+ 索引（`CREATE INDEX` 单独语句）。
3. 若加了迁移，更新 `MySqlFlywayMigrationPathTests` 里的迁移数量与版本断言。
4. 结构断言参考 `ScheduleMigrationSmokeTests`（查 `information_schema.columns` / `table_constraints` / `indexes`）。

## 4. 写后端

- 领域服务是**大而全的单 Service**（`ScheduleService` 已 2000 行），新功能加在同类服务里，方便复用其 private 辅助方法（`requireSchedule`、`userZone`、`pausePendingReminders`、`recalculateScheduleDates`）。
- 参数校验抛 `BusinessException(400, "field is invalid")`；查不到抛 404。
- SQL 必须同时兼容 MySQL 与 H2：**不要用 `insert ... on duplicate key update` / `merge`**。幂等写法用「先 update，affected=0 再 insert，捕获 `DataIntegrityViolationException` 回退 update」（见 `ScheduleService.saveProgressDaily`）。
- 时间戳统一 `utc_timestamp()`；日期归属按用户时区（`userZone(userId)`）计算。
- 影响负荷的写操作后必须调 `fatigueService.recalculateDates(userId, dates, invalidateTraining)`；负荷真的变了才传 `true`（会作废相关日终调查样本并触发重训）。

## 5. 写用户端（web-user 改完 desktop 自动复用）

- 类型放 `src/types/index.ts`；纯函数放 `src/utils/helpers.ts`（顺手补 `utils/helpers.test.ts`）。
- API 封装都在 `src/stores/app.ts` 的 setup store 里，用 `request<T>(path, { method, body })`，再从 store 的 return 对象导出。
- 全局样式在 `src/style.css`；页面大多不写 `<style>`。新增 scoped 样式也可以，但已有类名优先复用（`tag` / `muted` / `plain-button` / `modal-backdrop` / `modal-panel`）。
- **`.login-form label` / `.modal-panel label` 是 `display: grid`**：往表单里塞「勾选框 + 文案」这类行内结构时，必须显式写回 flex（`.login-form label.remember-toggle { display: flex }`），否则勾选框和文字上下堆叠。
- **多根节点（fragment）组件不能用父组件的 scoped 类名控制布局**：父组件的 scope 属性只落到 fragment 锚点，不会落到根元素上（浏览器里表现为「所有警告都指向该组件、样式完全不生效」）。修法二选一：父级用 `:deep(.xxx)`；或组件里 `defineOptions({ inheritAttrs: false })` + 把 `attrs.class` 显式绑到根元素（后者还能顺带消掉 `Extraneous non-props attributes (class)` 警告）。
- 需要「记住密码」这类本地持久化时，单独放 `src/utils/xxx.ts`（做一层 base64 编码，**不是加密**，注释里写明 localStorage 对同源脚本可读），store 在 setup 里读一次回填，登录成功才写入、取消勾选立即清除；两端（web-user / admin-web）各留一份，互不共享别名。
- 提交前跑 `npm test` 与 `npm run typecheck`（web-user 与 desktop 各跑一次，admin-web 有独立测试与 `src/utils/*.js`）。

## 6. 真实浏览器验收（自动化测试全绿 ≠ 界面是对的）

装（全局装会 `EPERM`，装到托管工作区）：
```bash
WS="/c/Users/ming/.workbuddy/binaries/node/workspace"
cd "$WS" && npm install agent-browser --no-fund --no-audit && ./node_modules/.bin/agent-browser install
```
用：`"$WS/node_modules/.bin/agent-browser"`（不在 PATH 上）。要点见项目 `MEMORY.md` 的「浏览器验收」小节——原生 `select`/`date` 用 `eval` 设值 + `dispatchEvent(new Event("input"))`；`set viewport 375 812` 查横向溢出；`console` / `errors` 看警告（`reload` 后再看一次最干净）。

- 桌面端 `npm run dev` 只起 vite（1420），可登录，但**浏览器模式下快捷时间轴取不到日程数据**，视觉验证仍需 Tauri 实机；能验的是「编辑器开关 → 创建成功」这类动作链路。
- 验证用账号：用户端 `13800138000 / Abc12345`，管理端 `admin / Admin12345`（登录页已不预填，要用 `eval` 填进去）。
- 每次实测结束要删掉自己造的验证数据（soft delete 会让进度负荷一并消失），别碰用户自己的任务。

## 7. 收尾

1. 全量回归：后端 `mvn clean test`（带 MySQL 环境变量）、web-user `npm test` + `npm run typecheck`、desktop `npm run typecheck`、admin-web `npm test`。
2. 只在用户文档明确要求的位置回填（如 `计划/*.md` 的完成状态核对章节、修复文档的「完成记录」表格）；核对结论优先放对话回复或 `D:\workbuddy\data`，不要往用户文档里加章节。
3. 追加一份到 `.workbuddy/memory/YYYY-MM-DD.md`；可复用的约定写 `MEMORY.md`。
