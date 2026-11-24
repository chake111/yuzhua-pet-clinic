# 阶段 0.5（`stage/pr-0.5`）护栏集成与真机评测证据汇总

- **集成基准**：`origin/main`（`893cd3d`）合并 `m05/guardrails-ci`（`765d0c0`）、`m05/backend`（`f6b0305`）、`m05/frontend`（`2f21004`）
- **验证前置提交**：`f3a1386c77347e76a27310599f952c924eec01f3`
- **JDK / 运行环境**：Corretto JDK `17.0.16`、Node.js `v22.23.2`、Docker `29.9.3`

## L2 静态与自动化门禁实测结果

| 检查项 | 命令 | 结果 | 证据文件 |
| --- | --- | --- | --- |
| 注释词法与密度 `--strict` | `node tools/comment-audit-baseline.mjs --strict` | `0` 违例；后端 122 文件（nonDoc `84`, doc `105`, `>35%` `0`）；前端 82 文件（nonDoc `119`, doc `13`, `>35%` `0`）；合计 `203` 非文档 / `118` 文档块 | [comment-audit-baseline.log](comment-audit-baseline.log) |
| 跨端重复度 | `npx --yes jscpd` | 335 文件，10 处克隆，`107 / 28066` 行（`0.38% <= 3%`） | [jscpd.log](jscpd.log) |
| 门禁脚本单测 | `node --test .github/scripts/*.test.mjs` | `20/20` 通过 | [comment-gate-tests.log](comment-gate-tests.log) |
| 后端全量验证 | `cd backend && mvn --batch-mode --no-transfer-progress verify` | Checkstyle `0` 违例；Surefire `120/120` 通过（含 `CommentGuardrailsTest`）；Failsafe `8` 个 `*IT` 共 `21/21` 通过；JaCoCo `SlowSqlInterceptor` LINE `21/21`（`100% >= 95%`） | [backend-mvn-verify.log](backend-mvn-verify.log) |
| 前端全量验证 | `cd frontend && npm run verify` | `eslint`（含 `comment-guard` 与复杂度门禁）0 错；`style-guard` 通过；`test:coverage`（`tableDensity.js`、`tools/style-guard/index.mjs` >= 95%）通过；`bundle-budget`：`index.html` `1.65 KB` gz（`<= 14 KB`），入口关键路径 `75.55 KB` gz（`<= 85 KB`），单图 `29.87 KB`（`<= 50 KB`） | [frontend-verify.log](frontend-verify.log) |
| Compose 编排校验 | `docker compose config -q`（注入临时环境变量） | 退出码 `0` | — |

## 真机三模型异构 LLM 评测结果

评审器配置（`.guardrails/llm-gate-config.json`）：`R1 = deepseek-flash`（deepseek）、`R2 = qwen4-max`（dashscope）、`R3 = mimo-v2.6-flash`（xiaomimimo）。

### 1. 55 条红队 + 20 条黄金样本回归（`--eval-fixtures`）

- 原始报告：[llm-eval-fixtures.json](llm-eval-fixtures.json)、运行日志：[llm-eval-fixtures.log](llm-eval-fixtures.log)
- **结论**：`passedThresholds = true`（退出码 `0`）
  - 红队总检出率：`54/55`（`98.18% >= 95%`）
    - `A1` 刷前缀：`9/10`（L2 检出 `9`）
    - `A2` 伪装死代码/模板：`8/8`（L1 `8`，L2 `8`）
    - `A3` 非白名单位置：`8/8`（L1 `8`，L2 `6`）
    - `A4` 注释-代码漂移：`8/8`（L2 `8`）
    - `A5` Prompt Injection：`15/15`（L1 `15`，L2 `15`）
    - `A6` 零信息量高风险 Javadoc：`6/6`（L2 `6`）
  - L1 确定性规则覆盖样本：`31/31`（`100%`）
  - 黄金样本误杀率：`0/20`（`0% <= 5%`）

### 2. `stage/pr-0.5` 全仓保留注释试扫（`--scan-repo .`）

- 原始报告：[llm-repo-scan.json](llm-repo-scan.json)、运行日志：[llm-repo-scan.log](llm-repo-scan.log)
- 扫描规模：`316` 条保留注释（13 批），`0` 项 2/3 多数决阻断聚类（`blockingClusters: []`）。
- 实测发现以下待跟进项（供人工复核）：
  1. `PRECHECK_PI_PATTERNS` 误报：`SlowSqlInterceptor.java:58` 注释 `// 原因: 阈值为 0 表示关闭慢 SQL 监控，直接放行避免额外计时开销` 命中正则 `(?:直接|必须|一律)(?:放行|通过|...)`，建议后续限定「直接放行」须带评审/门禁上下文。
  2. 批大小与额度韧性：`R2`（`qwen4-max`）13/13 批全部成功；`R1`（`deepseek-flash`）前 11 批成功、第 12–13 批因 DeepSeek 测试 key 余额耗尽报 `HTTP 402 Insufficient Balance`；`R3`（`mimo-v2.6-flash`）在 40 条大批量下有 7/13 批触发 90s 超时（与 `--eval-fixtures` 5 条小批量 100% 成功形成对比），建议 `--scan-repo` 后续采用较小 `maxItems`（如 10）。
  3. 单评审 `Major` 建议（共 `22` 处，均未形成 2/3 多数决阻断，详情见 `llm-repo-scan.json`，典型代表：`AppointmentMapper.java:20`、`DoctorScheduleMapper.java:12`、`JwtUtils.java:118`、`UserContext.java:49`、`Doctors.vue:65`、`Workbench.vue:75`、`ConsultRoom.vue:24`、`OrderDetail.vue:264`）。
