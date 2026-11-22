# AGENTS.md

本仓库是「愈爪|宠物医院预约与在线问诊平台」作品集：前端 Vue 3（`frontend/`），后端 Spring Boot 3 + JDK 17（`backend/`），MySQL 8（`sql/`），Compose 一键栈（`compose.yaml`），计划、ADR 与证据在 `docs/`。

## 四条规矩

### 规矩一：品牌与标识单一事实源，改名先做映射表

- 用户可见品牌只从 `frontend/src/brand.js` 与后端 `BrandProperties`（`yuzhua.brand.*`）取值，页面标题、OpenAPI、包描述、README 均引用它，禁止复制字符串。
- 技术标识（包名 `com.yuzhua`、库名 `yuzhua`、前缀 `yuzhua.`、环境变量 `YUZHUA_*`、镜像/卷/Compose 名）变更前，先写「旧→新」映射表、迁移脚本与 `git grep` 白名单。
- 验收：`git grep` 命中仅剩 `docs/品牌沿革.md`「审计说明」所列白名单。（#12 改名曾波及 242 文件与 32 张截图；单点定义才能收敛为「改一处 + 重跑派生工件」）。

### 规矩二：派生工件一条命令再生成，历史证据只增不改

- `docs/screenshots/` 由 `node tools/screenshots.mjs` 按 `tools/screenshots.manifest.json` 重拍，禁止手改。
- README 实测数字（用例数、覆盖率、QPS、p95、构建包体）必须链向 `docs/test-evidence/**` 原始输出（`mvn verify`、`summary-*.json`、`npm-verify.log`），禁写估算值；配置性数值（阈值、端口、超时、种子量）以代码/脚本为准。
- `docs/test-evidence/**` 按日期归档，复跑写入新日期目录，严禁改写旧证据。

### 规矩三：不变量与门禁先于功能落地，验证按三层执行

- 先定不变量再写代码：写接口定幂等（`Idempotency-Key` + `(user_id, idem_key)` 唯一索引）、定时任务定互斥（`scheduler_lease` 数据库租约）、部署定探针与指标出口；门禁与代码同次提交（#9/#10 后补曾引发 30–40 文件跨层返工）。
- 单测与覆盖率：只测基础可用性与复杂不变量（状态机、越权隔离、边界值、写语义、并发兜底），禁写凑数测试。覆盖率门禁仅卡后端 `SlowSqlInterceptor`（LINE ≥95%，厚 Service 由 `*IT` 覆盖）与前端 `tools/style-guard/index.mjs`、`src/utils/tableDensity.js`（行/分支/函数 ≥95%）。
- 三层验证（按时机执行，不跨层）：
  - **L1 轻量局部**（小段收尾跑，免 Docker）：后端 `mvn test [-Dtest=<类>]`；前端 `node --test tests/<文件>.test.mjs` 或 `npm test && npm run lint && npm run style-guard`；单页 UI `node tools/screenshots.mjs --only <图>|--dry-run`；视频 `node tools/demo-video/build.mjs --dry-run|--skip-record --stage=keyframes`；编排 `docker compose config -q`。（仅 `*IT` 覆盖的并发/幂等/支付改动可按需跑 `mvn test-compile failsafe:integration-test failsafe:verify -Dit.test=<IT类>`）。
  - **L2 合并前门禁**（大改完成或合并前统一跑）：
    ```bash
    cd backend && mvn --batch-mode --no-transfer-progress verify   # 需 Docker
    cd frontend && npm run verify
    node --test .github/scripts/localize-dependabot.test.mjs
    docker compose config -q
    ```
    涉及多页/全局 UI 变更另跑全量 `node tools/screenshots.mjs`。
  - **L3 专项重型**（动到对应链路或发布前显式触发）：`node tools/demo-video/build.mjs` 全量导出；`tools/load/run-load.sh` / `run-steady.sh` k6 压测；`tools/main_flow_browser.mjs` / `frontend/tests/payment-ticket.browser.mjs` / `tools/*_check.mjs` 隔离栈回归；`docker compose up -d --build --wait` 全栈重建 smoke。

任务派发约束（整段复制进任务说明）：

> 执行时机：小段改动收尾跑一次 L1（免 Docker，不每改两行就跑）；大批量调整完成或合并前统一跑一次 L2；L3 仅在动到对应链路或发布前显式触发。不跨层：L1 不跑 `mvn verify`、全量截图与压测；合并前不跳过 L2。单测只测基础可用性与复杂不变量，禁止为凑覆盖率补逐字段赋值、空查询或简单透传测试；覆盖率门禁对象仅 `SlowSqlInterceptor`（后端 LINE 95%）与前端两个文件（行/分支/函数 95%），不自行扩大门禁对象。

