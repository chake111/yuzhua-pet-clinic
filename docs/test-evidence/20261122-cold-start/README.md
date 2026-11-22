# 2026-11-22 前端冷启动优化 Phase 1 本地门禁与生产容器验收

本目录归档「愈爪|宠物医院预约与在线问诊平台」前端冷启动优化（Phase 1，设计方案见 [`docs/designs/frontend-cold-start-optimization.md`](../../designs/frontend-cold-start-optimization.md)，架构决策见 [`docs/adr/0012-frontend-cold-start-and-asset-delivery.md`](../../adr/0012-frontend-cold-start-and-asset-delivery.md)）在本机 Windows + Docker Desktop（`29.9.3`）环境下完成的 L2 合并前门禁与 L3 生产容器实测原始输出。

以下数据均为**本机生产构建容器（`http://127.0.0.1:8088`）与 L2/L3 本地验证实测结果**；公网宿主机外层 Nginx 只读核查（Phase 0）与真实中国大陆手机 5G 采样尚未执行，见文末「待线上执行项」。

## 证据文件索引

| 文件 | 内容 |
| --- | --- |
| [mvn-verify.log](mvn-verify.log) | 后端 `mvn --batch-mode --no-transfer-progress verify` 全量单测、Testcontainers 集成测试与 JaCoCo 门禁输出 |
| [npm-verify.log](npm-verify.log) | 前端 `npm run verify`（`lint`、`style-guard`、`test:coverage`、`build` + `postbuild-assets.mjs`、`bundle-budget`）输出 |
| [curl-headers.txt](curl-headers.txt) | 生产容器 `yuzhua-web` + `backend` 8 类静态与上传路径的 HTTP 状态、压缩与 `Cache-Control` 响应头实测 |
| [screenshots.log](screenshots.log) | 全新初始化库导入 `tools/screenshots.prepare.sql` 后，针对生产容器执行 `tools/screenshots.mjs` 的 32/32 截图核验日志 |
| [main-flow.log](main-flow.log) | 针对生产容器执行端到端主流程（预约/支付/病历/评价/WebSocket 问诊）与按需样式/弹窗/抽屉 DOM 断言日志 |
| [playwright-cdp-checks.json](playwright-cdp-checks.json) | `/demo` 空窗预热、`saveData` 跳过、`?demo=*` 预填隔离、`vite:preloadError` 自愈及 CDP 弱网限速冷启动原始记录 |

## 一、L2 合并前门禁（本机，Windows + Docker Desktop 29.9.3）

| 校验项 | 实测结果 | 原始证据 |
| --- | --- | --- |
| `cd backend && mvn --batch-mode --no-transfer-progress verify` | Surefire 单测 `116` 条通过（含新增 `WebMvcConfigCacheControlTest` `2` 条）、Failsafe 集成测试 `21` 条（8 个 IT 类）全部通过，0 失败/0 错误；JaCoCo `report` 分析 `70` 类，`check-core-coverage` 仅分析 `1` 类（`SlowSqlInterceptor` LINE ≥95%）并通过；`BUILD SUCCESS`（耗时 `01:43 min`） | [mvn-verify.log](mvn-verify.log) |
| `cd frontend && npm run verify` | `eslint` 通过；`style-guard` 通过；单测 `54/54` 通过（含新增 `demo-preset.test.mjs`、`bundle-budget.test.mjs`）；覆盖率门禁仅卡既定 2 文件：`tableDensity.js` `100.00 / 100.00 / 100.00`、`tools/style-guard/index.mjs` `100.00 / 96.51 / 100.00`（行/分支/函数，均 ≥95%）；`vite v5.4.21` 构建通过（`2460 modules`，`11.28s`），`postbuild-assets.mjs` 写入 `2` 个入口预热资产并生成 `153` 个 `.gz`（扫描 `160`，跳过 `7`）；`bundle-budget` 门禁通过 | [npm-verify.log](npm-verify.log) |
| `node --test .github/scripts/localize-dependabot.test.mjs` | `6/6` 通过（本次 L2 同步执行，无单独日志文件） | — |
| `docker compose config -q` | 退出码 `0`（本次 L2 同步执行，无单独日志文件） | — |

