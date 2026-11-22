# 零预算解决 yuzhua-petclinic.top 手机 5G 首次冷启动白屏设计方案

*   **Author:** chake111
*   **Status:** Proposed
*   **Date:** 2026-11-22

--------------------------------------------------------------------------------

## Overview

本设计方案面向「愈爪|宠物医院预约与在线问诊平台」线上站点 `https://yuzhua-petclinic.top`，目标是在**零新增基础设施或云服务预算**的前提下，消除中国大陆手机 5G 网络首次访问时持续约 20 秒纯白屏的可用性阻塞，并建立长期防回退的构建与部署门禁。

项目当前为单前端工程双入口（宠主端 `/` 与医院管理后台 `/admin`），通过海外源站（`47.250.169.146`）宿主机外层 HTTPS 反代 nginx 转发至容器内 `yuzhua-web`（`nginx:1.28-alpine`）与 Spring Boot 后端。本方案通过**削减首屏关键路径载荷**、**消除跨海多阶段串行加载瀑布**、**利用对外宣传页 `/demo` 阅读空窗静默预热并打通 `?demo=*` 入口**、**补齐容器层静态预压缩与分层缓存头**，将首屏体验收敛至海外弱网物理带宽允许的合理区间，并由 CI 门禁与文档沉淀长期固化。

## The Problem

### 1. 两条真实首次访问入口的体验断层

对照仓库源码与线上实测（2026-11-22 绕过本地代理直连 `https://yuzhua-petclinic.top`），外部访客存在两条首次访问路径，均存在明显体验断层：

1.  **裸域直达入口 `/`（宠主端免登录首页）与直达登录页 `/login`**：
    *   手机 5G 空缓存访问时，页面在下载与解析首包期间长时间停留在纯白屏，用户无法判断站点是否故障，往往在看到任何内容前直接关闭页面。
2.  **对外宣传主入口 `/demo`（301 跳转 `/demo/`）**：
    *   `/demo` 自身为约 18 KB 静态 HTML 与 18.5 KB WebP 吉祥物图，首屏很快；但页面内所有进入链接均指向 SPA 登录页（`/login?demo=user`、`/admin/login?demo=doctor&redirect=/admin/my-schedule`、`/admin/login?demo=admin&redirect=/admin/dashboard`）。
    *   访客在 `/demo` 停留阅读 5–15 秒期间，浏览器未对 SPA 入口静态资产做任何预热；点击进入后重新遭遇完整冷启动等待，且 `?demo=*` 参数尚未被登录页消费，访客仍需返回或提前复制 `Yuzhua@2026` 手动输入。

### 2. 根因拆解：物理带宽上限 × 应用重载荷与串行瀑布

约 20 秒白屏不是单一瓶颈，而是海外链路物理约束与应用交付链路冗余叠加造成：

*   **网络与传输基线（2026-11-22 直连实测）**：源站 `47.250.169.146` 已开启 TLSv1.3、HTTP/2（ALPN `h2`）和宿主机外层 `gzip`（未开启 Brotli `br`），但直连单次测得 TCP RTT 约 `250 ms`、单流下载吞吐约 `38.7 KB/s`（约 `0.3 Mbps`）。在此速率下，首屏关键路径每多传输 40 KB，白屏就会延长约 1 秒。
*   **入口载荷过重（源码核查 + 线上实测）**：
    *   `frontend/src/main.js` 全量引入 `ElementPlus`、`element-plus/dist/index.css`、约 290+ 全量 `@element-plus/icons-vue`；`frontend/src/App.vue` 在根组件同步挂载 `ImageViewerHost.vue`；`frontend/src/router/index.js` 在入口静态依赖管理端 store。线上实测入口 `index-*.js` 原始 `1.28 MB` / gzip `415 KB`（单文件下载 **`10.73 s`**），`index-*.css` 原始 `378 KB` / gzip `53 KB`（下载 **`1.95 s`**）。
    *   `/login` 与 `/admin/login` 额外加载 `570 KB` 不可压缩 PNG（`frontend/src/assets/tuantuan-a-final.png`，`1774×887`），在 `38.7 KB/s` 吞吐下与首包争抢带宽。
*   **空 HTML 与 5 阶串行加载瀑布（源码核查）**：
    *   `frontend/index.html` 仅含空 `<div id="app"></div>`，HTML 到达后无任何可见品牌反馈。
    *   `router/index.js` 将 `ClientLayout.vue`、`Home.vue`、`Login.vue` 全部设为动态懒加载；`Home.vue` 在组件挂载后才调用 `/api/client/catalog/home`，且整页套有 `v-loading="loading"` 遮罩。用户在下载并执行完 468 KB 入口包后，还要再串行等待「拉布局 chunk → 拉页面 chunk → 发首页数据请求 → 解除全页遮罩」多趟跨海 RTT。
*   **压缩、缓存与发版稳定性短板（源码核查）**：
    *   容器内 `deploy/nginx/default.conf` 未配置 `gzip_static` 或容器级 `gzip`，静态资产压缩完全依赖不在仓库内的宿主机外层 nginx 动态压缩。
    *   `/assets/` 仅设 `expires 7d`（无 `immutable`）；`/demo/` 整段设 `Cache-Control: no-store`（使 `bfcache` 失效且连带静态图无法走 304/缓存）；后端 `WebMvcConfig` 对 `/uploads/**` 未配缓存头。
    *   滚动发版后若旧标签页触发新哈希懒加载 chunk 404，前端缺少单次刷新自愈机制。

### 3. 统一术语与度量口径

为避免后续验收与监控口径漂移，本方案统一采用以下定义：

| 术语 / 指标 | 定义 |
| --- | --- |
| **冷启动（Cold Load）** | 浏览器空缓存、无 Service Worker，按固定目标 URL（`/` 与 `/login` 分别记录）在两个口径下实测：CDP 锁定基线（`250 ms RTT + 38.7 KB/s + 4× CPU`）与真实手机 5G 白天/晚高峰多样本（报 p50/p90）。 |
| **白屏结束（FCP）** | 浏览器首次绘制可见像素的时刻（含 `index.html` 内联非交互品牌壳）；仅代表用户不再面对白屏，不代表业务页面可用。 |
| **真实主内容就绪（LCP）** | 排除加载壳后的真实业务主元素绘制时刻（`/login` 计入团团 WebP 主图；`/` 分别记录首屏静态结构与接口数据返回后的渲染节点）。 |
| **可交互（TTI）与空壳死窗** | 拆分为「壳可操作（Vue/Router 挂载完成、搜索框等控件响应输入）」与「业务就绪（首页科室/医生/公告呈现真实数据）」；同时监控 **`TTI - FCP`** 差值，防止短 FCP 掩盖长时间无法交互。 |
| **关键路径包体（Transfer Size）** | 解析构建产物 `dist/index.html` 得到的首屏入口 `<script>` + `<link rel="stylesheet">` + `modulepreload` 的 **gzip 后传输总字节**；图片资产单独核算并设上限。 |
| **双层 Nginx** | **宿主机外层 nginx**（位于 `47.250.169.146` 宿主机，终止 TLS 1.3/HTTP2 并反代至 `127.0.0.1:8088`，当前配置不在仓库）与**容器内 `yuzhua-web` nginx**（`deploy/nginx/default.conf`，由 Git 与 CI/CD 管控）。 |

## Goals

按「缓存状态 × 访问入口」2×2 场景与工程防退化拆分为四组可验证目标；受海外源站物理带宽约束，不承诺无条件全时段 5 秒内完成交互，而是按可控网络基线与真实链路分别校验：

### 1. P0：冷缓存直达 `/` 与 `/login` 首屏减重与去瀑布

*   **1 RTT 结束白屏**：`dist/index.html`（含内联非交互品牌加载壳）gzip 后 **≤ 14 KB**（落入单次 TCP 初始拥塞窗口 IW10），在 `250 ms RTT` 基线下 FCP 落在约 `1–2 s`。
*   **关键路径传输字节达标**：入口关键路径 JS + CSS gzip 总传输字节由实测 `468 KB` 压降至预算上限内（预期 `110–160 KB` gzip；CI 首版硬门禁上限锁定为 `165 KB` / `168,960 B`）。
*   **登录页主图减重**：`frontend/src/assets/` 内登录页团团吉祥物图由 `570 KB` PNG 替换为精确 `2:1` 比例（`960×480` 或 `1020×510`，满足 3x 手机屏清晰度）的 WebP，文件大小 **≤ 40 KB**。
*   **消除 `/` 串行瀑布与整页死窗**：直达 `/` 在 HTML 阶段并发拉取 `/api/client/catalog/home`，合并首屏核心外壳与首页代码消除路由二次跨海 RTT，并移除 `Home.vue` 整页 `v-loading` 遮罩使搜索框与快捷入口在挂载后立即可操作。
*   **形成可复现前后对照证据**：在 CDP 锁定基线与真机 5G 白天/晚高峰多样本下完成实测对照，按 `AGENTS.md` 规矩二归档至 `docs/test-evidence/<日期>-cold-start/`（不修改任何历史证据文件）。

### 2. P1：冷缓存经 `/demo` 导流进入 SPA 的体验闭环

*   **空窗温和预热且覆盖全移动端**：构建期自动生成最小入口资产清单（不手写哈希文件名）；`/demo/index.html` 仅在 `window.load` 完成后的空闲时段且 `!navigator.connection?.saveData` 时低优先级预取同源入口资产，兼容 iOS Safari 与微信内置浏览器，不得拖慢 `/demo` 自身首屏绘制，也不预热后台图表或触发写请求。
*   **打通 `?demo=user|doctor|admin` 进入路径**：消费 `/demo` 指向的三个查询参数，消除演示访客手动复制粘贴密码的摩擦，并确保公网演示口令与本地开发种子口令（`123456`）通过配置隔离。
*   **拆分更新过时文案**：待实测验证首屏加载达标后，更新 `deploy/demo/index.html:375` 的提示文案，将「首次加载慢」与「医生/管理端建议桌面浏览」拆开准确表达。

### 3. P1：热缓存复访与滚动发版稳定性

*   **容器层压缩自治与分层缓存**：`yuzhua-web` 镜像在构建期预生成 `.gz` 文件并在 `deploy/nginx/default.conf` 启用 `gzip_static`（兼容 HTTP/1.0 回源）；`/assets/` 升级为 `public, max-age=31536000, immutable`；`/demo/` 区分 HTML `no-cache`（恢复 `bfcache` 与 ETag 304）与 `/demo/agent-assets/` 静态缓存；后端 `WebMvcConfig` 区分 `/uploads/seed/**` 与 UUID `/uploads/**` 分层输出 `Cache-Control`。
*   **滚动发版防白屏自愈**：前端入口捕获 `vite:preloadError` 并执行受保护的单次刷新自愈，避免用户停留在旧页期间发版导致懒加载路由 404 死锁。

### 4. P0：工程门禁与架构文档沉淀

*   **构建、代码与上线三重门禁**：
    *   新增纯 Node 首屏包体与单图体积预算检查脚本并接入 `npm run verify` 与 CI；
    *   在 `frontend/eslint.config.js` 禁止全量导入 `element-plus` 与 `@element-plus/icons-vue`；
    *   在 `.github/workflows/deploy.yml` 冒烟阶段补充对 `/` 返回 `200`、`Content-Encoding` 与 `Cache-Control` 响应头的校验；
    *   合并前针对生产构建容器通过 `tools/screenshots.mjs`（保持 UI 基线一致）与 `tools/main_flow_browser.mjs` 回归。
*   **文档与运维留档**：新增 1 篇 ADR（`docs/adr/0012-frontend-cold-start-and-asset-delivery.md`），在 `docs/部署步骤.md` 增补宿主机外层 nginx 核查与对齐章节（`7.3` 节），留存 Phase 0 宿主机只读核查记录。

## Non-Goals

为守住零预算约束与单工程可维护性，以下事项明确不在本方案范围内：

