# tools

隔离测试环境与业务回归脚本。它们不是生产入口，也不会在 CI 中执行；CI 跑 `backend` 的 `mvn verify`（单元 + 集成测试）与 `frontend` 的 lint / style-guard / test:coverage / build，另跑 Dependabot 本地化脚本单测。

## 前置条件

- Node.js 22、Python 3、MySQL 客户端
- `main_flow_browser.mjs` 另需 Playwright（可用 `PLAYWRIGHT_PACKAGE` 指向已安装的包）
- `screenshots.mjs` 另需 Playwright 与本机 Edge/Chromium；默认通道 `msedge`，可用 `PLAYWRIGHT_CHANNEL` 覆盖
- 先由 `qa_env.py prepare` 生成隔离库与 `runtime.json`；后续脚本都用 `--config` 或 `PETQA_CONFIG` 指向它

## 脚本

| 脚本 | 用途 |
| --- | --- |
| `qa_env.py` | 隔离测试环境的 prepare / check / instance / start / stop；建库、建受限账号、导入 schema 与种子数据 |
| `business_qa.py` | 隔离演示库的快照 / 恢复与查询辅助 |
| `acceptance.mjs` | 真实链路验收：登录改密与令牌失效、重连已读语义、科室行锁 |
| `business_rules_check.mjs` | 业务规则回归；运行前用 `business_qa.py snapshot` 备份 |
| `audit_regression.mjs` | 审计修复后的回归入口，覆盖宠物、会话、消息与账号状态 |
| `main_flow_check.mjs` | 主流程接口级回归，运行在专用测试库 |
| `main_flow_browser.mjs` | 主流程真实浏览器验收，输出截图与执行日志 |
| `screenshots.mjs` | 按 `screenshots.manifest.json` 重拍 `docs/screenshots/` 全部 32 张；自动登录、导航、校验宽度/DPR 与页面错误 |
| `screenshots.prepare.sql` | 固化截图演示状态：张三待支付单、刘恋已接诊病历、林若初被 2 条预约引用后停诊的排班；幂等，可重复执行 |
| `read_receipt_check.mjs` | 已读回执回归：进房/重连不写已读，仅显式 READ 归零 |
| `schedule_status_check.mjs` | 排班状态回归：状态只由停诊/恢复写入 |
| `dashboard_query_check.mjs` | 看板聚合口径与索引命中回归 |
| `trace_id_check.mjs` | 500 追踪号回归：响应体、响应头与日志可对应 |
| `gen_data_model.py` | 从 `sql/01_schema.sql` 生成 `docs/数据模型.md` |
| `prepare_business_demo.py` | 准备一份可重复的浏览器演示数据 |

### 截图脚本

`tools/screenshots.mjs` 按 `tools/screenshots.manifest.json` 重拍 `docs/screenshots/` 全部 32 张，自动登录用户端 / 后台 / 医生三种角色，导航到页面后等待稳定选择器，再按 manifest 的 `capture` 模式截图：`viewport` 截首屏、`table` 定位表格或主卡片按基线高度裁切、`full` 保留整页且高度必须与基线一致。退出前校验 PNG 宽/高/DPR、README 引用图高宽比与页面错误。基线于 2026-11-18 重定：旧图 20/21/23/28/29/30/33/41/42 因旧品牌或布局/列宽过期，按当前 UI 完整内容重定；31 因 DPR 取整差 1px。

前置：

1. 临时 MySQL 8.0 实例监听 `127.0.0.1:3307`，使用独立临时 datadir，依次导入 `sql/01_schema.sql`、`sql/02_data.sql`、`tools/screenshots.prepare.sql`；不要连接本机 3306 的业务库。
2. 后端 `backend/target/yuzhua-server.jar` 监听 `8081`，数据源指向上述临时库（`SPRING_DATASOURCE_URL=...:3307/yuzhua`），设置 `JWT_SECRET` 与 `YUZHUA_PAY_TIMEOUT_MINUTES=600`（避免待支付单在拍摄期间被清扫）。
3. 前端 `cd frontend && npm run dev -- --port 5197`，页面地址 `http://127.0.0.1:5197`。
4. Playwright 可用 `PLAYWRIGHT_PACKAGE` 指向已安装的包；浏览器默认 `msedge`，失败回退 chromium。

演示状态由 `tools/screenshots.prepare.sql` 固化：张三待支付到院单（13）、刘恋已接诊且有病历的今日门诊（31）、林若初被 2 条预约引用后停诊的排班（41/42）。文件幂等，可重复执行；其余截图依赖 `02_data.sql` 的历史订单、问诊会话与档案数据。

完整命令顺序：

```bash
# 1. 临时库（3307、独立 datadir；勿连本机 3306）
mysql -h127.0.0.1 -P3307 -uroot < sql/01_schema.sql
mysql -h127.0.0.1 -P3307 -uroot yuzhua < sql/02_data.sql
mysql -h127.0.0.1 -P3307 -uroot yuzhua < tools/screenshots.prepare.sql

# 2. 后端（工作目录 backend；JWT_SECRET 换成至少 32 字节的测试值）
cd backend
SPRING_DATASOURCE_URL='jdbc:mysql://127.0.0.1:3307/yuzhua?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true' \
SPRING_DATASOURCE_USERNAME=root SPRING_DATASOURCE_PASSWORD= \
JWT_SECRET='<至少32字节的测试密钥>' \
YUZHUA_UPLOAD_DIR="$PWD/uploads" \
YUZHUA_PAY_TIMEOUT_MINUTES=600 \
java -jar target/yuzhua-server.jar

# 3. 前端（另开终端，仓库根目录）
cd frontend && npm run dev -- --port 5197

# 4. 截图（仓库根目录）
PLAYWRIGHT_PACKAGE='<全局 playwright 包路径>' node tools/screenshots.mjs
```

```bash
# 只列出 32 项并校验 manifest 基线宽度/高度/DPR，不启动浏览器
node tools/screenshots.mjs --dry-run

# 全量重拍
node tools/screenshots.mjs

# 只重拍一张
node tools/screenshots.mjs --only 03-client-home.png

# 换前端地址
node tools/screenshots.mjs --base http://127.0.0.1:5197
```

## 压测脚本

| 脚本 | 用途 |
| --- | --- |
| `load/run-load.sh` | 100 VU race/oversell 并发正确性压测，独立 compose 项目 `yuzhuaload`；`EVIDENCE_DIR` 可覆盖 |
| `load/run-steady.sh` | 默认 30 req/s × 3 分钟稳态读混合压测，带 Prometheus/Grafana（`observability` profile），独立项目 `yuzhuaperf` |

两个脚本都会为隔离栈提供测试专用 MySQL/JWT 默认值，并处理 Windows Git Bash 的路径转换；`KEEP=1` 保留栈供检查或截图。

## 注意

`qa_env.py` 会建库、建账号并导入结构；`01_schema.sql` 自带 `DROP DATABASE yuzhua`，脚本会先改写成隔离库名并校验，避免误连本机业务库。执行前请确认 `--admin-defaults-file` 指向的是测试实例。
