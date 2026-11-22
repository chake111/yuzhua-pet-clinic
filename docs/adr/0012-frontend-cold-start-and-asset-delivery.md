# 零预算前端冷启动瘦身与分层资产交付

## 背景与实测基线

线上站点 `https://yuzhua-petclinic.top` 为单前端工程双入口（宠主端 `/` 与医院后台 `/admin`），流量经海外源站（`47.250.169.146`）宿主机外层 HTTPS 反代 nginx 转发至容器 `yuzhua-web`（`nginx:1.28-alpine`）与 Spring Boot 后端。

2026-11-22 绕过本地代理直连线上站点并核查仓库源码，确认中国大陆手机 5G 空缓存首访出现约 20 秒纯白屏，由海外物理链路约束与应用交付冗余叠加引起：

- **网络基线（2026-11-22 直连实测）**：外层已开启 TLSv1.3、HTTP/2（`h2`）与动态 `gzip`（未启用 Brotli），但单次直连测得 TCP RTT 约 `250 ms`、单流下载吞吐约 `38.7 KB/s`（约 `0.3 Mbps`）；每多传输 40 KB 首屏载荷，白屏约增加 1 秒。
- **入口载荷与登录大图（实测 + 源码核查）**：`main.js` 全量注册 `ElementPlus`、`element-plus/dist/index.css` 及全部 `@element-plus/icons-vue`（290+ 图标），`App.vue` 同步挂载 `ImageViewerHost`，`router/index.js` 顶层静态引入管理端 store。实测入口 `index-*.js`（原始 `1.28 MB` / gzip `415 KB`，下载 `10.73 s`）与 `index-*.css`（原始 `378 KB` / gzip `53 KB`，下载 `1.95 s`）合计 `468 KB` gzip；`/login` 与 `/admin/login` 额外加载 `570 KB` PNG（`frontend/src/assets/tuantuan-a-final.png`，`1774×887`）。
- **空 HTML 与 5 阶串行瀑布（源码核查）**：`index.html` 仅含空 `<div id="app"></div>`；`ClientLayout`、`Home`、`Login` 全部动态懒加载，`Home.vue` 挂载后才请求 `/api/client/catalog/home` 且整页覆盖 `v-loading` 遮罩。下载完 468 KB 入口包后仍需串行等待多趟跨海 RTT 才出现首屏内容。
- **导流衔接、压缩与缓存短板（源码核查）**：对外落地页 `/demo` 访客停留 5–15 秒期间未预热 SPA 入口资产，且链接里的 `?demo=user|doctor|admin` 未被登录页消费；容器 `deploy/nginx/default.conf` 未配置 `gzip_static`，`/assets/` 仅 `expires 7d`、`/demo/` 整段 `Cache-Control: no-store`（阻断 `bfcache` 与 304），后端 `WebMvcConfig` 对 `/uploads/**` 未配置缓存头，前端缺少发版后懒加载 chunk 404 的单次刷新自愈。

## 约束边界

- 零新增基础设施或云服务费用：不采购付费 CDN、不升级带宽、不迁移至国内付费节点。
- 维持单前端工程双入口与纯静态容器交付形态；不改动 `compose.yaml` 卷挂载、不改后端 SQL/JVM 业务逻辑、不重压或改名后端 26 张种子图（`backend/uploads/seed/*.jpg`）。
- 不扩大 `AGENTS.md` 规矩三锁定的 95% 覆盖率门禁对象（后端仍仅卡 `SlowSqlInterceptor`，前端仍仅卡 `tools/style-guard/index.mjs` 与 `src/utils/tableDensity.js`）。
- 品牌文案继续从 `frontend/src/brand.js` 单点取值；预热清单与预压缩产物均在构建期自动生成，不手写哈希文件名。

## 核心决定

### 1. Element Plus 按需装配、样式层锁定与图标/弹窗收口