*   **不增加任何基础设施或云服务费用**：不采购付费 CDN、不升级带宽或专线、不迁移至付费国内节点。
*   **不用原生 HTML 重写 Element Plus 业务控件**：保留全站 Element Plus 组件一致性、无障碍行为以及 `style-guard`、`screenshots.mjs`、`main_flow_browser.mjs` 的既有基线，不引入双轨 UI 维护债。
*   **不在容器或宿主机安装 Brotli 模块**：容器继续使用原生 `nginx:1.28-alpine` 的 `gzip_static`，不引入 `nginx-mod-http-brotli` / `ngx_brotli`。
*   **不引入 Service Worker / PWA 离线缓存**：对首次冷启动无正向收益，反而在发版切换与 iOS 微信环境下引入额外缓存故障面。
*   **不默认接入 Cloudflare Free 等免费海外 CDN 或公共 JS CDN**：国内移动 5G 易绕行美西造成晚高峰劣化，且 100 秒空闲断连与 `consultSocket.js` 重连上限冲突、存在 JWT query 经过第三方边缘的风险；仅作独立条件立项的 Phase 3 备选。
*   **不将管理端 `/admin/*` 纳入手机 5G 冷启动达标页**：本方案仅保证管理端依赖（含 ECharts）不污染宠主端首包，并顺带将 `Dashboard.vue` 图表改为按需动态加载。
*   **不重压或改名后端 26 张种子图（`backend/uploads/seed/*.jpg`）**，**不修改后端 SQL/JVM 业务逻辑**，**不更改线上 `compose.yaml` 卷挂载结构**，**不扩大 `AGENTS.md` 规矩三锁定的 95% 覆盖率门禁对象**（仍只卡后端 `SlowSqlInterceptor` 与前端 `tools/style-guard/index.mjs`、`src/utils/tableDensity.js`）。

## Open Questions

*   **OQ-1（Phase 0 宿主机只读核查项，非仓库代码决定）**：线上 `47.250.169.146` 宿主机外层 HTTPS 反代 nginx 配置与内核 TCP 参数不在本仓库内，需在正式发布前执行只读核查确认：
    1.  外层 nginx 是否清空转发给上游容器的 `Accept-Encoding`（若清空会导致容器 `gzip_static` 无法命中，退化回外层动态压缩）；
    2.  外层 nginx 是否强制覆写容器返回的 `Cache-Control` 或 `Vary` 头；
    3.  外层 nginx 是否下发了阻断 `index.html` 内联 `<style>` / `<script>` 的 `Content-Security-Policy`；
    4.  当前内核拥塞控制算法与队列调度器（`tcp_congestion_control` / `default_qdisc`）状态。
    该核查结果决定是否需要独立执行 Phase 2 宿主机外层配置对齐。

## Critical User Journeys (CUJs)

### CUJ 1：手机 5G 首次直达裸域 `/`（冷缓存）

*   **Given**：用户使用中国大陆手机 5G（或 CDP 模拟 `250 ms RTT + 38.7 KB/s + 4× CPU`），浏览器处于空缓存、未登录状态，无 Service Worker。
*   **When**：用户在浏览器地址栏输入并访问 `https://yuzhua-petclinic.top/`。
*   **Then**：
    1.  浏览器在首个 HTML 响应（gzip ≤ 14 KB）到达后立即渲染非交互品牌加载壳（不含可点击假控件、`<h1>` 或默认手机底栏），白屏在 1–2 秒内结束（FCP）。
    2.  HTML `<head>` 在不等待 JS 下载的情况下并发发起 `GET /api/client/catalog/home`。
    3.  瘦身后的入口 JS + CSS（含 `ClientLayout` 与 `Home` 首屏核心）下载并挂载完成，自动替换加载壳；搜索框与快捷入口立即可交互，不再被整页 `v-loading` 遮罩阻断。
    4.  `Home.vue` 直接复用预拉取的首页数据渲染科室、推荐医生与公告；若预拉请求失败或超时，错误被静默转交组件自身并展示既有 `LoadFailure` 重试界面，不出现未捕获脚本报错。

### CUJ 2：手机 5G 首次直达登录页 `/login` 或 `/admin/login`（冷缓存）

*   **Given**：用户处于空缓存、未登录状态，使用手机 5G 访问登录页。
*   **When**：用户直接打开 `https://yuzhua-petclinic.top/login` 或 `/admin/login`。
*   **Then**：
    1.  首个 HTML 响应迅速展示非交互品牌加载壳，不出现宠主端底栏闪现污染 `/admin/login`。
    2.  登录页加载瘦身后的入口包与 `≤ 40 KB` 的 `2:1` 团团 WebP 主图（清晰适配 3x 手机屏，布局偏移与既有 `LoginScene.vue` 视觉基线一致，不出现 `570 KB` PNG 阻塞）。
    3.  登录表单、输入框 16px 字号（防止 iOS 自动放大）、密码显隐与表单校验行为与优化前完全一致，管理端登录页保留既有按钮对比度（`admin.css` 生效）。

### CUJ 3：访客从对外宣传页 `/demo` 进入三端体验（冷缓存 + 空窗预热）

*   **Given**：首次访问者在手机浏览器（含 iOS Safari / 微信内置浏览器）或桌面浏览器中打开 `https://yuzhua-petclinic.top/demo`（301 至 `/demo/`），且未开启省流量模式（`saveData !== true`）。
*   **When**：访客浏览 `/demo` 页面约 5–15 秒后，点击「进入宠主端」「医生端」或「管理端」链接。
*   **Then**：
    1.  `/demo/` 自身首屏（HTML、样式、`tuantuan.webp`）优先完成绘制，预热脚本仅在 `window.load` 后的空闲时机启动，不抢占 `/demo` 首屏带宽。
    2.  预热仅按构建生成的最小清单拉取 SPA 入口静态资产（且请求的凭证/CORS 模式与 `<script type="module">` 匹配，避免浏览器二次重复下载），不拉取后台 ECharts，也不发出任何写请求；开启 `saveData` 时完全跳过预热。
    3.  点击三端入口后复用既有浏览器缓存与连接，大幅缩短进入登录页/目标页的等待时间，并按最终拍板的 `?demo=*` 交互完成演示账号衔接。

### CUJ 4：已访问用户复访与滚动发版期间导航（热缓存 + 自愈）

*   **Given**：用户此前已访问过站点并持有浏览器缓存；随后站点通过 `deploy.yml` 滚动发布新版本前端镜像，旧静态 chunk 文件被新哈希产物替换。
*   **When**：
    1.  用户复访 `/` 或 `/demo/`；
    2.  或用户在发版前已打开的旧标签页内点击进入尚未加载的懒加载路由（如 `/doctors`、`/admin/schedule`）。
*   **Then**：
    1.  复访时 `/assets/*` 直接命中 `1y immutable` 强缓存（零网络请求）；`/index.html` 与 `/demo/index.html` 走 `no-cache` 条件验证（未变更返回 304，且浏览器前进/后退可命中 `bfcache`）；`/uploads/seed/**` 与 UUID `/uploads/**` 分别命中对应 `Cache-Control` 缓存策略。
    2.  旧标签页触发懒加载 chunk 404 时，`vite:preloadError` 监听器执行受防循环标记保护的单次整页刷新，加载新版 `index.html` 并恢复正常导航，不会卡死在白屏或无响应点击状态；若刷新后仍失败则停止重试，防止无限刷新循环。

### CUJ 5：研发提交改动与 CI / 部署防退化验证

*   **Given**：开发者修改前端页面、样式、依赖或 nginx/后端静态配置并提交 PR 或合并至 `main`。
*   **When**：本地或 CI 运行 `npm run verify`（含 `lint`、`style-guard`、`test:coverage`、`build` 与包体预算检查）、生产容器截图/主流程回归，以及 `deploy.yml` 上线冒烟。
*   **Then**：
    1.  若误重新引入全量 `element-plus` / `@element-plus/icons-vue`，`eslint` 立即阻断。
    2.  若首屏关键路径 gzip 字节、`index.html` gzip 体积或单张静态资产超过预算阈值，包体门禁立即阻断构建。
    3.  全量中文文案（含 `ElMessageBox`）、`main.css` 5 处覆写样式和桌面/移动端 UI 布局通过 `tools/screenshots.mjs`（针对生产构建容器）与 `tools/main_flow_browser.mjs` 校验。
    4.  `deploy.yml` 冒烟确认线上 `/` 返回 `200` 且关键静态资产具备预期的压缩编码与缓存头。

## Technical Plan (Architecture)

本方案在不改变单工程双入口（`/` 与 `/admin`）及纯静态容器部署形态的前提下，沿「构建与依赖瘦身 → 首屏 HTML 并发抢跑 → `/demo` 空窗预热与 `?demo=*` 衔接 → 容器预压缩与双端分层缓存 → 四重防回退门禁」五条技术链路协同改造。

### 1. 系统交付拓扑与加载时序

