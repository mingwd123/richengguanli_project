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
- 附件存 `dayliane.ticket.storage-dir`（默认 `./data/tickets`），**生产部署必须挂宿主机卷**；multipart 上限 11MB/13MB 已在 application.yml 配置。**两个待补的部署缺口**（2026-09-21 核对，尚未修）：① `docker-compose.yml` 没给 backend 挂附件卷、也没透传 `DAYLIANE_TICKET_STORAGE_DIR`；② `deploy/spa-nginx.conf` 无 `client_max_body_size`，而 Docker 里用户端以 `VITE_API_BASE_URL=/api/v1` 构建、上传经容器内 Nginx 反代，默认 1MB 会导致大截图 413。
- 限流是进程内滑动窗口（`TicketRateLimiter`），单实例前提；多实例部署时需换集中存储。
- 浏览器验收注意：`onBeforeRouteLeave` 从 vue-router 导入；页面里 `window.confirm` 会卡死 CDP，自动化前先补丁 `window.confirm=()=>true`；el-select 要用真实鼠标事件。

## 仓库安全现状（2026-09-23 排查，未清理完）

远端 `ssh://git@ssh.github.com:443/mingwd123/richengguanli_project.git` 是 **public 仓库**，`main` 与 `origin/main` 同步（最后推送 2026-09-20），所以历史内容一律视为已公开。

已确认公开放置的内容（都在提交历史里，`.gitignore` 管不到）：
1. **`src/backend/data/tickets/**`——63 个 PNG，共 70MB，是工单系统里用户真实上传的附件**。既泄露用户内容也把仓库撑到 89MB。最该处理的一项。
2. `测试团队账号密码.md`——测试账号（13800138000 等）统一密码 `Abc12345`，且文件自称"测试团队所有账号均为活跃状态"。服务器是公网 IP + HTTP 部署，这些账号若在生产可用即为真实风险。
3. 三张 `*-zoom.png`（`fingerprint-zoom.png` / `key-nearest.png` / `public-key-zoom.png`）——**只是 SSH 公钥内容与指纹**（`cat dayliane_github.pub` 的输出，key 备注 `dayliane-production-readonly`），不是私钥。公钥本身不是秘密，风险低；泄露的是服务器主机名 `VM-0-3-ubuntu`、路径 `/opt/dayliane` 这类部署信息。
4. `.workbuddy/memory/*.md` 与 `skills/**/SKILL.md`——智能体工作文件，含本机 MySQL `root/mysql`（仅 localhost）。
5. `Dayliane-timeline.exe`（14.8MB 构建产物）、2 个 `*.log`。

`.gitignore` 已在 2026-09-23 更新（密钥/证书、key 截图、`src/backend/data/`、exe 安装包、`.workbuddy/` 等）。**但忽略规则对"已跟踪"文件无效**：上述约 75 个文件仍在索引里，需 `git rm --cached` + 提交；要真正从公开历史里抹掉需 `filter-repo`/BFG 重写历史 + force push。

## 开题报告 PPT 构建（slidep，2026-09-24 补）