### 构建产物与预算对照（[npm-verify.log](npm-verify.log)）

| 指标 | 优化前基线（2026-11-22 线上直连实测） | 本次构建实测（Windows `npm run verify`） | CI 门禁上限 |
| --- | --- | --- | --- |
| `/index.html`（gzip） | 空 `<div id="app">` | `1.65 KB`（`1,688 B`，原始 `3,315 B`，`.gz` 存在） | `14.00 KB`（`14,336 B`） |
| 首屏入口关键路径 JS + CSS（gzip） | `468 KB`（JS `415 KB` + CSS `53 KB`） | `160.84 KB`（`164,700 B`：JS `146.51 KB` / `150,024 B` + CSS `14.33 KB` / `14,676 B`，不含 `echarts`） | `165.00 KB`（`168,960 B`） |
| 登录页主图（`dist/assets/` 单图） | `570 KB` PNG（`tuantuan-a-final.png`） | `29.87 KB`（`30,584 B`，`tuantuan-8wo1R5K3.webp`） | `50.00 KB`（`51,200 B`） |
| `/demo-preload.json` | 不存在 | `2` 个静态入口资产（排除 `dynamicImports`） | 存在、非空且均有 `.gz` |

## 二、L3 生产容器实测（`http://127.0.0.1:8088`，`nginx/1.28.3`）

### 1. 响应头与静态预压缩矩阵（[curl-headers.txt](curl-headers.txt)）

以 `curl -sSI -H 'Accept-Encoding: gzip'` 校验容器重建（`docker compose up -d --build --wait`）后的 8 类路径：

| 路径 | 状态 | 压缩与字节 | `Cache-Control` / 关键头 |
| --- | --- | --- | --- |
| `/` | `200` | `Content-Encoding: gzip`，`1,688 B` | `Cache-Control: no-cache`，`Vary: Accept-Encoding` |
| `/assets/index-nNbCAKX0.js` | `200` | `Content-Encoding: gzip`，`149,579 B` | `Cache-Control: public, max-age=31536000, immutable`，`Vary: Accept-Encoding` |
| `/demo/` | `200` | `Content-Encoding: gzip`，`6,494 B` | `Cache-Control: no-cache`（已替换原 `no-store`），`Vary: Accept-Encoding` |
| `/demo/agent-assets/tuantuan.webp` | `200` | `image/webp`，`18,502 B` | `Cache-Control: public, max-age=604800` |
| `/demo-preload.json` | `200` | `application/json`，`104 B` | `Cache-Control: no-cache`；返回 `["/assets/index-nNbCAKX0.js","/assets/index-54opOs1E.css"]` |
| `/.vite/manifest.json` | `404` | — | 按设计隐藏 Vite 完整 manifest |
| `/api/uploads/seed/doctor-01.jpg` | `200` | `image/jpeg`，`13,605 B` | `Cache-Control: max-age=86400, public`（不含 `immutable`） |
| `/api/uploads/2026/11/22/0ddc8d957c6b439a91924c9ced5810ac.png` | `200` | `image/png`，`68 B` | `Cache-Control: max-age=31536000, public, immutable` |

### 2. 全站 32 页截图与端到端主流程回归