```text
[中国大陆手机 5G 浏览器]  (实测 250ms RTT, ~38.7 KB/s, TLS 1.3 + HTTP/2)
          |
          v
[宿主机外层 HTTPS 反代 Nginx (47.250.169.146)]   <-- Phase 0 只读核查; 仅异常时 Phase 2 对齐
          |  http://127.0.0.1:8088
          v
[容器 yuzhua-web (nginx:1.28-alpine, deploy/nginx/default.conf)]   <-- Phase 1 仓库自治
   |-- /assets/*           : gzip_static + Cache-Control: public, max-age=31536000, immutable
   |-- /index.html         : gzip_static + Cache-Control: no-cache (<= 14 KB gz, 含内联非交互壳)
   |-- /demo-preload.json  : gzip_static + Cache-Control: no-cache (构建期自动生成最小入口清单)
   |-- /demo/              : gzip_static + Cache-Control: no-cache (恢复 bfcache/304, 空窗预热)
   |-- /demo/agent-assets/ : gzip_static + Cache-Control: public, max-age=604800
   |-- /.vite/             : return 404 (不暴露完整构建 manifest)
   `-- /api/*  ----------> [容器 backend:8081 (Spring Boot 3, WebMvcConfig)]
                              |-- /api/client/catalog/home : 公开聚合接口 (与首屏 HTML 并发抢跑)
                              |-- /api/uploads/seed/**     : Cache-Control: public, max-age=86400
                              `-- /api/uploads/** (UUID)   : Cache-Control: public, max-age=31536000, immutable
```

**优化前后直达 `/` 冷启动瀑布对比：**

```text
优化前（5 阶串行瀑布，白屏持续至第 4 阶末）：
  RTT1: GET / (空 <div id="app">)
  ---> 下载 index.js (415 KB gz, ~10.7s) + index.css (53 KB gz, ~2.0s)
       ---> 路由解析后再拉 ClientLayout chunk
            ---> 再拉 Home chunk
                 ---> onMounted 发起 GET /api/client/catalog/home (期间全页 v-loading 遮罩阻断)

优化后（路径 A — 直达 /）：
  RTT1: GET / (<= 14 KB gz，到达即绘制非交互品牌壳 -> FCP 结束白屏)
  ├── 并行 1: <head> 内联脚本立即发起 GET /api/client/catalog/home (仅 pathname === '/')
  └── 并行 2: 下载瘦身后的入口核心 JS+CSS (含 ClientLayout + Home 首屏代码，目标 ~110–160 KB gz)
        ---> Vue 挂载替换加载壳，搜索框与快捷入口立即可交互 (无全页遮罩)
        ---> Home.vue 直接消费已返回/在途的首页数据 Promise 渲染科室、医生与公告

优化后（路径 B — 经 /demo 导流进入）：
  RTT1: GET /demo/ (快速呈现落地页)
  ---> window.load 后空闲且 !saveData: 读取 /demo-preload.json，低优先级 fetch 入口 JS+CSS 写入磁盘缓存
  ---> 用户点击「进入宠主端 / 医生端 / 管理端」跳转 /login?demo=* 或 /admin/login?demo=*&redirect=...
        ---> 入口资产命中磁盘缓存 + 登录页加载 <= 40 KB 团团 WebP + 自动预填账号与演示密码，点一次登录进入
```

---

### 2. 子系统一：Element Plus 按需装配、32 图标显式导入与样式层级锁定

*   **模板组件与指令按需（已拍板 OQ-2 选项 A）**：
    *   在 `frontend/package.json` 的 `devDependencies` 引入 `unplugin-vue-components`；在 `frontend/vite.config.js` 注册 `Components({ dts: false, resolvers: [ElementPlusResolver({ importStyle: 'css', directives: true })] })`。
    *   明确**不引入** `unplugin-auto-import`，避免破坏 `frontend/eslint.config.js` 的 `no-undef` 校验以及 `.github/workflows/ci.yml` 中 `lint` 先于 `build` 的执行顺序。
    *   全仓 51 个 `.vue` 文件继续保持 kebab-case 标签写法（`<el-table>`、`<el-table-column>`、`<el-dialog>` 等），满足 `frontend/tools/style-guard/index.mjs` 静态正则解析。
    *   清理 2 处特殊写法：`views/admin/ConsultRoom.vue` 移除 `import { ElDrawer } from 'element-plus'`，将模板中的 `<ElDrawer>` 统一改为 `<el-drawer>` 交由 resolver 自动按需打包；确认 `views/admin/Workbench.vue` 的 `<el-collapse-transition>` 由 resolver 正确解析并携带 `collapse-transition/style/css.mjs`。
    *   在 `Home.vue` 移除整页 `v-loading` 后，`/` 与 `/login` 均不含 `v-loading`；其余 35 处使用 `v-loading` 的非首屏页面由 `ElementPlusResolver({ directives: true })` 自动在各自 chunk 内注入 `ElLoadingDirective` 与 `loading/style/css.mjs`，首包不再携带 `ElLoading`。
*   **入口清理、中文环境与命令式 API 样式收口**：
    *   `frontend/src/main.js` 删除 `import * as Icons`、`import ElementPlus`、`import 'element-plus/dist/index.css'` 与全量循环注册；保留 `./styles/main.css` 与 `./styles/admin.css`（78 行 ~1.5 KB gzip，覆盖不在 `AdminLayout` 内的 `/admin/login` 按钮对比度）。
    *   `frontend/src/App.vue` 根模板改用 `<el-config-provider :locale="zhCn">` 包裹（`zhCn` 从 `element-plus/es/locale/lang/zh-cn` 导入），保证全站声明式组件中文本地化。
    *   `ElMessage` 被首屏入口依赖的 `src/api/http.js` 等 29 处调用：在 `main.js` 显式导入 `element-plus/es/components/message/style/css`（源 gzip 约 `0.8 KB`，共用已入首包的 `base.css`），确保任意报错提示样式完备。
    *   `ElMessageBox` 仅在非首屏 15 处使用：新增薄封装模块 `frontend/src/utils/messageBox.js`，内部统一导入 `ElMessageBox` 与 `element-plus/es/components/message-box/style/css`，对外提供同名的 `confirm` / `prompt` / `alert` 代理并在未传按钮文案时注入中文默认值 `{ confirmButtonText: '确定', cancelButtonText: '取消' }`；15 个调用文件改为从 `@/utils/messageBox` 导入，同时核实 5 处原缺省按钮文案的调用点（`AdminLayout.vue:83`、`admin/Appointments.vue:164`、`admin/Schedules.vue:193-199`、`admin/ConsultRecords.vue:107,120`）展示中文按钮，彻底避免脱离 `<el-config-provider>` 上下文回退英文 `OK/Cancel`，同时把 `ElMessageBox` JS+CSS 完全隔离出首屏包。
*   **32 个图标按需显式导入**：
    *   全仓实际仅使用 32 个 `@element-plus/icons-vue` 图标（分布在 26 个 `.vue` 文件），逐一在对应 `.vue` 的 `<script setup>` 中具名导入，删除 261 个未使用图标。
    *   3 处动态 `<component :is>`（`ClientLayout.vue:119`、`AdminLayout.vue:107`、`Mine.vue:41`）将导航配置数组中的 `icon` 字段由字符串改为直接引用具名导入的图标组件（并用 `markRaw` 避免 Vue 响应式代理开销）。
    *   8 处字符串图标属性（`Home.vue:55` `icon="Search"`；`DoctorList.vue:68`、`NoticeList.vue:59`、`admin/Doctors.vue:161`、`admin/Users.vue:77`、`admin/Pets.vue:95`、`admin/Records.vue:50`、`admin/Notices.vue:113` 的 `prefix-icon="Search"`）统一改为属性绑定 `:icon="Search"` / `:prefix-icon="Search"`。
*   **CSS `@layer` 锁定 `main.css` 5 处同特异性覆写**：
    *   在 `frontend/vite.config.js` 新增轻量 Vite 插件 `elementPlusLayer()`，其 `transform(code, id)` 识别剥离 query 后的路径匹配 `/[\\/]node_modules[\\/]element-plus[\\/]theme-chalk[\\/].*\.css$/`（跳过 `?raw`/`?url`），将文件内容包装为 `@layer element-plus {\n${code}\n}`。
    *   已全量静态核查 `element-plus/theme-chalk/` 全部 121 个 CSS 文件：均不含 `@charset`、`@import` 或 `@layer`，且 `main.css` 的 5 处关键覆写目标（`:root` 主题变量 L68-110、`.el-avatar` L141-145、`.el-input__inner/.el-textarea__inner` 16px 防 iOS 缩放 L148-151、`.el-date-table` 选中态 L154-156、`.el-image-viewer__mask` 92% 遮罩 L158-165）在 Element Plus 对应 CSS 中均无 `!important` 冲突。
    *   `main.css`、`admin.css` 与所有 `.vue` `<style scoped>` 维持在未分层层（unlayered），按 CSS Cascade Layers 规范优先级恒高于 `@layer element-plus`，无论异步路由 chunk 何时注入 Element Plus CSS，都不会反覆盖项目样式；且 `main.css` 原语法与选择器路径零改动，完全兼容 `style-guard`。
*   **非首屏重模块剥离**：
    *   `frontend/src/App.vue` 将 `ImageViewerHost` 改为 `defineAsyncComponent(() => import('@/components/ImageViewerHost.vue'))` 并通过 `v-if="imageViewer.open"` 延迟挂载；`ImageViewerHost.vue` 的 `watch([() => imageViewer.open, () => imageViewer.index], ...)` 补充 `{ immediate: true }`，确保首次异步挂载时仍为 `.el-image-viewer__img` 写入语义 `alt`。
    *   `frontend/src/router/index.js` 删除顶层静态 `import { useAdminStore } from '@/stores/admin'`，改为在 `beforeEach` 进入 `if (to.meta.admin)` 分支时动态 `const { useAdminStore } = await import('@/stores/admin')`，切断用户端首包对 88 行 `src/api/admin.js` 的静态传递依赖。
    *   `frontend/src/views/admin/Dashboard.vue` 将 `echarts/core`、图表/组件/渲染器与 `echarts.use(...)` 移入模块级单例缓存函数 `loadEcharts()`，在 `draw()` 中按需动态 `import()`，不阻塞 KPI 卡片与待办区块先渲染（并在卸载时沿用 `requestId` 守卫防止异步完成后向已销毁 DOM 初始化画布）。

---

### 3. 子系统二：首屏内联壳、`/` 并发抢跑与登录页主图优化

*   **`index.html` 非交互品牌加载壳（gzip ≤ 14 KB）**：
    *   在 `frontend/index.html` `<div id="app">` 内放置纯静态品牌加载外壳与内联 `<style>`（当 `app.mount('#app')` 执行时自动整体替换，无需额外清理代码）。
    *   品牌文案遵守 `AGENTS.md` 规矩一：扩展 `frontend/vite.config.js` 的 `brandHtml()` 占位替换，除既有 `%BRAND_TITLE%`、`%BRAND_DESCRIPTION%` 外增加 `%BRAND_NAME%` 与 `%BRAND_TAGLINE%`，全部唯一取自 `frontend/src/brand.js`。
    *   反误触与截图基线硬约束：加载壳内**禁止**出现 `<h1>` 标签（避免提前触发 `tools/screenshots.manifest.json` 的 `waitFor: "h1:has-text(...)"`）、**禁止**绘制看似可点击的假搜索框/假按钮、**禁止**绘制宠主端底部导航栏（避免访问 `/admin/*` 时闪现用户端底栏），并尊重 `@media (prefers-reduced-motion: reduce)`。
*   **合并首页核心与 `<head>` 并发拉取 `/api/client/catalog/home`**：
    *   `frontend/src/router/index.js` 将 `ClientLayout.vue` 与 `Home.vue` 改为静态导入（与入口打包到同一首屏关键路径，消灭 `/` 访问时下载完入口再发起两次跨海 RTT 拉布局与首页 chunk）；`/login`、`/doctors`、`/admin/*` 等所有其他路由维持动态 `import()`。
    *   `frontend/index.html` `<head>` 注入极小内联脚本（未压缩 < 350 B）：仅当 `window.location.pathname === '/'` 时立即执行 `fetch('/api/client/catalog/home', { credentials: 'same-origin' })`（后端 `CatalogController#home` 已核实为纯公开只读聚合接口，不依赖鉴权头），将 `{ ts: Date.now(), promise }` 挂至 `window.__YUZHUA_HOME_PREFETCH__`；`promise` 内部校验 `res.ok` 与 `body && body.code === 200` 后解析 `body.data`，任意网络/解析/业务异常均捕获并返回 `null`（杜绝 `unhandledrejection` 污染 Playwright `pageerror` 断言）。
    *   `frontend/src/views/client/Home.vue` 的 `load()` 在首次挂载且预拉记录存在且耗时未超过 30 秒时，原子取出并清空 `window.__YUZHUA_HOME_PREFETCH__`；若预拉结果非空则直接填充 `data`，若为 `null` 或用户手动重试则走原有 `homeData()` 触发 `LoadFailure` 交互闭环。
    *   移除 `Home.vue` 根节点 `<div class="pc-narrow home">` 上的 `v-loading="loading"` 全页遮罩，改为仅在 `loading && !data.departments.length` / `!data.doctors.length` / `!data.notices.length` 时于对应列表区块内显示固定占位高度的轻量静态骨架块，使搜索框与「我的预约 / 在线问诊」快捷入口在挂载后立即可用，同时避免数据到达后大面积布局跳动（CLS）。
*   **登录页团团吉祥物 WebP 替换**：
    *   离线从原始高清图 `frontend/src/assets/tuantuan-a-final.png`（`1774×887`，严格 `2:1`，`570 KB`）导出精确 `2:1` 的 **`1020×510`**（覆盖 `LoginScene.vue` 桌面 `340 CSS px × 3x DPR` 与手机 `300 CSS px × 3x DPR` 清晰度，若 1020 宽下超 38 KB 则回退 `960×480`）有损+Alpha WebP 存为 `frontend/src/assets/tuantuan.webp`（目标 ≤ `40 KB`），并删除 `frontend/src/assets/tuantuan-a-final.png`。
    *   `frontend/src/components/LoginScene.vue` 将导入路径改为 `@/assets/tuantuan.webp`，`<img>` 的 `width`/`height` 属性同步更新为实际导出像素（保持 `2:1` 纵横比，与 `.mascot { transform: translate(-50%, -73.3%); }` 对齐不变）。资产保留在 `frontend/src/assets/` 内，满足 `deploy/web/Dockerfile` 仅拷贝 `frontend/` 的构建隔离，且不在 `package.json` 引入 `sharp`。

---

### 4. 子系统三：`/demo` 空窗静默预热与 `?demo=*` 预填衔接

*   **构建期自动生成最小入口清单（遵守 `AGENTS.md` 规矩二）**：
    *   `frontend/vite.config.js` 开启 `build.manifest: true`（输出 `dist/.vite/manifest.json`）。
    *   构建后纯 Node 脚本 `frontend/tools/postbuild-assets.mjs` 读取 `dist/.vite/manifest.json`，仅沿 `index.html` 入口节点递归收集其 `file`、`css` 与静态 `imports` 链上的 `.js`/`.css` URL（严格排除 `dynamicImports` 下的路由页、管理端与 ECharts），去重输出极简清单 `dist/demo-preload.json`：

```json
{
  "version": 1,
  "assets": [
    "/assets/index-<hash>.js",
    "/assets/index-<hash>.css"
  ]
}
```

*   **全移动端兼容的 `/demo` 空窗温和预热**：
    *   在 `deploy/demo/index.html` 末尾既有 `<script>` 内追加独立预热逻辑：
        1.  仅在 `window.addEventListener('load', ...)` 后调度，使用 `requestIdleCallback(run, { timeout: 2500 })`，不支持 `requestIdleCallback` 的浏览器（如 iOS Safari）回退 `setTimeout(run, 800)`；
        2.  省流量与极弱网保护：检测到 `navigator.connection?.saveData === true` 或 `navigator.connection?.effectiveType === 'slow-2g'` 时直接退出；
        3.  `fetch('/demo-preload.json', { credentials: 'same-origin', cache: 'no-cache' })` 获取清单后，按顺序或最多 2 路并发对每个 URL 发起 `fetch(url, { method: 'GET', mode: 'cors', credentials: 'same-origin', cache: 'default' })` 并显式 `await res.arrayBuffer()` 消费响应体确保写入 HTTP 磁盘缓存；
        4.  该请求模式与 Vite 在 `index.html` 中输出的 `<script type="module" crossorigin>` 及 `<link rel="stylesheet" crossorigin>` 使用完全一致的同源 CORS 凭据缓存键，兼顾 Chrome、iOS Safari 与微信 WKWebView，且所有异常静默吞掉不影响 `/demo` 自身功能。
*   **`?demo=*` 预填与环境变量隔离（已拍板 OQ-1 选项 A）**：
    *   新增纯逻辑模块 `frontend/src/utils/demoPreset.js`（便于单测）：
        *   接口：`resolveDemoPreset(side, demoQuery, demoPassword = import.meta.env.VITE_DEMO_PASSWORD)`
        *   白名单映射：`side === 'client'` 仅匹配 `user → { username: 'zhangsan' }`；`side === 'admin'` 仅匹配 `doctor → { username: 'doctor01' }`、`admin → { username: 'admin' }`；其余非法值一律返回 `null`。
        *   密码字段：仅当 `demoPassword` 为非空字符串时同时返回 `password: demoPassword`，否则返回 `password: ''`（源码不出现 `Yuzhua@2026` 或 `123456` 字面量）。
    *   `frontend/src/components/LoginForm.vue` 新增可选 props `initialUsername`、`initialPassword`（用于初始化 `form`，并在 `watch` 监听参数变化且用户尚未手工改写时同步，允许用户随时清空改用其他账号）。
    *   `frontend/src/views/client/Login.vue` 与 `frontend/src/views/admin/Login.vue` 调用 `resolveDemoPreset(...)` 将结果传入 `LoginForm`；**不自动触发登录写请求**，**不覆写浏览器已有登录态**（已有登录态时用户自主决定点登录切换或直接返回）。
    *   构建参数透传链路：`deploy/web/Dockerfile` 在 `build` 阶段声明 `ARG VITE_DEMO_PASSWORD=""` 与 `ENV VITE_DEMO_PASSWORD=$VITE_DEMO_PASSWORD`；`.github/workflows/deploy.yml` 的 `build` job 向 `docker/build-push-action` 增加 `build-args: VITE_DEMO_PASSWORD=${{ vars.DEMO_PASSWORD }}`（采用仓库级非敏感 Variable `DEMO_PASSWORD`，与 `build` job 未绑定 `production` environment 的现有结构兼容；未配置时安全降级为仅预填账号）。
    *   文案更新时机：在 Phase 1 线上实测冷启动达标后，拆分更新 `deploy/demo/index.html:375` 的 `#slow-note` 提示，去掉「完整系统首次加载较慢」，仅在医生端/管理端保留桌面端浏览建议。

---

### 5. 子系统四：容器预压缩、双端分层缓存与发版 chunk 自愈

*   **构建期纯 Node `.gz` 预压缩**：
    *   `frontend/tools/postbuild-assets.mjs` 使用内置 `node:zlib`（`gzipSync(buf, { level: 9 })`）递归扫描目标目录，为全部 `.js`、`.css`、`.html`、`.svg`、`.json`（原始字节 ≥ 256 B 且压缩后体积严格更小）写入同名 `.gz` 文件；支持 `--gzip-only <dir>` 模式，在 `deploy/web/Dockerfile` 的 `build` 阶段复用同一脚本对暂存的 `deploy/demo/index.html` 与 `deploy/demo/agent-assets/logo.svg` 一并预压后拷贝进 `nginx:1.28-alpine`，零额外 Alpine 软件包、Windows 与 Linux 构建行为完全一致。
*   **容器 Nginx 配置升级（`deploy/nginx/default.conf`）**：
    *   `server` 块启用：
        *   `gzip_static on;`
        *   `gzip on;`、`gzip_vary on;`、`gzip_http_version 1.0;`（兜底宿主机未配 `proxy_http_version 1.1` 的 HTTP/1.0 回源）、`gzip_proxied any;`、`gzip_min_length 256;`
        *   `gzip_types text/plain text/css application/javascript application/json image/svg+xml;`
    *   新增 `location /.vite/ { return 404; }`。
    *   分层缓存规则：
        *   `location /assets/`：`add_header Cache-Control "public, max-age=31536000, immutable";` + `try_files $uri =404;`（替换原 `expires 7d`）。
        *   `location = /index.html` 与 `location = /demo-preload.json`：`add_header Cache-Control "no-cache";`。
        *   `location /demo/agent-assets/`：`add_header Cache-Control "public, max-age=604800";` + `try_files $uri =404;`。
        *   `location /demo/`：`add_header Cache-Control "no-cache";`（替换原 `no-store`，恢复 `bfcache` 与 ETag 304）+ `try_files $uri $uri/ /demo/index.html;`。
*   **后端上传图分层缓存（`backend/src/main/java/com/yuzhua/config/WebMvcConfig.java`）**：
    *   保持既有卷挂载与 URL 路径 `/api/uploads/**` 不变，在 `addResourceHandlers` 注册两条按路径特异度排序的静态资源映射：
        1.  先注册 `resourcePattern() + "/seed/**"` → `file:<location>/seed/`，配置 `CacheControl.maxAge(Duration.ofDays(1)).cachePublic()`（同名种子图更新无需等一年）；
        2.  再注册 `resourcePattern() + "/**"` → `file:<location>/`，配置 `CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable()`（`FileStorageService.java:58` 已保证用户上传文件名全部为不可变 32 位 UUID）。
*   **滚动发版 chunk 404 防循环自愈（`frontend/src/main.js`）**：
    *   在 `app.mount('#app')` 前注册 `window.addEventListener('vite:preloadError', handler)`：读取 `sessionStorage.getItem('yuzhua_preload_reload_at')`（包在 `try/catch` 中兼容隐私模式）；若无记录或距今超过 `30_000 ms`，写入当前时间戳、调用 `event.preventDefault()` 并 `window.location.reload()`；若 30 秒内已自愈过一次则放行错误，避免持续故障导致无限刷新。

---

### 6. 子系统五：四重工程门禁与分阶段发布机制

*   **ESLint 防回退规则（`frontend/eslint.config.js`）**：
    *   新增 `no-restricted-imports`：
        1.  禁止从 `element-plus` 默认导入（`import ElementPlus`）、命名空间导入、以及导入 `element-plus/dist/index.css`；
        2.  禁止从 `@element-plus/icons-vue` 命名空间导入（`import * as Icons`）；
        3.  禁止业务文件直接从 `element-plus` 导入 `ElMessageBox`（统一导向 `@/utils/messageBox`，仅豁免 `src/utils/messageBox.js`）。
*   **纯 Node 包体预算门禁（`frontend/tools/bundle-budget.mjs`）**：
    *   挂入 `frontend/package.json` 的 `verify` 链（`lint && style-guard && test:coverage && build && bundle-budget`）以及 `.github/workflows/ci.yml` 的 `frontend` job。
    *   构建完成后读取 `dist/index.html`、`dist/demo-preload.json` 与 `dist/assets/`，校验：
        1.  `dist/index.html` gzip 体积 ≤ **`14,336 B`（14 KB）**；
        2.  入口关键路径（`dist/index.html` 引用的全部 `<script type="module">`、`<link rel="stylesheet">` 与 `<link rel="modulepreload">`）gzip 总字节 ≤ **`168,960 B`（165 KB）**（Phase 1 首版实测后按需进一步收紧至「实测值 + 15%」），且关键路径 chunk 文件名与内容映射中不得包含 `echarts`；
        3.  `dist/assets/` 中单张图片（`.png`/`.jpg`/`.jpeg`/`.webp`）体积 ≤ **`51,200 B`（50 KB）**；
        4.  `dist/demo-preload.json` 存在、非空且所列路径均真实存在于 `dist/` 并具备同名 `.gz` 文件。
    *   严格遵守 `AGENTS.md` 规矩三：不扩大 `test:coverage` 的 95% 覆盖率门禁对象（仍只卡 `tools/style-guard/index.mjs` 与 `src/utils/tableDensity.js`）；仅为 `demoPreset.js` 与 `bundle-budget.mjs` 核心解析逻辑补基础单测。
*   **部署冒烟头校验（`.github/workflows/deploy.yml`）**：
    *   在 `Smoke-test the live site` 循环中新增对 `/`、`demo-preload.json` 与线上提取出的首个 `/assets/index-*.js` 的校验：确认 `HTTP 200`、`Content-Encoding: gzip`（带 `Accept-Encoding: gzip` 请求时）、`/` 含 `Cache-Control: no-cache`、入口 JS 含 `immutable`。
*   **四阶段交付边界**：
    *   **Phase 0（前置只读核查）**：在宿主机 `47.250.169.146` 执行只读命令核查外层 nginx 与内核 BBR 并留档；
    *   **Phase 1（仓库代码全量上线）**：全部前端、容器 nginx、后端 `WebMvcConfig`、CI 门禁与 ADR/文档经 `npm run verify`、`mvn verify`、生产容器 `screenshots.mjs` + `main_flow_browser.mjs` 回归后合并部署，并在 CDP 与真机 5G 完成前后实测对照归档；
    *   **Phase 2（条件触发宿主机对齐）**：仅当 Phase 0 发现外层 nginx 清空 `Accept-Encoding`、覆写 `Cache-Control` 或加了阻断内联壳的 `CSP` 时独立修改宿主机配置并补充 `deploy/host/` 参考模板与 `docs/部署步骤.md` `7.3` 节；
    *   **Phase 3（条件可选网络层）**：不纳入本设计实施范围。

## Alternatives Considered

### Alternative A：登录页或未登录首页改原生 HTML（放弃首屏 Element Plus）

*   **Pros**：首屏关键路径 JS + CSS 可进一步压降至约 `45–65 KB` gzip，在晚高峰弱网下的抗抖动能力更强。
*   **Cons**：引入「原生控件 + Element Plus」双轨 UI 维护债（焦点描边、清除按钮、密码显隐、评分星、表单校验规则需手工复刻），极易破坏 `frontend/tools/style-guard/index.mjs` 约束、`tools/screenshots.mjs` 像素尺寸基线与 `tools/main_flow_browser.mjs` 选择器锚点。
*   **Reason for Rejection**：Rejected by author: 全站保留 Element Plus 并统一按需加载，避免引入双轨控件样式与交互维护成本。

### Alternative B：容器加装 `nginx-mod-http-brotli` 或宿主机外层手动开启 Brotli

*   **Pros**：相比 `gzip` 可在文本资产上再减少约 `15%–20%` 传输字节（约 `15–25 KB`，`38.7 KB/s` 链路上约省 `0.4–0.6 s`）。
*   **Cons**：容器内加装第三方 Alpine 动态模块增加基础镜像升级风险，且仍依赖宿主机外层 nginx 透传 `Accept-Encoding: br`；在宿主机手工编译/安装 Brotli 则脱离 Git 与 CI/CD 自动部署与回滚闭环，存在系统升级 ABI 断裂隐患。
*   **Reason for Rejection**：Rejected by author: 选择容器原生 `nginx:1.28-alpine` 静态预压缩 `gzip_static`，换取零外部模块依赖与随镜像原子回滚。

### Alternative C：`/demo` 不做预下载，或仅预下载不接通 `?demo=*`

*   **Pros**：零构建清单耦合，只看视频不进入系统的访客零额外预取流量（省约 `110–160 KB`），且无需触碰登录页与构建参数。
*   **Cons**：完全浪费访客在 `/demo` 阅读 5–15 秒的链路空闲窗；且点击进入登录页后仍需手工复制粘贴演示密码，转化链路存在明显断裂。
*   **Reason for Rejection**：Rejected by author: 选择同时实施 `/demo` 空窗温和预热（受 `window.load` 与 `!saveData` 门控）与 `?demo=*` 预填衔接。

### Alternative D：仅用 Speculation Rules API 做 `/demo` 预热

*   **Pros**：仅需在 `/demo/index.html` 加约 5 行 `<script type="speculationrules">`，零构建清单耦合。
*   **Cons**：iOS Safari 与 iOS 微信内置浏览器（WKWebView）均不支持 Speculation Rules API，无法覆盖中国大陆大量移动端真实访客。
*   **Reason for Rejection**：Rejected by author (technical constraint): 改用构建期 `demo-preload.json` 清单 + 通用同源 CORS `fetch` 预热覆盖全移动端浏览器。

### Alternative E：保留 `/` 路由二次懒加载，并在挂载后再拉取首页接口

*   **Pros**：路由写法保持完全一致，直达 `/login` 或 `/admin` 时可少载约 `10–20 KB` 首页外壳代码。
*   **Cons**：直达裸域 `/` 在下载完入口包后，仍需额外串行等待「`ClientLayout` chunk → `Home` chunk → `/api/client/catalog/home`」多趟跨海 RTT（约增加 `0.8–1.5 s`），且数据到达前被整页 `v-loading` 遮罩阻断交互。
*   **Reason for Rejection**：Rejected by author: 选择静态合并 `ClientLayout + Home` 核心并在 `index.html` `<head>` 并发预拉 `/api/client/catalog/home`，同时去除整页 `v-loading` 遮罩。

### Alternative F：默认接入免费海外 CDN（Cloudflare Free）或公共 JS CDN

*   **Pros**：零费用即可获得边缘 Brotli 与 HTTP/3。
*   **Cons**：国内移动 5G 访问 Cloudflare Free 节点易绕行美西，晚高峰丢包率反而高于直连源站；边缘 100 秒空闲断连会撞上 `consultSocket.js` 4 次重连上限放大问诊断线；且 `/api/ws/consult?token=` 的 JWT 将经由第三方边缘转发；公共 JS CDN 则破坏按需 tree-shaking 与自包含容器部署。
*   **Reason for Rejection**：Rejected (deferred to conditional Phase 3): 默认不引入，仅当 Phase 1+2 实测未达标、三网 5G 晚高峰 A/B 测试显著胜出且完成 WebSocket 重连/鉴权加固后再独立评估立项。

### Alternative G：引入 Service Worker / PWA 离线缓存

*   **Pros**：热复访接近离线瞬开。
*   **Cons**：对首次冷启动白屏毫无帮助，首访注册与预缓存反而可能争抢首屏带宽；滚动发版期间极易因缓存旧清单触发 chunk 404 白屏；iOS 微信内置浏览器对 Service Worker 支持不稳定。
*   **Reason for Rejection**：Rejected: 与「解决首次访问白屏」核心目标冲突，且显著扩大发版故障面。

### Alternative H：引入 `unplugin-auto-import` 消除脚本侧显式 `import`

*   **Pros**：可省去业务代码中的 `import { ElMessage } from 'element-plus'` 等样板行。
*   **Cons**：直接与 `frontend/eslint.config.js` 的 `no-undef` 校验及 `.github/workflows/ci.yml` 中 `npm run lint` 先于 `npm run build` 的执行顺序冲突（CI 未先跑构建不会生成自动导入声明）；且无法为全仓已有 45 处显式 `ElMessage`/`ElMessageBox`/`ElDrawer` 自动补齐 CSS 导入。
*   **Reason for Rejection**：Rejected: 仅采用 `unplugin-vue-components` 处理模板组件与指令，脚本侧维持显式导入并配合 `src/utils/messageBox.js` 集中绑定样式。

### Alternative I：将 `admin.css` 拆分出全局入口包

*   **Pros**：理论上可为用户端首屏减少少量 CSS 字节。
*   **Cons**：`frontend/src/styles/admin.css` 全文仅 78 行（约 `1.5 KB` gzip），却同时负责不在 `AdminLayout` 内的 `/admin/login` 按钮对比度与禁用态样式，拆出后需额外维护登录页挂载顺序，风险远大于收益。
*   **Reason for Rejection**：Rejected: 保持 `admin.css` 留在入口不拆。

### Alternative J：改造成 Vite MPA 多 HTML 或引入 SSR / SSG

*   **Pros**：可物理拆分用户端与后台两份 HTML，或由服务端直接输出完整 HTML 内容。
*   **Cons**：牵连本地 Vite dev 中间件、nginx 多路径回退、跨端路由切换与全套浏览器截图/回归脚本；SSR 还需引入 Node 常驻服务进程，彻底改变当前纯静态容器交付模型。
*   **Reason for Rejection**：Rejected: 单 HTML 按需拆包 + ≤ 14 KB 非交互内联壳的改动面与运维复杂度远低。

### Alternative K：重压后端 26 张种子图或在 `package.json` 引入 `sharp`

*   **Pros**：可微幅减小医生/宠物种子头像体积，或自动化执行图片转码。
*   **Cons**：实查 `backend/uploads/seed/` 26 张图单张仅 `10–27 KB`（总计约 `380 KB`），且文件名与 `.jpg` 扩展名硬编码在 `sql/02_data.sql` 并受线上持久化卷不自动覆写旧文件约束；在 `package.json` 引入 `sharp` 在 Alpine musl 容器构建中容易出现原生二进制拉取失败。
*   **Reason for Rejection**：Rejected: 后端种子图仅通过 `WebMvcConfig` 补齐 24h 缓存头；登录页团团 WebP 离线一次性生成并提交入库。

### Alternative L：`?demo=*` 由 `/demo` 原地调登录接口写 `localStorage` 直跳，或经 `sessionStorage` 标记自动提交

*   **Pros**：访客从 `/demo` 点击入口后可少点一次登录按钮，直接落入目标业务页。
*   **Cons**：原地登录会让纯静态页 `/demo/index.html` 与 SPA 内部 4 个 `localStorage` 键名及 `LoginVo` 响应结构产生隐式跨层耦合，且易静默顶掉同浏览器已登录的同侧账号；经 `sessionStorage` 标记自动提交则引入双状态分支与跨标签页退化，增加测试矩阵且口令漂移时会直接触发错误弹窗。
*   **Reason for Rejection**：Rejected by author: 选择 OQ-1 选项 A（登录页消费 `?demo=*` 预填表单，用户确认后手动点一次登录），保持零跨层存储耦合、不覆写已有登录态且无自动写请求风险。

### Alternative M：零新增插件，按端集中注册组件清单或在 51 个 `.vue` 内逐页手工导入

*   **Pros**：零新增 `devDependency`，构建链路不引入模板编译期改写。
*   **Cons**：按端集中注册会使 `/` 与 `/login` 首包被迫捆绑 15 个仅在用户端子页使用的重组件（实测未压缩源模块已多出 `108.2 KB` gzip，尚不含 `date-picker` 传递拉入的 `time-picker`/`dayjs`，`38.7 KB/s` 下多耗约 `1.5–2.1 s`）；而 51 个 `.vue` 逐页手工维护组件与 `style/css.mjs` 改动面大且无法靠构建/ESLint 检出隐式子组件 CSS 漏导。
*   **Reason for Rejection**：Rejected by author: 选择 OQ-2 选项 A（引入 `unplugin-vue-components` 仅做模板按需、禁用 `unplugin-auto-import`），在可控的 1 个构建期依赖下获得按路由最小首包与低漏导风险。

--------------------------------------------------------------------------------

## Non-Functional Requirements

*   **Security & Privacy**：
    *   `?demo=*` 仅做表单预填，严禁在页面加载、爬虫抓取或预渲染时自动触发 `POST /api/*/auth/login` 写请求，亦不覆写浏览器既有登录态。
    *   演示口令不写入前端 Git 源码（生产经 GitHub Repository Variable `DEMO_PASSWORD` → `deploy/web/Dockerfile` `ARG VITE_DEMO_PASSWORD` 注入；该口令本身已在 `/demo` 公开展示，此隔离用于区分公网演示环境与本地开发默认 `123456`）。
    *   `deploy/nginx/default.conf` 对 `/.vite/` 返回 `404`，不对外暴露完整构建依赖清单；`/api/ws/` 保持既有 `access_log off` 与 `error_log /dev/null crit` 防止 JWT 落盘，且不经第三方免费 CDN 转发 WebSocket。
*   **Performance & Latency**：
    *   按实测物理链路（`250 ms RTT`、`38.7 KB/s`）设定可验证预算：`index.html` gzip ≤ `14 KB`（1 RTT 结束白屏，FCP 约 `1–2 s`），首屏入口关键路径 JS + CSS gzip ≤ `165 KB`（预期实测 `110–160 KB`，正常冷启约 `4–6 s`），登录页团团 WebP ≤ `40 KB`（单图 CI 上限 `50 KB`）。
    *   晚高峰跨境丢包（`10–20 KB/s`）下冷启动仍可能回退至 `8–12 s`，验收同时记录 `TTI - FCP` 空壳死窗耗时与真机白天/晚高峰 p50/p90，不虚报物理带宽之外的指标。
    *   `/demo` 空窗预热严格在 `window.load` 空闲期启动并遵守 `saveData`，绝不阻塞 `/demo` 自身首屏绘制，也不预热管理端 ECharts。
*   **Rollout & Rollback**：
    *   **Phase 0** 为宿主机只读核查，零线上变更。
    *   **Phase 1** 全部经由现有 `.github/workflows/deploy.yml` 推送 `yuzhua-web` 与 `yuzhua-backend` 新镜像并 `docker compose up -d --no-build --wait` 滚动发布，不改动线上 `compose.yaml` 卷挂载；若上线后出现异常，可直接通过 GitHub Actions `workflow_dispatch` 填入上一稳定提交完整 SHA（`image_tag`）一键回滚镜像，无需手工操作宿主机。
    *   前端 `vite:preloadError` 30 秒防循环单次自愈兜底滚动发布窗口内的旧页路由切换。
    *   **Phase 2**（仅当 Phase 0 查出宿主机外层 nginx 阻碍项时独立执行）：修改前备份宿主机 nginx 配置，经 `sudo nginx -t` 校验通过后执行 `sudo nginx -s reload`；回滚直接恢复备份并 reload。

--------------------------------------------------------------------------------

### Additional Resources

*   仓库协作与验证门禁规范：[AGENTS.md](../../AGENTS.md)
*   生产部署与自动发布说明：[部署步骤.md](../%E9%83%A8%E7%BD%B2%E6%AD%A5%E9%AA%A4.md)
*   前端视觉与样式令牌规范：[design.md](../design.md)
*   演示落地页视觉与交互基线：[agent-notes.md](../../deploy/demo/agent-notes.md)

### Future Improvements

*   **条件 Phase 3（网络层边缘加速）**：若 Phase 1 + Phase 2 上线后经多日真机 5G 晚高峰采样仍存在明显吞吐瓶颈，可另立专项对免费/低成本边缘加速做三网 5G 晚高峰 A/B 实测，并同步加固 `consultSocket.js` 的断线重连策略与 WebSocket 鉴权传输方式。
*   **`/demo` 预热清单按需扩展**：若上线实测表明访客从 `/demo` 点击进入 `/login` 后，登录页懒加载 chunk 与 `tuantuan.webp` 仍带来可感知等待，可在评估只看视频访客的额外流量代价后，选择性将登录页静态资产纳入 `demo-preload.json`。

--------------------------------------------------------------------------------

## Detailed Implementation Plan

所有代码与配置改动按「构建与入口基础设施 → 核心业务与资产改造 → 其余 29 个 `.vue` 图标与 `ElMessageBox` 迁移 → 构建脚本/单测 → `/demo`/Docker/Nginx/CI → 后端缓存 → 文档与证据」顺序落地。本计划不输出实现源码，仅定义文件级契约、签名与变更动因。

### Target Files to Create/Modify/Delete

#### 一、前端构建、门禁与入口核心

1.  **`frontend/package.json`** (**Modify**)
    *   **Type / Scope**：前端依赖与 npm 脚本配置
    *   **Proposed Changes**：在 `devDependencies` 新增 `unplugin-vue-components`；将 `build` 更新为在 `vite build` 完成后顺序执行 `node tools/postbuild-assets.mjs`；新增 `bundle-budget` 脚本调用 `node tools/bundle-budget.mjs`，并将其接入 `verify`（`lint && style-guard && test:coverage && build && bundle-budget`）。保持 `test:coverage` 覆盖率门禁对象（`tools/style-guard/index.mjs`、`src/utils/tableDensity.js`）不变。
    *   **Rationale**：建立模板按需编译、构建后 `.gz` / 预热清单生成与包体门禁的自动化闭环。

2.  **`frontend/package-lock.json`** (**Modify**)
    *   **Type / Scope**：依赖锁文件
    *   **Proposed Changes**：通过 `npm install --save-dev unplugin-vue-components` 同步更新依赖树。
    *   **Rationale**：保障本地、CI 与 `deploy/web/Dockerfile` 的 `npm ci` 可复现构建。

3.  **`frontend/vite.config.js`** (**Modify**)
    *   **Type / Scope**：Vite 构建配置与原生插件
    *   **Proposed Changes**：
        *   引入 `unplugin-vue-components/vite` 与 `ElementPlusResolver`，注册 `Components({ dts: false, resolvers: [ElementPlusResolver({ importStyle: 'css', directives: true })] })`；
        *   新增原生插件 `elementPlusLayer()`，其 `transform(code, id)` 对路径匹配 `/[\\/]node_modules[\\/]element-plus[\\/]theme-chalk[\\/].*\.css$/`（剥离 query 后且非 `?raw`/`?url`）的样式外包 `@layer element-plus { ... }`；
        *   扩展 `brandHtml()` 占位替换，在 `%BRAND_TITLE%`、`%BRAND_DESCRIPTION%` 基础上补充 `%BRAND_NAME%` 与 `%BRAND_TAGLINE%`（均取自 `src/brand.js`）；
        *   在 `build` 配置开启 `manifest: true`。
    *   **Rationale**：实现按路由最小拆包、解除 `main.css` 5 处覆写对加载顺序的依赖、遵守品牌单一事实源，并为 `postbuild-assets.mjs` 提供精准入口清单数据源。

4.  **`frontend/eslint.config.js`** (**Modify**)
    *   **Type / Scope**：ESLint 静态约束规则
    *   **Proposed Changes**：新增 `no-restricted-imports` 规则：禁止 `element-plus` 默认导入/命名空间导入与 `element-plus/dist/index.css`；禁止 `@element-plus/icons-vue` 命名空间导入；禁止业务文件直接从 `element-plus` 导入 `ElMessageBox`（通过 `files: ['src/utils/messageBox.js']` 单独豁免封装文件自身）。
    *   **Rationale**：在 `lint` 阶段阻断全量导入回退与未绑定样式/中文兜底的 `ElMessageBox` 裸调。

5.  **`frontend/index.html`** (**Modify**)
    *   **Type / Scope**：SPA 唯一 HTML 入口
    *   **Proposed Changes**：
        *   在 `<head>` 末尾追加内联预拉脚本（< 350 B）：仅当 `location.pathname === '/'` 时发起 `fetch('/api/client/catalog/home', { credentials: 'same-origin' })`，将 `{ ts: Date.now(), promise }` 写入 `window.__YUZHUA_HOME_PREFETCH__`，异常与非 200 业务码统一吞错返回 `null`；
        *   在 `<div id="app">` 内部写入轻量内联 `<style>` 与非交互品牌加载壳（展示品牌 SVG 标志、`%BRAND_NAME%`、`%BRAND_TAGLINE%` 与无交互加载提示；严禁含 `<h1>`、假按钮/输入框或底部导航栏；支持 `prefers-reduced-motion`）。整份 HTML gzip 严控 ≤ 14 KB。
    *   **Rationale**：1 RTT 结束纯白屏，同时让 `/` 首页接口与入口 JS/CSS 并发传输。

6.  **`frontend/src/main.js`** (**Modify**)
    *   **Type / Scope**：前端应用启动入口
    *   **Proposed Changes**：移除 `import * as Icons from '@element-plus/icons-vue'`、`import ElementPlus from 'element-plus'`、`import zhCn`、`import 'element-plus/dist/index.css'` 及图标全量注册循环；保留 `./styles/main.css` 与 `./styles/admin.css`；显式导入 `element-plus/es/components/message/style/css`；在 `app.mount('#app')` 前注册 `window.addEventListener('vite:preloadError', ...)`（使用 `sessionStorage` 键 `yuzhua_preload_reload_at` 做 30 秒窗口防循环单次 `location.reload()`）。
    *   **Rationale**：消除首屏最大体量冗余依赖，保障 `ElMessage` 样式就绪并兜底滚动发版 chunk 404。

7.  **`frontend/src/App.vue`** (**Modify**)
    *   **Type / Scope**：根组件
    *   **Proposed Changes**：外层包裹 `<el-config-provider :locale="zhCn">`（导入 `element-plus/es/locale/lang/zh-cn`）；改用 `defineAsyncComponent(() => import('@/components/ImageViewerHost.vue'))` 并通过 `v-if="imageViewer.open"` 按需挂载 `ImageViewerHost`（导入 `imageViewer` 响应式状态）。
    *   **Rationale**：移除 `main.js` 全量插件注册后维持全站中文 locale，同时将 `el-image-viewer` 彻底移出首屏包。

8.  **`frontend/src/router/index.js`** (**Modify**)
    *   **Type / Scope**：路由表与前置守卫
    *   **Proposed Changes**：将 `ClientLayout`（`@/layouts/ClientLayout.vue`）与 `Home`（`@/views/client/Home.vue`）改为顶层静态导入，其余全部路由保持动态 `import()`；移除顶层静态 `import { useAdminStore } from '@/stores/admin'`，将 `router.beforeEach` 改为 `async` 函数，在 `if (to.meta.admin)` 分支内执行 `const { useAdminStore } = await import('@/stores/admin')`。
    *   **Rationale**：合并 `/` 首屏核心代码消灭路由二次跨海 RTT，同时切断用户端首包对 `src/api/admin.js` 的静态引入。

#### 二、新增业务工具、首屏业务页与吉祥物资产

9.  **`frontend/src/utils/messageBox.js`** (**Create**)
    *   **Type / Scope**：命令式弹窗封装模块
    *   **Proposed Changes**：导入 `ElMessageBox` 与 `element-plus/es/components/message-box/style/css`；导出 `ElMessageBox` 代理对象（覆盖 `confirm`、`prompt`、`alert`），当调用方未传 `confirmButtonText`/`cancelButtonText` 时默认补入 `'确定'`/`'取消'`（`alert` 仅补 `confirmButtonText`），透传其余全部参数与返回值。
    *   **Rationale**：隔离 `ElMessageBox` JS+CSS 出首屏包，同时杜绝按需模式下漏导样式或回退英文 `OK/Cancel` 按钮。

10. **`frontend/src/utils/demoPreset.js`** (**Create**)
    *   **Type / Scope**：演示入口参数解析纯模块
    *   **Proposed Changes**：导出 `resolveDemoPreset(side, demoQuery, demoPassword = import.meta.env.VITE_DEMO_PASSWORD)`；校验 `side` 与白名单（`client`: `user→zhangsan`；`admin`: `doctor→doctor01`、`admin→admin`），非白名单返回 `null`；`password` 仅当 `demoPassword` 非空时填充，否则为空串。
    *   **Rationale**：消费 `?demo=*` 且与本地种子密码 `123456` 隔离，源码不出现明文生产演示密码。

11. **`frontend/src/assets/tuantuan.webp`** (**Create**)
    *   **Type / Scope**：静态图片资产
    *   **Proposed Changes**：离线从 `tuantuan-a-final.png`（`1774×887`）转码导出精确 `2:1` 比例的 `1020×510`（若超 38 KB 则降档 `960×480`）带 Alpha 通道 WebP，体积严格 ≤ `40 KB`。
    *   **Rationale**：将登录页主图由 `570 KB` 压降至 ≤ `40 KB`（38.7 KB/s 链路省约 13 秒下载争抢），同时满足 3x 旗舰屏清晰度。

12. **`frontend/src/assets/tuantuan-a-final.png`** (**Delete**)
    *   **Type / Scope**：废弃大图资产
    *   **Proposed Changes**：删除原 `570 KB` PNG 文件（全仓已核查仅 `LoginScene.vue:3` 引用）。
    *   **Rationale**：避免残留不可压缩大图重新被引用或触发资产清理歧义。

13. **`frontend/src/components/LoginScene.vue`** (**Modify**)
    *   **Type / Scope**：双端登录场景外壳组件
    *   **Proposed Changes**：将 `mascot` 导入路径替换为 `@/assets/tuantuan.webp`；同步更新 `<img class="mascot">` 的 `width`/`height` 属性为导出 WebP 的精确 `2:1` 像素值。
    *   **Rationale**：切换轻量 WebP 并保持原有 `translate(-50%, -73.3%)` 视觉布局零位移。

14. **`frontend/src/components/LoginForm.vue`** (**Modify**)
    *   **Type / Scope**：双端登录表单组件
    *   **Proposed Changes**：新增可选 props `initialUsername`（String，默认 `''`）与 `initialPassword`（String，默认 `''`），初始化 `form` 并在用户尚未手工改动字段时响应路由 query 变更同步预填；显式导入 `@element-plus/icons-vue` 的 `Lock`、`User`。
    *   **Rationale**：承接 `?demo=*` 预填需求并允许用户随时覆盖输入；满足图标显式导入。

15. **`frontend/src/views/client/Login.vue`** (**Modify**)
    *   **Type / Scope**：宠主端登录页
    *   **Proposed Changes**：读取 `route.query.demo` 并通过 `resolveDemoPreset('client', ...)` 计算初始账号与密码传入 `LoginForm`；不自动调用 `store.login()`，不清除或覆盖既有登录态。
    *   **Rationale**：按 OQ-1 选项 A 接通 `/login?demo=user`。

16. **`frontend/src/views/admin/Login.vue`** (**Modify**)
    *   **Type / Scope**：医院后台登录页
    *   **Proposed Changes**：读取 `route.query.demo` 并通过 `resolveDemoPreset('admin', ...)` 计算初始账号与密码传入 `LoginForm`；不自动提交登录，保留既有 `route.query.redirect` 跳转逻辑。
    *   **Rationale**：按 OQ-1 选项 A 接通 `/admin/login?demo=doctor&redirect=...` 与 `?demo=admin&redirect=...`。

17. **`frontend/src/views/client/Home.vue`** (**Modify**)
    *   **Type / Scope**：宠主端首页
    *   **Proposed Changes**：
        *   `load()` 首次执行时优先原子取出并清空 `window.__YUZHUA_HOME_PREFETCH__`（30 秒内有效），若解析值非空直接写入 `data`，若为 `null`、已过期或用户手动重试则回退 `homeData()`；
        *   移除根节点 `<div class="pc-narrow home">` 的 `v-loading="loading"` 全页遮罩，改为科室/医生/公告三段列表在 `loading` 且无数据时显示与真实卡片高度匹配的轻量静态骨架占位；
        *   显式导入 `ArrowRight`、`ChatDotRound`、`Search`、`Tickets` 图标，将 `el-button` 的 `icon="Search"` 改为 `:icon="Search"`。
    *   **Rationale**：消费 `<head>` 预拉数据、消除全页交互死窗并避免首屏拉入 `ElLoading`。

18. **`frontend/src/components/ImageViewerHost.vue`** (**Modify**)
    *   **Type / Scope**：全站图片查看器宿主组件
    *   **Proposed Changes**：将 `watch([() => imageViewer.open, () => imageViewer.index], ...)` 补充 `{ immediate: true }`；显式导入 `PictureFilled` 图标。
    *   **Rationale**：配合 `App.vue` 异步条件挂载，确保首次点击大图打开时 `alt` 属性正确写入。

19. **`frontend/src/views/admin/Dashboard.vue`** (**Modify**)
    *   **Type / Scope**：后台工作台看板页
    *   **Proposed Changes**：移除顶层静态 `echarts/core`、图表/组件/渲染器导入与顶层 `echarts.use(...)`；新增模块级缓存函数 `loadEcharts()`，在 `draw()` 内异步 `await loadEcharts()` 并完成初始化与 `ResizeObserver` 绑定（在 `await` 后复用 `current !== requestId` 守卫避免卸载后绘制）。
    *   **Rationale**：将 ECharts 与 Dashboard 视图主代码拆包解耦，让 KPI 卡片优先渲染且防止任何 ECharts 依赖泄漏进入口。

#### 三、全仓剩余 29 个 `.vue` 文件的图标显式导入、`ElMessageBox` 收口与 `ElDrawer` 规范化

20. **`frontend/src/layouts/ClientLayout.vue`** (**Modify**)
    *   **Type / Scope**：用户端外壳组件
    *   **Proposed Changes**：显式导入 `ChatDotRound`、`HomeFilled`、`Search`、`User`（配合 `markRaw` 写入 `tabs` 数组替代字符串图标名，保持 `<component :is="t.icon" />`）。
    *   **Rationale**：移除全局图标注册后保持底部导航 4 个图标正常渲染。

21. **`frontend/src/layouts/AdminLayout.vue`** (**Modify**)
    *   **Type / Scope**：管理端外壳组件
    *   **Proposed Changes**：显式导入 `Avatar`、`Bell`、`Calendar`、`ChatDotRound`、`Clock`、`Collection`、`DataLine`、`Document`、`Expand`、`Fold`、`Notebook`、`OfficeBuilding`、`Star`、`Tickets`、`User`（菜单项 `icon` 改用 `markRaw` 组件引用替代字符串）；`ElMessageBox` 改为从 `@/utils/messageBox` 导入，并在退出确认（原 L83）显式核验中文按钮文案（如 `confirmButtonText: '退出登录', cancelButtonText: '取消'`）。
    *   **Rationale**：补齐侧栏/折叠图标显式导入并修复 L83 缺省按钮文案在按需模式下的英文回退。

22. **`frontend/src/views/client/Mine.vue`** (**Modify**)
    *   **Type / Scope**：用户端个人中心外壳
    *   **Proposed Changes**：显式导入 `Notebook`、`Setting`、`Sunny`、`Tickets`（`nav` 数组 `icon` 改为 `markRaw` 组件引用替代字符串）。
    *   **Rationale**：移除全局图标注册后保持 4 个子页导航图标正常渲染。

23. **`frontend/src/views/admin/ConsultRoom.vue`** (**Modify**)
    *   **Type / Scope**：医生端问诊聊天室
    *   **Proposed Changes**：从 `import { ElMessage, ElDrawer } from 'element-plus'` 中移除 `ElDrawer`，模板 `<ElDrawer>` 改为 `<el-drawer>` 交由 `unplugin-vue-components` 自动按需引入组件与 CSS；显式导入 `ArrowLeft`、`Document` 图标。
    *   **Rationale**：避免手动导入 `ElDrawer` 导致抽屉样式缺失，并补齐图标导入。

24. **`frontend/src/components/ConsultPanel.vue`** (**Modify**) — 显式导入 `Calendar`、`Picture`、`Top`、`ZoomIn` 图标，适配全量图标移除。
25. **`frontend/src/components/DoctorCard.vue`** (**Modify**) — 显式导入 `ArrowRight` 图标，适配全量图标移除。
26. **`frontend/src/components/ImageGallery.vue`** (**Modify**) — 显式导入 `PictureFilled`、`ZoomIn` 图标，适配全量图标移除。
27. **`frontend/src/components/ImageUpload.vue`** (**Modify**) — 显式导入 `Close`、`Loading`、`PictureFilled`、`Plus` 图标，适配全量图标移除。
28. **`frontend/src/components/PetPicker.vue`** (**Modify**) — 显式导入 `Plus`、`Select` 图标，适配全量图标移除。
29. **`frontend/src/views/client/Booking.vue`** (**Modify**) — 显式导入 `ArrowLeft` 图标，适配全量图标移除。
30. **`frontend/src/views/client/ConsultList.vue`** (**Modify**) — 显式导入 `ArrowRight` 图标，适配全量图标移除。
31. **`frontend/src/views/client/DoctorList.vue`** (**Modify**) — 显式导入 `Search` 图标，并将 L68 `prefix-icon="Search"` 改为 `:prefix-icon="Search"`。
32. **`frontend/src/views/client/NoticeDetail.vue`** (**Modify**) — 显式导入 `View` 图标，适配全量图标移除。
33. **`frontend/src/views/client/NoticeList.vue`** (**Modify**) — 显式导入 `Search`、`View` 图标，并将 L59 `prefix-icon="Search"` 改为 `:prefix-icon="Search"`。
34. **`frontend/src/views/client/ConsultRoom.vue`** (**Modify**) — 显式导入 `ArrowLeft` 图标；`ElMessageBox` 改为从 `@/utils/messageBox` 导入。
35. **`frontend/src/views/client/MyOrders.vue`** (**Modify**) — `ElMessageBox` 改为从 `@/utils/messageBox` 导入。
36. **`frontend/src/views/client/MyPets.vue`** (**Modify**) — 显式导入 `Plus` 图标；`ElMessageBox` 改为从 `@/utils/messageBox` 导入。
37. **`frontend/src/views/client/OrderDetail.vue`** (**Modify**) — `ElMessageBox` 改为从 `@/utils/messageBox` 导入。
38. **`frontend/src/views/admin/AppointmentDetail.vue`** (**Modify**) — `ElMessageBox` 改为从 `@/utils/messageBox` 导入。
39. **`frontend/src/views/admin/Appointments.vue`** (**Modify**) — `ElMessageBox` 改为从 `@/utils/messageBox` 导入，并在结束就诊确认（原 L164）显式补充中文按钮文案（`confirmButtonText: '确定完成', cancelButtonText: '取消'`）。
40. **`frontend/src/views/admin/ConsultRecords.vue`** (**Modify**) — 显式导入 `ArrowDown` 图标；`ElMessageBox` 改为从 `@/utils/messageBox` 导入，并在恢复消息（原 L107）与撤销处置（原 L120）显式补充中文按钮文案。
41. **`frontend/src/views/admin/Departments.vue`** (**Modify**) — `ElMessageBox` 改为从 `@/utils/messageBox` 导入。
42. **`frontend/src/views/admin/Doctors.vue`** (**Modify**) — 显式导入 `Search` 图标并将 L161 `prefix-icon="Search"` 改为 `:prefix-icon="Search"`；`ElMessageBox` 改为从 `@/utils/messageBox` 导入。
43. **`frontend/src/views/admin/Notices.vue`** (**Modify**) — 显式导入 `Search` 图标并将 L113 `prefix-icon="Search"` 改为 `:prefix-icon="Search"`；`ElMessageBox` 改为从 `@/utils/messageBox` 导入。
44. **`frontend/src/views/admin/Pets.vue`** (**Modify**) — 显式导入 `Search` 图标并将 L95 `prefix-icon="Search"` 改为 `:prefix-icon="Search"`；`ElMessageBox` 改为从 `@/utils/messageBox` 导入。
45. **`frontend/src/views/admin/Records.vue`** (**Modify**) — 显式导入 `Search` 图标并将 L50 `prefix-icon="Search"` 改为 `:prefix-icon="Search"`。
46. **`frontend/src/views/admin/Schedules.vue`** (**Modify**) — `ElMessageBox` 改为从 `@/utils/messageBox` 导入，并在删除排班确认（原 L193-199）显式补充中文按钮文案（`confirmButtonText: '确认删除', cancelButtonText: '取消'`）。
47. **`frontend/src/views/admin/Users.vue`** (**Modify**) — 显式导入 `Search` 图标并将 L77 `prefix-icon="Search"` 改为 `:prefix-icon="Search"`；`ElMessageBox` 改为从 `@/utils/messageBox` 导入。
48. **`frontend/src/views/admin/Workbench.vue`** (**Modify**) — 显式导入 `ArrowDown` 图标；`ElMessageBox` 改为从 `@/utils/messageBox` 导入；保持 `<el-collapse-transition>` 交由 `unplugin-vue-components` 按需引入。

#### 四、构建后处理、包体门禁脚本与前端单元测试

49. **`frontend/tools/postbuild-assets.mjs`** (**Create**)
    *   **Type / Scope**：纯 Node 构建后处理脚本（`node:fs` / `node:path` / `node:zlib`）
    *   **Proposed Changes**：
        *   默认模式：读取 `dist/.vite/manifest.json`，从 `index.html` 入口节点递归提取静态 `file`、`css` 与静态 `imports` 形成去重 URL 数组写入 `dist/demo-preload.json`（严格忽略 `dynamicImports`）；同时对 `dist/` 下全部 ≥ 256 B 的 `.js`/`.css`/`.html`/`.svg`/`.json` 生成 `level: 9` 的同名 `.gz`（仅在压缩后严格变小时落盘）；
        *   `--gzip-only <dir>` 模式：跳过 manifest 解析，仅对指定目录预生成 `.gz` 文件。
    *   **Rationale**：消除手工维护哈希文件名的违规风险（`AGENTS.md` 规矩二），跨平台（Windows/Alpine）产出 `gzip_static` 预压缩资产。

50. **`frontend/tools/bundle-budget.mjs`** (**Create**)
    *   **Type / Scope**：纯 Node 包体与预压缩门禁脚本（导出纯校验函数供测试）
    *   **Proposed Changes**：定义可配置的预算常量（`HTML_GZIP_MAX = 14 * 1024`、`ENTRY_GZIP_MAX = 165 * 1024`、`IMAGE_MAX = 50 * 1024`）；解析 `dist/index.html` 提取全部入口 `<script type="module">`、`<link rel="stylesheet">` 与 `<link rel="modulepreload">` 并核算实际 `.gz`（或实时 `gzipSync`）总字节；断言无 `echarts` 落入入口集合；扫描 `dist/assets/` 图片体积上限；断言 `dist/demo-preload.json` 与所有关键文本产物均具备同名 `.gz`。任一超限打印明细并以退出码 `1` 失败。
    *   **Rationale**：长期防止首屏包体与图片体积回退。

51. **`frontend/tests/demo-preset.test.mjs`** (**Create**)
    *   **Type / Scope**：前端单元测试（`node --test`）
    *   **Proposed Changes**：覆盖 `resolveDemoPreset` 的合法角色映射、跨端越权隔离（如 `client` 传 `admin`/`doctor` 返 `null`）、空密码降级与未知参数处理。
    *   **Rationale**：验证 `?demo=*` 参数解析与环境隔离不变量。

52. **`frontend/tests/bundle-budget.test.mjs`** (**Create**)
    *   **Type / Scope**：前端单元测试（`node --test`）
    *   **Proposed Changes**：使用临时目录或内存夹具测试 `bundle-budget.mjs` 导出的校验函数，覆盖通过基线、HTML 超 14 KB 失败、入口总字节超限失败、入口含 `echarts` 失败、单图超 50 KB 失败、缺失 `.gz` 失败。
    *   **Rationale**：确保包体预算门禁本身不会因解析缺陷静默放行。

#### 五、`/demo` 落地页、容器镜像、Nginx 与 CI/CD

53. **`deploy/demo/index.html`** (**Modify**)
    *   **Type / Scope**：对外宣传静态落地页
    *   **Proposed Changes**：在末尾既有 IIFE `<script>` 中新增空窗温和预热逻辑（`window.load` → `requestIdleCallback` / `setTimeout(800)` → 检查 `!navigator.connection?.saveData && effectiveType !== 'slow-2g'` → `fetch('/demo-preload.json')` → 并发 ≤ 2 调用 `fetch(url, { method: 'GET', mode: 'cors', credentials: 'same-origin', cache: 'default' })` 并消费 `arrayBuffer()`，全程静默容错）；待 Phase 1 线上冷启实测达标后，拆分更新第 375 行 `#slow-note` 文案，仅保留医生/管理端桌面浏览建议。
    *   **Rationale**：利用访客 5–15 秒阅读窗预热 SPA 入口资产且兼容 iOS Safari / 微信 WKWebView，并清理过时慢加载提示。