- 源目录 `D:\workbuddy\data\ktbg_ppt\开题报告\`：`slides/01..17.slide` 是源文件，`STORY.md` 是叙事与版式设计稿，成品 `开题报告.pptx` 同步到桌面 `开题报告-面向个体疲劳容量的日程排程系统-明经涛.pptx`。改 PPT 一律改 `.slide` 源文件，别改桌面那份。
- 改完必须跑全套几何检查（脚本在 `D:\workbuddy\data\ktbg_work\`）：`slidep-validate --all`（语法）、`check_overflow.py`（文字溢出卡片）、`check_bounds.py`（越界画布）、`verify_layers.py`（方格底纹图层）。**语法校验查不出布局错。**
- **坑：重跑 `slidep-start` 是「追加」不是「重建」**。守护进程停掉后再跑，会把 17 页变成 34 页（1–17 新版 + 18–34 旧版重复）。干净重建：先 `taskkill` 掉 `.slidep/pid` 里的 node 进程 → 把 `开题报告.pptx` 移走 → 再 `slidep-start`。
- **现在能目视了（重要）**：本机装了 PowerPoint，可用 COM 导出单页 PNG 再读图核对——`Presentations.Open($src,$true,$false,$false)`（只读、不显示窗口）→ `Slides.Item(n).Export($png,"PNG",1280,720)` → `Close()` + `Quit()`。这解开了此前"本机没装 LibreOffice、无法渲染"的死结，此前所有版式结论都只能靠解析坐标推断。
- 加内容的正确姿势：先解析 pptx 量出卡片内已用内容的底边（`a:off`/`a:ext` ÷ 9525 得像素），剩余高度扣掉下内边距才是可用空间；加完再量一次。页脚安全线：内容区底 y=632。

## 疲劳判定算法要点（2026-10-02 核对源码）

- 常量：默认五档权重 **1/2/3/5/8**（L1–L5），默认容量 **18**（限幅 5–60），算法 version 2。容量含义 = 得 75 分对应的负荷。
- **分数 = 负荷 × 75 ÷ 容量**，封顶 100；档位 `thresholdBand`：<50 舒适 / <70 较满 / <85 较疲劳 / <100 高负荷 / ≥100 可能过载；`alertBand` 从 ≥70 起，severity 1/2/3。
- 负荷：计划负荷 = 当天全部未取消日程的权重和；已完成负荷 = 完成日按完成疲劳权重（未选则回退预计疲劳）+ 每日进度（`progress_delta/100 × 当时权重`）。**进度型日程到 100% 时不再补写整项完成负荷**（防重复）。
- 提醒：`scanFatigueAlerts` 每 5 分钟扫（`FatigueSurveyScanTask`），同一用户 2 小时内只提醒一次，可按档位「今天不再提醒」；team 完成疲劳只进 `teamCompletedLoad`，**不影响个人模型与预警**。
- 入模条件（`model_eligible`）：当天有已完成个人日程负荷>0、次日 21:30 起 48h 内提交、期间未换时区、无强外部因素（等级 2 或标签「身体不适」「存在未记录任务」）；轻度外部因素（等级 1）学习权重 0.5。
- 容量校准：候选 = 75 × 已完成负荷 ÷ clamp(自评,10,95)，≤28 天窗内按「学习权重 × 新近度」加权中位数，平滑 0.85 旧 + 0.15 新，单步限 ±10%，限幅 5–60；从第 7 条有效调查起才开始动。
- 权重拟合：需 ≥30 条有效调查且跨度 ≥27 天、每档至少出现在 5 天；最小二乘 + 正则（λ=4 拉向默认值），结果限制在默认值 ±30% 且严格递增。
- 阶段：<7 条 基础估算(default) / 7 条起 校准中(calibrating) / ≥21 条且跨度 ≥20 天 已个性化(personalized)。

## 前端结构约定

- `src/desktop` 通过 vite 别名 `@web` 复用 `src/web-user/src` 的组件与 store，改 web-user 后桌面端自动继承；桌面端自己的组件在 `src/desktop/src/components`。
- 「新建日程」的真实表单是 `src/web-user/src/views/SchedulesPage.vue` 里的内联 `<form>`；`src/web-user/src/components/ScheduleForm.vue` 是**无人引用的死组件**，改它不会生效。
- 页面大多不写 `<style>`，共用样式在 `src/web-user/src/style.css`；`.modal-panel label { display: grid }` 会覆盖组件的 flex，弹窗内自定义 label 布局要显式提高优先级。
- 桌面端有自己的 `src/desktop/src/desktop.css`。
- **多根节点（fragment）组件不能在父组件 scoped 样式里用类名控制布局**：父组件的 scope 属性只会落到组件的 fragment 锚点，不会落到根元素上。父级要用 `:deep(.xxx)`，或组件里 `defineOptions({ inheritAttrs: false })` + 把 `attrs.class` 显式绑到根元素（后者的额外好处是调用方传的 `class` 不再被 Vue 丢弃并报警告）。
- **「待办 + 累计进度 100%」是可达状态**（取消后恢复、或修正回退再补回），面板必须给出「标记完成」入口，不能只留「完成剩余进度」。判断逻辑在 `utils/helpers.ts` 的 `progressCompletionActionLabel` / `progressSubmitDialogTitle` / `isProgressComplete`，网页与桌面共用。

## 配色与设计系统（2026-09-22 核实，做任何视觉产出的基准）

- **`src/web-user/src/style.css` 里有两套配色，以 `/* Dayliane 2.0 visual system */`（约行 461 起的 `:root`）为准**——它写在文件后段、优先级更高，是系统**实际渲染**的那套。文件前段（行 18–156）的 `#2f80ed` 旧蓝样式大多已被 2.0 覆盖（`.logo-icon`、`.nav-items button.active`、`.tag`、`.date-cell.today`、`.primary`、`.eyebrow` 等），**别拿旧蓝当主色**。
- **Dayliane 2.0 令牌**：主色 `#0b8f86`（hover/深 `#08766f`）、浅底 `#e2f4f1` / `#edf8f6`；页面底 `#f3f7f7`；卡片 `#ffffff`、次级底 `#edf4f3`；边框 `#dbe6e4` / 软 `#e8efee` / 强 `#cbd9d7`；文字 `#11201f`（标题）/ `#22302f`（正文）/ `#4c605e`（次）/ `#70817f`（辅助）；语义 danger `#d94c62`、warning `#b87014`、success `#17805b`（各带 `-soft` 浅底）；次要蓝 `#4175dc`；珊瑚 `#e96f61`。有 `[data-theme="dark"]` 分支。
- 圆角：卡片/面板 **8px**、控件 **7px**、标签 **5px**（2.0 已废弃胶囊，只剩进度条等用 999）。
- 品牌标识 `.logo-icon`：深底 `#11201f` 圆角 8 + `box-shadow: inset -8px -8px 0 var(--primary)` 的右下 L 形青绿角标。
- `body` 背景除 `--bg` 外还叠 **32×32px 方格**，线色 = `color-mix(--border-soft 42%, --bg)` ≈ `#eef4f3`。
- 管理端 `src/admin-web` 用的是 **Element Plus 默认观感**（`#409eff` + `#001529` 侧边栏 + `#f5f7fa` 底），与用户端不是同一套——做管理端相关产出时别混用。