- **模板按需编译**：`devDependencies` 引入 `unplugin-vue-components`，在 `frontend/vite.config.js` 注册 `ElementPlusResolver({ importStyle: 'css', directives: true })`。明确**不引入** `unplugin-auto-import`，避免与 `frontend/eslint.config.js` 的 `no-undef` 校验以及 CI 中 `lint` 先于 `build` 的顺序冲突。全仓 51 个 `.vue` 保持 kebab-case 标签写法以满足 `style-guard` 静态解析，`views/admin/ConsultRoom.vue` 的 `<ElDrawer>` 统一改为 `<el-drawer>` 交由 resolver 按需打包。
- **CSS `@layer` 锁定样式优先级**：`vite.config.js` 新增插件 `elementPlusLayer()`，将 `node_modules/element-plus/theme-chalk/*.css`（跳过 `?raw`/`?url`）外包一层 `@layer element-plus { ... }`。全量静态核查 121 个 `theme-chalk` CSS 文件均不含 `@charset`、`@import`、`@layer`，且与 `main.css` 的 5 处覆写目标无 `!important` 冲突；`main.css`、`admin.css`（78 行约 `1.5 KB` gzip，保留在入口以维持 `/admin/login` 按钮对比度）与 `<style scoped>` 保持未分层（unlayered），按 CSS Cascade Layers 规则优先级高于 `@layer element-plus`，不受异步路由 chunk 注入顺序影响。
- **首屏关键路径去 Element Plus / Axios 化与登录路由直入首包**：首阶段按需拆包后入口仍含 `108 KB` gzip 的 Element Plus 运行时/图标桶及 `17 KB` gzip 的 `axios`，且 `/login`、`/admin/login` 存在二次异步 chunk 瀑布，晚高峰跨海弱网（`8–10 KB/s`）下冷启动仍需 16–20 秒。二阶段将首屏直达路由（`/`、`/login`、`/admin/login` 及其直连组件 `ClientLayout.vue`、`Home.vue`、`DoctorCard.vue`、`LoadFailure.vue`、`LoginForm.vue`）全部替换为原生 HTML 控件 + `src/styles/entry-el.css`（置于 `@layer element-plus` 的同名基础类）+ `src/components/EntryIcon.vue`（11 个首屏内联 SVG 路径）+ `src/utils/toast.js`（原生 `.el-message` 提示条）；`src/api/http.js` 改用原生 `fetch` + `AbortController` 替代 `axios`；`vite.config.js` 在构建与 dev 预构建阶段将 `element-plus/es/locale/lang/en.mjs` 直接映射为 `zh-cn.mjs`，使 `App.vue` 无需挂载 `<el-config-provider>` 即可维持全站懒加载组件中文本地化；`router/index.js` 将 `/login` 与 `/admin/login` 静态并入首包，消除从 `/demo` 或直达登录页时的二次 JS/CSS 瀑布。首屏入口关键路径总体积由 `160.84 KB` gzip 进一步压降至 `75.50 KB` gzip（JS `66.52 KB` + CSS `8.98 KB`）。
- **其余懒加载路由图标显式导入与 `ElMessageBox` 收口**：非首屏 `.vue` 实际使用的 `@element-plus/icons-vue` 图标在各自组件中具名导入；非首屏 15 处 `ElMessageBox` 统一经 `frontend/src/utils/messageBox.js` 导入（内含 `message-box/style/css` 及 `confirm`/`prompt`/`alert` 中文默认按钮文案 `确定`/`取消`），将其 JS 与 CSS 隔离出首屏包。
- **剥离非首屏重模块**：`App.vue` 改用 `defineAsyncComponent` + `v-if="imageViewer.open"` 按需挂载 `ImageViewerHost`（`ImageViewerHost.vue` 的 `watch` 补 `{ immediate: true }` 保留语义 `alt`）；`views/admin/Dashboard.vue` 按需动态加载 `echarts/core`。