54. **`deploy/web/Dockerfile`** (**Modify**)
    *   **Type / Scope**：前端与落地页容器镜像构建定义
    *   **Proposed Changes**：在 `build` stage 新增 `ARG VITE_DEMO_PASSWORD=""` 与 `ENV VITE_DEMO_PASSWORD=$VITE_DEMO_PASSWORD`（在 `RUN npm run build` 之前）；随后拷贝 `deploy/demo/index.html` 与 `deploy/demo/agent-assets/` 至 `/src/demo-stage/` 并运行 `node tools/postbuild-assets.mjs --gzip-only /src/demo-stage`；在 `nginx:1.28-alpine` stage 将 `/src/demo-stage/` 复制到 `/usr/share/nginx/html/demo/`。
    *   **Rationale**：向生产前端透传演示口令变量，同时为 `/demo/` 文本资产一并预生成 `.gz`，零新增 Alpine 包依赖。

55. **`deploy/nginx/default.conf`** (**Modify**)
    *   **Type / Scope**：容器内 Nginx 配置
    *   **Proposed Changes**：
        *   在 `server` 块启用 `gzip_static on; gzip on; gzip_vary on; gzip_http_version 1.0; gzip_proxied any; gzip_min_length 256; gzip_types text/plain text/css application/javascript application/json image/svg+xml;`；
        *   新增 `location /.vite/ { return 404; }`；
        *   将 `location /assets/` 从 `expires 7d;` 改为 `add_header Cache-Control "public, max-age=31536000, immutable";`；
        *   新增 `location = /demo-preload.json { add_header Cache-Control "no-cache"; }`；
        *   新增更具体的 `location /demo/agent-assets/ { add_header Cache-Control "public, max-age=604800"; try_files $uri =404; }`，并将 `location /demo/` 的 `Cache-Control` 由 `no-store` 改为 `no-cache`。
    *   **Rationale**：实现容器层静态预压缩自治与三类静态资产的精准缓存策略，恢复 `/demo/` 的 `bfcache` 与 ETag 304。