- **全站截图基线**（[screenshots.log](screenshots.log)）：使用 `docker compose down -v && docker compose up -d --wait` 重建干净库并导入 `tools/screenshots.prepare.sql` 后，对 `http://127.0.0.1:8088` 执行 `node tools/screenshots.mjs`（`msedge`），结果 **`32/32` 全部通过（失败 `0`）**，32 张 PNG 全部 `Δ宽 = 0, Δ高 = 0`，0 `pageerror`。覆盖三端登录页（含 WebP 吉祥物）、宠主端首页/预约/支付/评价、管理端动态 ECharts 看板、医生端工作台与问诊抽屉及停诊排班/冻结弹窗。
- **主流程与样式/中文断言**（[main-flow.log](main-flow.log)）：对 `http://127.0.0.1:8088` 执行预约下单支付（单号 `640`）、接诊病历与评价及 WebSocket 图文问诊全流程（会话 `10`），结果 `ok: true`、`pageErrors: []`。同期校验 DOM 实际生效样式与组件：
  - `main.css` 覆写（`@layer element-plus` 下）：`--color-primary: #ff385c`、`--el-color-primary: #ff385c`、`.el-input__inner` `font-size: 16px`、`.el-avatar` 背景 `rgb(242, 242, 242)`；
  - `admin.css` 主按钮背景：`rgb(217, 37, 73)`；
  - `ElMessageBox`（经 `src/utils/messageBox.js`）：按钮文案 `["取消","退出登录"]` 与弹窗样式正常；
  - 医生端问诊抽屉（`<el-drawer>` 按需解析）：尺寸 `400×900`（`x=1365, y=0`）与样式正常。

### 3. `/demo` 预热、`?demo=*` 预填、`vite:preloadError` 与 CDP 限速冷启动（[playwright-cdp-checks.json](playwright-cdp-checks.json)）

采集时间：`2026-11-22T11:49:01.671Z`，目标：`http://127.0.0.1:8088`。

- **`/demo` 空窗预热命中磁盘缓存**：`demoIdlePreload.ok = true`。`/demo/` 在 `load` 后拉取 `/demo-preload.json`，点击进入 `/login?demo=user` 时 `/assets/index-nNbCAKX0.js` 与 `/assets/index-54opOs1E.css` 均命中 `fromDiskCache: true`、`encodedDataLength: 0`。
- **`saveData` 省流量跳过**：`demoSaveDataSkip.ok = true`。开启 `saveData` 时 `preloadFetched = false`（总请求 `8`）。
- **`?demo=*` 预填与安全不变量**：`demoQueryPrefill.ok = true`。`/login?demo=user` → `zhangsan`、`/admin/login?demo=doctor&redirect=/admin/my-schedule` → `doctor01`、`/admin/login?demo=admin&redirect=/admin/dashboard` → `admin`（本地镜像未传 `VITE_DEMO_PASSWORD`，密码均安全降级为空串 `""`）；跨端越界 `/login?demo=admin` 与 `/admin/login?demo=user` 均预填为空串；`autoLoginPosts = 0`、`existingAuthUntouched = true`、`manualEditVerified = true`。
- **`vite:preloadError` 30 秒单次自愈**：`vitePreloadErrorGuard.ok = true`。初始 `loads = 1`，首次 chunk 404 触发单次重载（`loads = 2`，写入 `yuzhua_preload_reload_at`），30 秒内第二次 404 未再次刷新（`loads = 2`）。
- **CDP 弱网限速冷启动基准**（`250 ms RTT + 38.7 KB/s (down/up) + 4x CPU`，禁用缓存，视口 `390×844 @2x`，各跑 3 次，全部 `pageErrors: []`）：