### 2. 首屏非交互加载壳、`/` 并发抢跑与登录页主图替换

- **≤ 14 KB gzip 非交互加载壳**：`frontend/index.html` 在 `<div id="app">` 内放置内联 `<style>` 与纯静态品牌加载壳，品牌名与副标题通过 `brandHtml()` 替换 `%BRAND_NAME%`/`%BRAND_TAGLINE%`（取自 `src/brand.js`）。硬约束：壳内不含 `<h1>`（避免提前触发截图清单的 `waitFor`）、不含假按钮或假输入框、不含用户端底部导航栏（避免 `/admin/*` 闪现），并遵守 `@media (prefers-reduced-motion: reduce)`；整份构建后 `dist/index.html` 预算 gzip ≤ `14 KB`（落入单次 TCP 初始拥塞窗口 IW10）。
- **消除 `/` 串行瀑布与全页遮罩**：`router/index.js` 静态导入 `ClientLayout` 与 `Home`（其余路由保持动态 `import()`），减少直达 `/` 时的二次路由 chunk 跨海 RTT。`index.html` `<head>` 末尾注入极小内联脚本（未压缩 < 350 B），仅当 `location.pathname === '/'` 时并发请求公开接口 `GET /api/client/catalog/home`（`credentials: 'same-origin'`）并将 `{ ts, promise }` 存入 `window.__YUZHUA_HOME_PREFETCH__`（任意异常或非 200 业务码静默归约为 `null`，避免 `unhandledrejection`）。`Home.vue` 在首次挂载且 30 秒内优先消费该预拉结果，并移除根节点全页 `v-loading` 遮罩，改为科室/医生/公告区块在加载中显示固定高度静态骨架占位，使搜索框与快捷入口挂载后立即可操作。
- **登录页团团主图替换**：离线将 `570 KB` `tuantuan-a-final.png`（`1774×887`）转为精确 `2:1`（`1020×510`，超 `38 KB` 时降档 `960×480`）带 Alpha 通道的 `frontend/src/assets/tuantuan.webp`（设计预算 ≤ `40 KB`）并删除原 PNG；`LoginScene.vue` 同步更新资源路径与 `width`/`height`，不引入 `sharp`。

### 3. `/demo` 空窗预热与 `?demo=*` 表单预填

- **构建期最小清单 + 通用 CORS `fetch` 预热**：`vite.config.js` 开启 `build.manifest: true`，`frontend/tools/postbuild-assets.mjs` 从 `dist/.vite/manifest.json` 仅沿 `index.html` 入口节点递归提取静态 `file`、`css`、`imports`（严格排除 `dynamicImports`）输出 `dist/demo-preload.json`。`deploy/demo/index.html` 仅在 `window.load` 完成后的空闲期（`requestIdleCallback` 降级 `setTimeout(800)`）且未开启省流量/非 `slow-2g` 时读取该清单，以并发 ≤ 2 发起同源 `fetch(url, { method: 'GET', mode: 'cors', credentials: 'same-origin', cache: 'default' })` 并消费 `arrayBuffer()` 写入 HTTP 磁盘缓存，与 Vite 模块/样式标签的 `crossorigin` 缓存键一致，兼容 Chrome、iOS Safari 与微信 WKWebView，全程静默容错，不预热后台也不触发写请求。
- **`?demo=*` 表单预填与口令隔离**：新增 `frontend/src/utils/demoPreset.js` 解析查询参数并按端白名单隔离（`client` 仅 `user → zhangsan`；`admin` 仅 `doctor → doctor01`、`admin → admin`），通过 `LoginForm.vue` 的 `initialUsername`/`initialPassword` 仅做初始预填，用户可随时覆盖输入，**不自动提交登录请求、不覆写已有登录态**。公网演示口令不写入 Git 源码，经 GitHub Repository Variable `DEMO_PASSWORD` → `.github/workflows/deploy.yml` `build-args` → `deploy/web/Dockerfile` `ARG VITE_DEMO_PASSWORD` 注入；未配置时安全降级为仅预填账号、密码框留空。