56. **`.github/workflows/ci.yml`** (**Modify**)
    *   **Type / Scope**：GitHub Actions 持续集成门禁
    *   **Proposed Changes**：在 `frontend` job 的 `构建` 步骤之后新增 `包体预算门禁` 步骤运行 `npm run bundle-budget`。
    *   **Rationale**：让每次 PR 与 `main` 推送都在 CI 阶段独立显式卡住首屏包体与预压缩门禁。

57. **`.github/workflows/deploy.yml`** (**Modify**)
    *   **Type / Scope**：GitHub Actions 生产构建与部署流水线
    *   **Proposed Changes**：
        *   在 `build` job 的 `Build and push web` 步骤新增 `build-args: VITE_DEMO_PASSWORD=${{ vars.DEMO_PASSWORD }}`；
        *   在 `deploy` job 的 `Smoke-test the live site` 循环中扩展校验：请求 `https://yuzhua-petclinic.top/`（带 `Accept-Encoding: gzip`）确认 `HTTP 200`、`Content-Encoding: gzip` 与 `Cache-Control: no-cache`；确认 `https://yuzhua-petclinic.top/demo-preload.json` 返回 `HTTP 200`；从 `/` HTML 提取首个 `/assets/index-*.js` 校验 `HTTP 200`、`Content-Encoding: gzip` 与 `Cache-Control` 含 `immutable`。
    *   **Rationale**：打通生产演示口令配置透传，并在上线冒烟阶段直接验证压缩与缓存头在双层 Nginx 后真实生效。