## 毕业论文文献综述格式要求（泉州师范学院，原件：材料3-文献综述封面与格式模板.doc）

原件在 `C:\Users\ming\Downloads\材料3-文献综述封面与格式模板.doc`；完整提取结果留档在 `D:\workbuddy\data\文献综述格式要求-原件提取.txt`。论文题目《面向个体疲劳容量的日程排程系统设计与实现》，作者明经涛 231304011，指导教师曾台盛 **教授**（不是副教授）。

**页面**：A4（21×29.7cm），上下左右页边距均 2.00cm；页脚有页码域，页眉为空。

**封面**：`泉 州 师 范 学 院`（黑体 26pt 加粗居中）→ `毕业论文（设计）文献综述`（黑体 26pt 加粗居中）→ `题  目` 及学院/专业/年级行（宋体 14pt 下划线，「数学与计算机科学 学院  计算机科学与技术 专业  23 级」）→ 学生姓名／学号 → 指导教师／职称 → 完成日期（宋体 14pt）→ `教务处  制`（黑体 14pt 加粗居中）。**封面第 2 段有一行模板残留乱码「片'p + 13×U+FDFD + 就业等信息作」，整段是隐藏文字（OOXML `<w:vanish/>`），Word/WPS 默认既不显示也不打印**（此前误判为"会印出来"，已更正）；只有解析 XML 或开启「显示隐藏文字」才看得到。段落格式为黑体 26pt 加粗居中，与校名行同级，位于校名之上——是模板作者删除某行后留下的残留。