### 4. 容器静态预压缩、分层缓存、chunk 404 自愈与四重门禁

- **容器预压缩自治**：`postbuild-assets.mjs` 使用内置 `node:zlib`（`level: 9`）为 `dist/` 及 Dockerfile 中暂存的 `deploy/demo/` 文本资产（≥ 256 B 且压缩更小）生成同名 `.gz`，零新增 Alpine 包依赖。`deploy/nginx/default.conf` 开启 `gzip_static on`、`gzip on`、`gzip_vary on`、`gzip_http_version 1.0`（兜底外层 HTTP/1.0 回源）、`gzip_proxied any`，并新增 `location /.vite/ { return 404; }` 避免暴露完整构建 manifest。
- **双端分层 `Cache-Control`**：
  - 容器 `default.conf`：`/assets/` 升级为 `public, max-age=31536000, immutable`；`/index.html`、`/demo-preload.json` 与 `/demo/` 统一设为 `no-cache`（`/demo/` 由 `no-store` 改为 `no-cache`，恢复 `bfcache` 与 ETag 304）；`/demo/agent-assets/` 设为 `public, max-age=604800`。
  - 后端 `WebMvcConfig`：按路径特异度先注册 `/uploads/seed/**`（`public, max-age=86400`，不加 `immutable`，避免种子图更新被缓存一年），再注册 `/uploads/**`（`FileStorageService` 生成的不可变 UUID 文件名，`public, max-age=31536000, immutable`）。
- **发版 chunk 404 单次自愈**：`frontend/src/main.js` 监听 `vite:preloadError`，用 `sessionStorage` 键 `yuzhua_preload_reload_at` 做 30 秒冷却窗口保护（读写包 `try/catch` 兼容隐私模式），首次触发阻止默认报错并执行单次 `location.reload()`，冷却期内复现则放行错误以防无限刷新。
- **四重防回退门禁**：
  1. `frontend/eslint.config.js` 的 `no-restricted-imports` 阻断全量导入 `element-plus` / `@element-plus/icons-vue`、导入 `element-plus/dist/index.css`、业务文件直接从 `element-plus` 导入 `ElMessageBox`，并严禁首屏关键路径 16 个文件导入 `element-plus`、`@element-plus/icons-vue` 或 `axios`。
  2. `frontend/tools/bundle-budget.mjs` 接入 `npm run verify` 与 `.github/workflows/ci.yml`，卡住四项预算阈值：`dist/index.html` ≤ `14 KB` gz、入口关键路径 JS+CSS ≤ `85 KB` gz（实测 `75.50 KB`，且不得包含 `echarts`）、`dist/assets/` 单图 ≤ `50 KB`、`dist/demo-preload.json` 有效且关键文本资产存在 `.gz`。
  3. 合并前对着生产构建容器执行 `tools/screenshots.mjs` 与 `tools/main_flow_browser.mjs` 回归，校验样式、中文弹窗与核心主流程。
  4. `.github/workflows/deploy.yml` 冒烟循环补充校验线上 `/`（`200`、`gzip`、`no-cache`）、`/demo-preload.json`（`200`）与提取出的入口 `/assets/index-*.js`（`200`、`gzip`、`immutable`）。

## 否决的替代方案