#### 六、后端静态图分层缓存与测试

58. **`backend/src/main/java/com/yuzhua/config/WebMvcConfig.java`** (**Modify**)
    *   **Type / Scope**：Spring MVC 静态资源配置
    *   **Proposed Changes**：在 `addResourceHandlers` 先注册 `resourcePattern() + "/seed/**"` → `file:<location>/seed/` 并配置 `CacheControl.maxAge(Duration.ofDays(1)).cachePublic()`；随后注册 `resourcePattern() + "/**"` → `file:<location>/` 并配置 `CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable()`。
    *   **Rationale**：让种子图获得 24 小时缓存且更新不会卡死一年，同时为不可变 UUID 上传图开启 1 年强缓存，不改动 `compose.yaml` 卷挂载。

59. **`backend/src/test/java/com/yuzhua/config/WebMvcConfigCacheControlTest.java`** (**Create**)
    *   **Type / Scope**：后端 Web 层单元测试（免 Docker）
    *   **Proposed Changes**：在临时上传目录写入 `seed/sample.jpg` 与 `2026/11/22/uuid.png`，通过轻量 `MockMvc` 请求两类路径，断言前者 `Cache-Control` 含 `max-age=86400` 且不含 `immutable`，后者含 `max-age=31536000` 与 `immutable`。
    *   **Rationale**：验证两条资源映射顺序与缓存头语义不回退。