**正文标题与署名**
- 正文大标题：`文献综述——<论文题目>`，三号黑体、加粗、居中、单倍行距
- 署名行 1：`数学与计算机科学学院  计算机科学与技术专业  学号　姓名`，小四楷体加粗居中单倍行距
- 署名行 2：`指导教师　指导教师姓名`，小四楷体加粗居中单倍行距

**摘要与关键词**（均五号楷体、加粗、1.5 倍行距）
- `【摘　要】` 200—300 字，首行缩进 2 字符
- `【关键词】` 3~5 个，用「；」分隔

**正文层级**（全部 1.5 倍行距）

| 层级 | 写法 | 字体字号 | 缩进 |
| :-- | :-- | :-- | :-- |
| 一级 | `一、×××` | 四号黑体加粗 | 无 |
| 二级 | `（一）×××` | 小四号黑体加粗 | 左缩进 2 字符 |
| 三级 | `1.×××` | 五号黑体加粗 | 左缩进 2 字符 |
| 四级 | `（1）×××` | 五号宋体 | 左缩进 2 字符 |
| 正文 | — | 五号宋体 | 首行缩进 2 字符 |

**参考文献**：标题「参考文献」上空二行、小四号黑体加粗、单倍行距；条目五号宋体、单倍行距；**至少 10 篇**。作者姓名写到第三位，其余写「，等」。

**著录格式按本模板（不是知网导出的 GB/T 7714 原样）**——模板用全角逗号与全角括号，且**不带 DOI**：
- 期刊：`[编号] 作者.文章题目名[J].期刊名，年份，卷号（期数）：引文页码.`
- 图书：`[编号] 作者.书名[M].出版社地址：出版社名，出版年份，引文页码.`
- 会议：`[编号] 作者.文章题目名[A].论文集名[C].出版社地址：出版社名，出版年份，引文页码.`
- 类型标识：A 论文集文章／J 期刊／C 论文集／M 书／D 学位论文／S 标准／P 专利／EB-OL 电子文档
- **模板里「图书」示例误标成 [C]（应为 [M]），别照抄这个错。**

**综述内容说明（模板自带）**：功能四条——说明课题为何值得研究、提供最新文献简介与讨论、提供相关概念与理论背景、讨论同课题已开展的研究。综述通常分引子／正文／结论三部分，正文可按**时间演进、研究主题或研究方法论**组织，可另设「发展现状」「历史演变」「研究方法与标准」「进一步需要研究的问题」等节。

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
- 已完成并归档的计划放 `计划/已完成/`（用户会要求「是否完成？完成就移动到已完成」）：归档前要把头部状态、完成记录表改成与实际一致，别让文件自相矛盾。
- 根 `README.md` 是项目对外说明，用户会要求「按现在的项目内容更新」——这类请求是**全量重写**授权，但仍要逐项核源码/配置/计划后再写，不凭印象；改完脚本校验内链。
- 对同一文件**一次改完**，不要多轮返工编辑。
- `D:\workbuddy\data` 是用户指定的交付物存放目录（含浏览器截图）。
- **验收截图不要滥截**（2026-09-22 用户反馈「你弄那么多截图干嘛」）：`D:\workbuddy\data\browser-shots` 曾两天堆到 28 张，多为同页面反复截留痕。以后只截**能一眼看出结论差异**的（修复前/后、移动端 vs 桌面端、关键流程结果），不给同一状态连拍；纯逻辑或可用测试证明的改动不截图，验证结论写在回复里。用户对截图数量的观感敏感，注意少而精。