| 入口 | 指标 | 3 次中位数（p50） | 3 次实测区间 | 说明 |
| --- | --- | --- | --- | --- |
| `/` | FCP（白屏结束） | `1,544 ms` | `1,520–1,584 ms` | 内联非交互品牌壳 1 RTT 绘制 |
| `/` | LCP（`h1.`） | `5,336 ms` | `5,304–5,364 ms` | Vue 挂载后真实首页主标题 |
| `/` | 壳可交互 TTI / `TTI - FCP` | `5,364 ms` / `3,813 ms` | `5,333–5,397 ms` / `3,813–3,820 ms` | 搜索框与快捷入口就绪 |
| `/` | 业务就绪 TTI / `TTI - FCP` | `5,368 ms` / `3,818 ms` | `5,337–5,402 ms` / `3,817–3,824 ms` | 3/3 次均在入口 JS 下载完成前发出 `/api/client/catalog/home` 预拉请求 |
| `/` | 关键路径传输 / 页面总传输 | `166,948 B`（`163.04 KB`） / `170,332 B`（`166.34 KB`） | 3 次一致 | 含响应头与入口 HTML/JS/CSS 压缩传输字节 |
| `/login` | FCP（白屏结束） | `1,472 ms` | `1,448–1,480 ms` | 内联非交互品牌壳 |
| `/login` | 壳可交互 TTI / `TTI - FCP` | `5,890 ms` / `4,436 ms` | `5,863–5,916 ms` / `4,391–4,442 ms` | 登录表单挂载完成 |
| `/login` | LCP（`img.mascot`） | `6,836 ms` | `6,832–6,880 ms` | `29.87 KB` 团团 WebP 主图绘制完成 |
| `/login` | 业务就绪 TTI / `TTI - FCP` | `6,819 ms` / `5,368 ms` | `6,816–6,863 ms` / `5,347–5,383 ms` | 登录页首屏资源就绪 |
| `/login` | 关键路径传输 / 页面总传输 | `166,948 B`（`163.04 KB`） / `218,510 B`（`213.39 KB`） | 3 次一致 | 总传输含登录页异步 chunk 与 `tuantuan.webp` |

## 三、待线上执行项（尚未在生产环境验证）

以下事项依赖生产宿主机与公网链路，未包含在本次本地验收中：

1. **Phase 0 宿主机外层 Nginx 只读核查**：在 `47.250.169.146` 执行 `docs/部署步骤.md` 7.3 节只读命令（`nginx -V`、`nginx -T` 核对 `Accept-Encoding`/`Cache-Control`/`Vary`/`CSP`/`http2`、`sysctl` BBR/qdisc、`docker ps`），确认外层反代无阻碍项，或按结果另行立项 Phase 2 对齐。
2. **生产发布与 `DEMO_PASSWORD` 配置**：按需在 GitHub 仓库级 Variables 配置 `DEMO_PASSWORD`，合并至 `main` 触发 `.github/workflows/deploy.yml` 滚动部署，并确认线上冒烟对 `/`、`/demo-preload.json` 与入口 `/assets/index-*.js` 的压缩与缓存头校验通过。
3. **公网真实手机 5G 采样**：部署上线后在中国大陆真实手机 5G 环境下完成白天与晚高峰多样本实测（`/`、`/login` 空缓存冷启动及 `/demo` 导流进入 p50/p90），结果按新日期另建证据目录归档，不改写本目录文件。
4. **更新 `/demo` 慢加载提示文案**：仅在公网真机 5G 实测确认冷启动达标后，再拆分更新 `deploy/demo/index.html` 第 375 行 `#slow-note` 文案，去掉「首次加载较慢」，保留医生/管理端桌面浏览建议。

## 备注

- `npm-verify.log`（Windows 本机构建）中入口 JS 为 `index-BLabDGkg.js`（`150,024 B` gz），而 `curl-headers.txt` 与 `playwright-cdp-checks.json`（Linux `node:22-alpine` 容器镜像构建）中入口 JS 为 `index-nNbCAKX0.js`（`149,579 B` gz），两者仅因构建平台换行/模块路径差异产生 `445 B` 哈希与字节差，入口 CSS 均为 `index-54opOs1E.css`。
- `tools/main_flow_browser.mjs` 固定指向本机隔离端口（`5197`/`3307`/`8082`），本次 `main-flow.log` 针对生产容器 `http://127.0.0.1:8088` 执行了等价的预约/支付/病历/评价/WebSocket 问诊全流程并附加样式覆写、中文 `ElMessageBox` 与 `<el-drawer>` 断言。
- `mvn-verify.log` 中 JaCoCo 对 `net/sf/jsqlparser` 的 `IllegalClassFormatException` 为第三方类插桩已知告警，不影响构建与覆盖率门禁判定。
- 本次未改动任何历史 `docs/test-evidence/**` 目录（如 `20261122-governance/`）。