#### 七、架构决策文档、部署文档与实测证据（含条件触发文件）

60. **`docs/adr/0012-frontend-cold-start-and-asset-delivery.md`** (**Create**)
    *   **Type / Scope**：架构决策记录（ADR）
    *   **Proposed Changes**：记录本次冷启动治理背景、关键约束、四项已拍板决定及否决方案（原生 HTML、Brotli 模块、免费 CDN、Service Worker、按端集中注册）。
    *   **Rationale**：沉淀决策脉络，避免后续迭代重新引入全量导入或盲接不稳定外部代理。

61. **`docs/部署步骤.md`** (**Modify**)
    *   **Type / Scope**：运维与部署文档
    *   **Proposed Changes**：在 7.2 节后新增 `### 7.3 宿主机外层 Nginx 核查与演示变量配置`，收录 Phase 0 只读核查命令、外层反代头透传核对清单（`Accept-Encoding`、`Cache-Control`、`CSP`）、`DEMO_PASSWORD` 仓库 Variable 配置说明及回滚指令。
    *   **Rationale**：补齐宿主机外层反代的可操作运维基线。

62. **`docs/README.md`** (**Modify**)
    *   **Type / Scope**：文档总索引
    *   **Proposed Changes**：在「产品与设计」节补充 `[designs/](designs/)` 设计文档目录入口。
    *   **Rationale**：确保本设计与后续设计文档可从文档索引发现。