### 规矩四：首屏冷启动与跨海弱网交付预算（#21 / #23 踩坑沉淀）

- **桶模块隔离（首屏零组件库/Axios 静态导入）**：Rollup 按「文件（Module）」粒度分包——首屏只要从单文件桶 `@element-plus/icons-vue`（`dist/index.mjs`）或 `element-plus` 导入 1 个符号，就会把全站共用图标（44 KB）、`lodash-es`（84 KB）、`@popperjs/core`、`@ctrl/tinycolor`、`@vueuse` 吸进入口 chunk。首屏直达路径 16 个文件（`main.js`、`App.vue`、`router/index.js`、`api/http.js`、`ClientLayout.vue`、`Home.vue`、`client/Login.vue`、`admin/Login.vue`、`LoginForm.vue`、`LoginScene.vue`、`DoctorCard.vue`、`LoadFailure.vue` 等，已由 `eslint.config.js` `no-restricted-imports` 锁死）严禁导入 `element-plus`、`@element-plus/icons-vue` 与 `axios`；首屏图标用 `EntryIcon.vue`、提示用 `toast.js`、请求用原生 `fetch` + `AbortController`（`try/finally` 清超时定时器）。
- **CSS `@layer` 锁定与默认中文替换**：按需拆包后异步路由 CSS 晚于 `main.css` 注入 `<head>`，由 `vite.config.js` 的 `elementPlusLayer()` 将 `theme-chalk/*.css` 与首屏 `entry-el.css` 包入 `@layer element-plus`，保证未分层的 `main.css` / `admin.css` / `scoped` 恒定优先；构建与 `optimizeDeps` 期将 `element-plus/es/locale/lang/en.mjs` 直接替换为 `zh-cn.mjs`（非首屏 `ElMessageBox` 统一走 `src/utils/messageBox.js` 兜底中文按钮），免除 `App.vue` 挂载 `<el-config-provider>`。
- **原生控件 DOM 契约对齐**：首屏原生标签保持 Element Plus 同名 `.el-*` 类名与层级，`unplugin-vue-components` 仅扫描 `<el-*>` 组件标签故不触发组件打包，同时零像素偏差复用既有 `:deep(.el-*)` 样式与 32 张截图尺寸基线（如 `/admin/login` `440×572 @2x` 整页高度严格 `1510px`）。
- **消除串行瀑布与 `/demo` 两阶段带宽隔离预热**：`index.html`（≤14 KB gz）内置非交互加载壳并在 `<head>` 并发预拉 `/api/client/catalog/home`；`ClientLayout`、`Home`、`client/Login`、`admin/Login` 静态并入首包（入口 JS+CSS 门禁 `ENTRY_GZIP_MAX ≤ 85 KB` gz、实测 `75.5 KB` gz，单图 `≤ 50 KB`）。`/demo` 第二屏截图必须用 `data-src` 延后（防 Chromium 弱网下将 `loading="lazy"` 阈值扩至 `2500px` 抢跑 45 KB 配图），预热队列第一阶段仅由阻塞型 `.js/.css`（显式 `mode: 'cors', credentials: 'same-origin'` 对齐 `<script type="module" crossorigin>` 缓存键）独占带宽落盘，第二阶段再加载图片。
- **传输层与本地弱网仿真**：`postbuild-assets.mjs` 构建期生成 `.gz` + 容器 `gzip_static on`；`/assets/` 与 `/uploads/<uuid>` 设 `max-age=31536000, immutable`（`/uploads/seed/**` 设 `86400`）；跨海源站宿主机开启 `tcp_congestion_control=bbr` + `default_qdisc=fq`；弱网相关改动上线前用生产构建产物 + CDP 限速（`250–300ms` RTT、`10/22/38.7 KB/s`）核验请求时间线。

## 仓库约定

- 提交信息用 Conventional Commits，主题必须含中文（`commitlint.config.cjs`）；PR 审查用中文、按严重级别（`.pr_agent.toml`）。
- 新增迁移放 `sql/migrations/YYYYMMDD_*.sql`，同步更新 `sql/01_schema.sql`；存量部署先跑迁移再上代码。
- 不改历史证据、JWT 历史密钥黑名单与 `docs/品牌沿革.md` 的旧名记录。
- 部署与排障入口：`compose.yaml`、`deploy/nginx/default.conf`、`docs/部署步骤.md`、`docs/adr/0012-frontend-cold-start-and-asset-delivery.md`。
