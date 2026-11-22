# 2026-11-22 测试治理 L2 验收

门禁收窄与测试精简（AGENTS.md 规矩三 L1/L2/L3 分层）落盘后的合并前门禁复跑。

## 改动范围

- `backend/pom.xml`：JaCoCo `check-core-coverage` 仅卡 `SlowSqlInterceptor`（LINE ≥95%），厚 Service 继续生成全量报告但不卡构建。
- `frontend/package.json`：新增 `verify`（lint → style-guard → test:coverage → build）。
- `.github/workflows/ci.yml`：frontend job 补 `npm run style-guard` 步骤。
- 后端测试精简：`AppointmentServiceCoverageTest` → `AppointmentServiceTest`（31 用例，保留越权隔离/状态机/前置拒绝/唯一键透传，含 `markMissedOne` 两例）；`DoctorScheduleServiceCoverageTest` + `SchedulePeriodCheckTest` → `DoctorScheduleServiceTest`（26 用例）；`FormMappingTest` → `WriteSemanticsTest`（9 用例）；`AppPropertiesValidationTest` 保留。
- 前端测试精简：`brand.test.mjs` 删逐字常量断言；`business-rules.test.mjs` 删模板 v-if/:required 正则、0 个月文案、与 consult-send/schedule-period 重复片段。
- 文档同步：AGENTS.md 规矩三、README.md（持续集成/单元测试/覆盖率门禁）、tools/README.md。

## 复跑结果（本机，Windows + Docker Desktop 29.9.3）

| 命令 | 结果 | 输出 |
| --- | --- | --- |
| `cd backend && mvn --batch-mode --no-transfer-progress verify` | 114 单测 + 21 集成测试（8 个 IT 类）全部通过，`All coverage checks have been met`，BUILD SUCCESS | [mvn-verify.txt](mvn-verify.txt) |
| `cd frontend && npm run verify` | lint / style-guard 通过；41 测试通过；覆盖率 `tableDensity.js` 100/100/100、`style-guard/index.mjs` 100/96.51/100（行/分支/函数，均 ≥95%）；build 成功 | [npm-verify.txt](npm-verify.txt) |
| `node --test .github/scripts/localize-dependabot.test.mjs` | 6 通过 | [localize-dependabot.txt](localize-dependabot.txt) |
| `docker compose config -q` | 退出码 0 | [compose-config.txt](compose-config.txt) |

## 备注

- 单测总数由 154 降为 114（精简 40 个机械/重复/IT 已覆盖用例）；集成测试 21 个不变。
- `mvn verify` 日志中 JaCoCo 对 `net/sf/jsqlparser` 的 `IllegalClassFormatException` 是第三方类插桩告警，不影响构建与门禁判定。
- 前端 `node_modules` 原本缺少 `@babel/parser`（lockfile 中存在），复跑前执行 `npm ci` 修复，与代码改动无关。
- 本次未动 UI 与编排，按规矩三 L2 口径不重拍截图；`docs/screenshots/` 与 manifest 不受影响。