63. **`docs/test-evidence/<YYYYMMDD>-cold-start/README.md` 及同目录原始采集文件** (**Create**)
    *   **Type / Scope**：可追溯验收证据归档
    *   **Proposed Changes**：按日期新建证据目录（不改动任何既有 `docs/test-evidence/**` 文件），归档 Phase 0 宿主机只读核查脱敏输出、优化前后 `dist` 体积与关键路径 gzip 对照、CDP 锁定基线与真机 5G 白天/晚高峰实测记录、以及 `npm run verify` 与 `mvn verify` 原始日志。
    *   **Rationale**：遵守 `AGENTS.md` 规矩二，为后续 README 或评审引用的全部实测数据提供原始依据。

64. **`docs/screenshots/*.png`** (**Conditional Regenerate**)
    *   **Type / Scope**：派生界面截图资产
    *   **Proposed Changes**：在生产构建容器上执行 `node tools/screenshots.mjs`；仅当存在预期像素更新（如登录页团团 WebP）且全部断言通过时提交脚本生成的更新图，**严禁手工替换单张截图**。
    *   **Rationale**：遵守 `AGENTS.md` 规矩二，验证全站 32 张页面在按需加载与 `@layer` 改造后无样式/中文回退。

65. **`deploy/host/yuzhua-petclinic.top.nginx.example`** (**Conditional Create — 仅 Phase 2 触发**)
    *   **Type / Scope**：宿主机外层反代脱敏参考模板
    *   **Proposed Changes**：仅当 Phase 0 核查发现宿主机外层 nginx 存在阻碍项并完成 Phase 2 对齐时创建，记录透传 `Accept-Encoding`、`Cache-Control` 与 HTTP/1.1 回源的最小参考配置。
    *   **Rationale**：将脱管的宿主机关键假设显性沉淀到仓库。

---

### Verification Plan

验证严格遵循 `AGENTS.md` 规矩三的 L1 / L2 / L3 分层时机执行：

*   **Phase 0（前置宿主机只读诊断，零修改）**：
    *   在 `47.250.169.146` 执行只读命令：
        *   `nginx -V 2>&1`
        *   `sudo nginx -T 2>/dev/null | sed -n '/yuzhua-petclinic\.top/,/^}/p'`
        *   `sudo nginx -T 2>/dev/null | grep -nE 'listen |http2|http3|gzip|brotli|proxy_http_version|Accept-Encoding|Cache-Control|expires|Content-Security-Policy|sub_filter|limit_req|proxy_cache|location '`
        *   `sysctl net.ipv4.tcp_congestion_control net.core.default_qdisc`
        *   `docker ps --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}\t{{.Ports}}'`
    *   判据：确认未清空 `Accept-Encoding`、未覆写 `Cache-Control`、无阻断内联 `<style>`/`<script>` 的 `CSP`；若发现阻碍项则单独规划 Phase 2。

*   **L1 轻量局部验证（小段改动收尾执行，免 Docker）**：
    *   前端单测、静态检查与样式护栏：`cd frontend && npm run lint && npm run style-guard && npm test`
    *   前端构建与包体门禁：`cd frontend && npm run build && npm run bundle-budget`
    *   后端缓存头单测：`cd backend && mvn test -Dtest=WebMvcConfigCacheControlTest`
    *   截图清单预检：`node tools/screenshots.mjs --dry-run`

*   **L2 合并前门禁（整体改动完成、合并前统一执行一次）**：
    *   后端全量单测与集成测试（需 Docker）：`cd backend && mvn --batch-mode --no-transfer-progress verify`
    *   前端完整验证链（含 `lint`、`style-guard`、`test:coverage`、`build`、`bundle-budget`）：`cd frontend && npm run verify`
    *   工具脚本校验：`node --test .github/scripts/localize-dependabot.test.mjs`
    *   编排语法校验：`docker compose config -q`
    *   覆盖率门禁核验：确认后端仍仅卡 `SlowSqlInterceptor` 95% LINE、前端仍仅卡 `tools/style-guard/index.mjs` 与 `src/utils/tableDensity.js` 95% 行/分支/函数，未擅自扩大覆盖率对象。

*   **L3 专项回归与上线后实测验收（针对生产构建容器与线上站点）**：
    *   **生产镜像重建与响应头核验**：执行 `docker compose up -d --build --wait`，以 `curl -I -H 'Accept-Encoding: gzip'` 逐项验证 `/`（`200`、`gzip`、`no-cache`）、入口 `/assets/index-*.js`（`200`、`gzip`、`max-age=31536000, immutable`）、`/demo/`（`200`、`gzip`、`no-cache`）、`/demo/agent-assets/tuantuan.webp`（`200`、`max-age=604800`）、`/demo-preload.json`（`200`、`no-cache`）、`/.vite/manifest.json`（`404`）、`/api/uploads/seed/doctor-01.jpg`（`max-age=86400`）与 UUID 上传图（`max-age=31536000, immutable`）。
    *   **生产容器全站 UI 与主流程回归**：对着生产构建容器执行 `node tools/screenshots.mjs --base http://127.0.0.1:8088`（或隔离栈端口）核验 32 张页面像素基线、`main.css` 5 处覆写样式、登录页团团 WebP 与中文 `ElMessageBox`；运行 `tools/main_flow_browser.mjs` 验证完整预约/支付/接诊/问诊链路零 `pageerror`。
    *   **`/demo` 预热缓存命中与 `?demo=*` 交互验证**：在 Playwright / Chrome DevTools 空缓存打开 `/demo/`，待 `load` 后空闲预热完成再点击「进入宠主端」「医生端」「管理端」，验证：
        1.  `/demo-preload.json` 内所有入口 JS/CSS 跳转后均命中磁盘缓存（零二次网络下载）；
        2.  省流量模式（`Save-Data: on` / `navigator.connection.saveData = true`）下跳过预热；
        3.  `/login?demo=user`、`/admin/login?demo=doctor&redirect=/admin/my-schedule`、`/admin/login?demo=admin&redirect=/admin/dashboard` 正确预填对应账号（带 `VITE_DEMO_PASSWORD` 时同时预填密码），且页面加载时不发出任何 `POST /api/*/auth/login` 写请求、不覆写既有登录态。
    *   **滚动发版 `vite:preloadError` 自愈验证**：在浏览器拦截某懒加载路由 chunk 返回 404，确认首错触发单次 `location.reload()` 并写入 `yuzhua_preload_reload_at`，30 秒内再次触发不再进入无限刷新。
    *   **冷启动指标对照归档**：
        1.  CDP 锁定基线（`250 ms RTT + 38.7 KB/s + 4× CPU`，空缓存）分别采集优化前/优化后 `/` 与 `/login` 的 FCP、LCP、壳可交互 TTI、业务就绪 TTI 与 `TTI - FCP`；
        2.  上线后采集中国大陆真实手机 5G 白天与晚高峰多样本（p50/p90）及 `/demo` 导流点击进入耗时，统一归档至 `docs/test-evidence/<YYYYMMDD>-cold-start/`；达标后再提交更新 `deploy/demo/index.html:375` 提示文案。

--------------------------------------------------------------------------------

<!-- Provenance: Indicates initial structural drafting via EGM. -->

*Drafted with EGM*