- **容器加装 `nginx-mod-http-brotli` 或宿主机手工编译 Brotli**：文本资产较 gzip 约再省 15%–20%（约 `15–25 KB`），但增加非原生 Alpine 动态模块维护风险，宿主机手工编译又脱离 Git 与 CI/CD 原子回滚，且仍依赖外层透传 `Accept-Encoding: br`。选用原生 `nginx:1.28-alpine` 的 `gzip_static`。
- **默认接入 Cloudflare Free 等免费海外 CDN 或公共 JS CDN**：国内移动 5G 易绕行美西导致晚高峰丢包高于直连；边缘 100 秒空闲断连会与 `consultSocket.js` 重连上限冲突，且 `/api/ws/consult?token=` 经第三方边缘转发引入额外风险；公共 JS CDN 破坏按需 tree-shaking 与自包含容器镜像。仅保留为后续独立评估的条件选项。
- **Service Worker / PWA 离线缓存**：对首次冷启动白屏没有帮助，首访注册预缓存反而可能争抢首屏带宽；滚动发版容易因旧清单触发 chunk 404，且在 iOS 微信内置浏览器兼容性不稳定。
- **按端集中注册组件清单（或 51 文件全手工导样式）**：按端集中注册会把 15 个仅在用户端子页使用的重组件带入 `/` 与 `/login` 首包（实查仅源模块已多出约 `108.2 KB` gzip，尚不含 `date-picker` 等传递依赖）；51 个 `.vue` 逐页全手工导入组件与 CSS 维护成本高且难以静态发现隐式子组件漏导。
- **`?demo=*` 自动调用登录接口或经 `sessionStorage` 自动提交**：静态落地页 `/demo` 直接登录会与 SPA 4 个 `localStorage` 键及 `LoginVo` 结构形成隐式跨层耦合，且页面加载或爬虫抓取时可能触发写请求或静默覆写同浏览器已有登录态。
- **仅使用 Speculation Rules API 做 `/demo` 预热 / 引入 `unplugin-auto-import`**：前者在 iOS Safari 与 iOS 微信 WKWebView 不受支持；后者与现有 ESLint `no-undef` 及 CI 先 lint 后 build 流程冲突。

## 已知边界与验证口径

- **宿主机外层 nginx 不在仓库内**：`47.250.169.146` 外层反代是否清空 `Accept-Encoding`、覆写 `Cache-Control` 或下发阻断内联壳的 `CSP`，需通过只读核查确认（步骤见 `docs/部署步骤.md` 7.3 节）；仅在检出阻碍项时独立执行宿主机外层配置对齐。
- **实测基线与设计预算区分**：上文 `468 KB`、`570 KB`、`250 ms RTT`、`38.7 KB/s` 为 2026-11-22 优化前实测基线；`14 KB`、`165 KB`、`40 KB`、`50 KB` 为设计预算与 CI 门禁上限。优化后的实际包体、CDP 锁定基线及真机 5G 白天/晚高峰采样结果按 `AGENTS.md` 规矩二归档至 `docs/test-evidence/<YYYYMMDD>-cold-start/`；晚高峰跨境丢包（`10–20 KB/s`）下冷启动仍受物理吞吐制约，不承诺全时段固定秒数。
- **落地页提示文案更新时机**：`deploy/demo/index.html` 中「首次加载较慢」提示（`#slow-note`）待 Phase 1 线上实测达标后再拆分更新，不在优化上线前提前改动。

代码依据：`docs/designs/frontend-cold-start-optimization.md`、`frontend/vite.config.js`、`frontend/eslint.config.js`、`frontend/index.html`、`frontend/src/main.js`、`frontend/src/App.vue`、`frontend/src/router/index.js`、`frontend/src/utils/messageBox.js`、`frontend/src/utils/demoPreset.js`、`frontend/tools/postbuild-assets.mjs`、`frontend/tools/bundle-budget.mjs`、`deploy/demo/index.html`、`deploy/web/Dockerfile`、`deploy/nginx/default.conf`、`.github/workflows/ci.yml`、`.github/workflows/deploy.yml`、`backend/src/main/java/com/yuzhua/config/WebMvcConfig.java`；配套测试见 `frontend/tests/demo-preset.test.mjs`、`frontend/tests/bundle-budget.test.mjs`、`WebMvcConfigCacheControlTest`。
