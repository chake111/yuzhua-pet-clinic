# AGENTS.md

本仓库是「愈爪|宠物医院预约与在线问诊平台」作品集：前端 Vue 3（`frontend/`），后端 Spring Boot 3 + JDK 17（`backend/`），MySQL 8（`sql/`），Compose 一键栈（`compose.yaml`），计划、ADR 与证据在 `docs/`。

## 三条规矩

### 规矩一：品牌与标识只有一个事实源，改名先做映射表

- 用户可见的品牌文案只从 `frontend/src/brand.js` 和后端 `BrandProperties`（`yuzhua.brand.*`）取值；页面标题、OpenAPI、包描述、README 标题都引用它，不复制字符串。
- 技术标识（包名 `com.yuzhua`、库名 `yuzhua`、配置前缀 `yuzhua.`、环境变量 `YUZHUA_*`、镜像/卷/Compose 项目名）要改时，先写「旧→新」映射表、迁移脚本和 `git grep` 审计白名单，再按层改代码。
- 验收：`git grep` 审计命中只剩 `docs/品牌沿革.md` 列出的白名单；正则与白名单见该文「审计说明」。

为什么：#12 改名一次动了 242 个文件（+3629/−943），横跨 Java 包、库名、环境变量、镜像、localStorage、Grafana 和 32 张截图。名字早定死并单点定义，这类改动才是「改一处 + 重跑派生工件」。

### 规矩二：派生工件必须一条命令再生成，禁止手改

- `docs/screenshots/` 由 `node tools/screenshots.mjs` 按 `tools/screenshots.manifest.json` 重拍；改 UI 后重跑，不手工换图。
- README 里的用例数、覆盖率、QPS、p95 必须来自 `docs/test-evidence/**` 的原始输出（`mvn verify`、`summary-*.json`），不写估算值。
- 配置性数值（门禁阈值、端口、超时、压测场景参数、种子数据量等）以代码与脚本为准，不需要证据链接；上一条只约束实测数字。
- `docs/test-evidence/**` 是历史证据，按日期归档，不改写旧文件；复跑写入新日期目录。
- 验收：README 中每个实测数字都能链到对应证据文件；截图 manifest 中的页面与断言和当前 UI 一致（可先跑 `--dry-run`）。

为什么：32 张截图在改名后整体重拍两次；证据文件从 123 涨到 152。手工维护派生文件，源改一次就全量返工一次。

### 规矩三：不变量与门禁先于功能落地

- 写写接口前先定幂等语义（如 `Idempotency-Key` + `(user_id, idem_key)` 唯一索引兜底）；写定时任务前先定多实例互斥（如 `scheduler_lease` 数据库租约）；写部署文件前先定 readiness/liveness 和指标出口。
- 每条不变量配可重复执行的验证：单测 / Testcontainers IT、`tools/*_check.mjs`、k6 脚本或 Compose smoke；门禁与代码同一次提交，不后补。
- 覆盖率门禁口径：后端 JaCoCo 仅对 `SlowSqlInterceptor` 卡 95% 行覆盖（`AppointmentService` / `DoctorScheduleService` 等厚 Service 继续生成全量报告但不卡构建，真实链路由 `*IT` 覆盖）；前端 `test:coverage` 对 `tools/style-guard/index.mjs` 与 `src/utils/tableDensity.js` 卡行/分支/函数 ≥95%。
- 单测准入原则：只测基础可用性与复杂不变量（状态机、越权隔离、边界值、写语义、并发兜底），不为了凑覆盖率写逐字段赋值、空查询或简单透传测试。

验证分三层，按对应时机执行，不每改两行就跑重型：

- **L1 轻量局部**（小段功能改完收尾跑一次，免 Docker）：
  - 后端 `mvn test -Dtest=<类>`；跨类改完跑 `mvn test`。
  - 前端 `node --test tests/<文件>.test.mjs`，或 `npm test` + `npm run lint` + `npm run style-guard`。
  - 改到单页 UI：`node tools/screenshots.mjs --only <图>` 或 `--dry-run`。
  - 改到视频脚本：`node tools/demo-video/build.mjs --dry-run` 或 `--skip-record --stage=keyframes`。
  - 改到编排：`docker compose config -q`。
  - 例外：改动落在仅由 `*IT` 覆盖的并发/幂等/支付链路时，可按需单跑 `mvn test-compile failsafe:integration-test failsafe:verify -Dit.test=<IT类>` 定位（此项需要 Docker）。
- **L2 合并前门禁**（大批量调整完成或合并代码前统一跑一次）：

  ```bash
  cd backend && mvn --batch-mode --no-transfer-progress verify   # 需 Docker
  cd frontend && npm run verify
  node --test .github/scripts/localize-dependabot.test.mjs
  docker compose config -q
  ```

  涉及多页/全局 UI 变更时另跑全量重拍 `node tools/screenshots.mjs`。
- **L3 专项重型**（仅动到对应链路或发布前显式触发）：`node tools/demo-video/build.mjs` 全量导出；`tools/load/run-load.sh` / `run-steady.sh` k6 压测；`tools/main_flow_browser.mjs` / `frontend/tests/payment-ticket.browser.mjs` / `tools/*_check.mjs` 隔离栈回归；`docker compose up -d --build --wait` 全栈重建 smoke。

任务派发约束（整段复制进任务说明）：

> 执行时机：小段改动收尾跑一次 L1（免 Docker，不每改两行就跑）；大批量调整完成或合并前统一跑一次 L2；L3 仅在动到对应链路或发布前显式触发。不跨层：L1 不跑 `mvn verify`、全量截图与压测；合并前不跳过 L2。单测只测基础可用性与复杂不变量，禁止为凑覆盖率补逐字段赋值、空查询或简单透传测试；覆盖率门禁对象仅 `SlowSqlInterceptor`（后端 LINE 95%）与前端两个文件（行/分支/函数 95%），不自行扩大门禁对象。

为什么：#9 的幂等键与任务租约是后补的（31 文件 +1345 行，含 2 个迁移），#10 的可观测性也是后补的（41 文件 +3281 行）。后补要同时改 schema、服务、测试、部署、文档和证据；先定不变量可以把返工压在一次实现里。

## 仓库约定

- 提交信息用 Conventional Commits，主题必须含中文（`commitlint.config.cjs`）；PR 审查用中文、按严重级别（`.pr_agent.toml`）。
- 新增迁移放 `sql/migrations/YYYYMMDD_*.sql`，同步更新 `sql/01_schema.sql`；存量部署先跑迁移再上代码。
- 不改历史证据、JWT 历史密钥黑名单与 `docs/品牌沿革.md` 的旧名记录。
- 部署与排障入口：`compose.yaml`、`deploy/nginx/default.conf`、`docs/部署步骤.md`。