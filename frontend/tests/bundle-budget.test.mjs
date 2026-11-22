/**
 * 首屏包体预算门禁与构建后处理脚本（bundle-budget.mjs / postbuild-assets.mjs）单测：
 * 使用临时目录构造可控 dist 夹具，验证通过基线与每条核心拦截条件不会静默放行。
 */
import assert from 'node:assert/strict'
import { randomBytes } from 'node:crypto'
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { test } from 'node:test'

import {
  ENTRY_GZIP_MAX,
  HTML_GZIP_MAX,
  IMAGE_MAX,
  parseEntryAssetsFromHtml,
  validateBundleBudget
} from '../tools/bundle-budget.mjs'
import { gzipDirectory, writeDemoPreloadManifest } from '../tools/postbuild-assets.mjs'

function withTempDir(fn) {
  const dir = mkdtempSync(join(tmpdir(), 'yuzhua-budget-test-'))
  try {
    return fn(dir)
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
}

/** 构造一份完整的最小合规 dist 目录，并跑真实 postbuild 生成 demo-preload.json 与 .gz。 */
function createValidFixture(distDir, overrides = {}) {
  const assetsDir = join(distDir, 'assets')
  const viteDir = join(distDir, '.vite')
  mkdirSync(assetsDir, { recursive: true })
  mkdirSync(viteDir, { recursive: true })

  const indexJsName = overrides.indexJsName ?? 'assets/index-abc123.js'
  const vendorJsName = overrides.vendorJsName ?? 'assets/vendor-def456.js'
  const indexCssName = overrides.indexCssName ?? 'assets/index-ghi789.css'
  const lazyChartName = 'assets/echarts-lazy-000.js'

  const html = overrides.html ?? `<!DOCTYPE html>
<html lang="zh-CN">
  <head>
    <meta charset="UTF-8" />
    <link rel="icon" type="image/svg+xml" href="/assets/logo.svg" />
    <script type="module" crossorigin src="/${indexJsName}"></script>
    <link rel="modulepreload" crossorigin href="/${vendorJsName}">
    <link rel="stylesheet" crossorigin href="/${indexCssName}">
  </head>
  <body>
    <div id="app">${'<!-- shell-padding -->'.repeat(20)}</div>
  </body>
</html>`

  writeFileSync(join(distDir, 'index.html'), html, 'utf8')
  writeFileSync(
    join(distDir, indexJsName),
    overrides.indexJsContent ?? `console.log("yuzhua-app-entry");\n${'// entry-code-line\n'.repeat(30)}`,
    'utf8'
  )
  writeFileSync(
    join(distDir, vendorJsName),
    overrides.vendorJsContent ?? `export const runtime = () => "ok";\n${'// vendor-code-line\n'.repeat(30)}`,
    'utf8'
  )
  writeFileSync(
    join(distDir, indexCssName),
    overrides.indexCssContent ?? `body{margin:0;background:#f7f7f7}\n${'.card{padding:16px}\n'.repeat(30)}`,
    'utf8'
  )
  // 动态懒加载 chunk（含 echarts），用于验证不会被 demo-preload.json 收集也不会误报入口违规
  writeFileSync(
    join(distDir, lazyChartName),
    `export const initChart = () => window.__ECHARTS__DEFAULT__RENDERER__;\n${'// chart\n'.repeat(30)}`,
    'utf8'
  )
  writeFileSync(join(assetsDir, 'tuantuan.webp'), overrides.imageBuffer ?? Buffer.alloc(18 * 1024, 0x42))

  const manifest = {
    'index.html': {
      file: indexJsName,
      src: 'index.html',
      isEntry: true,
      imports: ['_vendor.js'],
      dynamicImports: ['src/views/admin/Dashboard.vue'],
      css: [indexCssName]
    },
    '_vendor.js': {
      file: vendorJsName
    },
    'src/views/admin/Dashboard.vue': {
      file: lazyChartName,
      isDynamicEntry: true
    }
  }
  writeFileSync(join(viteDir, 'manifest.json'), JSON.stringify(manifest, null, 2), 'utf8')

  writeDemoPreloadManifest(distDir)
  gzipDirectory(distDir)
}

test('parseEntryAssetsFromHtml 提取入口脚本/样式/modulepreload 并忽略外链与普通资源', () => {
  const html = `
    <link rel="icon" href="/favicon.svg">
    <link rel="stylesheet" href="https://cdn.example.com/ext.css">
    <link crossorigin href="/assets/index-1.css?v=1" rel="stylesheet">
    <link rel="modulepreload" crossorigin href="/assets/vendor-1.js">
    <script src="/assets/legacy.js"></script>
    <script crossorigin src="/assets/index-1.js" type="module"></script>
    <link rel="modulepreload" href="/assets/vendor-1.js">
  `
  assert.deepEqual(parseEntryAssetsFromHtml(html), [
    '/assets/index-1.js',
    '/assets/index-1.css',
    '/assets/vendor-1.js'
  ])
})

test('合规 dist 产物通过全部门禁，且 demo-preload.json 严格排除 dynamicImports', () => {
  withTempDir((distDir) => {
    createValidFixture(distDir)

    const preload = JSON.parse(readFileSync(join(distDir, 'demo-preload.json'), 'utf8'))
    assert.equal(preload.version, 1)
    assert.deepEqual(preload.assets, [
      '/assets/index-abc123.js',
      '/assets/index-ghi789.css',
      '/assets/vendor-def456.js'
    ])
    assert.ok(!preload.assets.some((item) => item.includes('echarts')))
    assert.equal(existsSync(join(distDir, '.vite', 'manifest.json.gz')), false)

    const result = validateBundleBudget(distDir)
    assert.equal(result.ok, true, JSON.stringify(result.violations))
    assert.equal(result.violations.length, 0)
    assert.equal(result.summary.entryAssets.length, 3)
  })
})

test('gzipDirectory（--gzip-only 模式）为 >=256B 可压缩文本生成 .gz 并跳过过小文件', () => {
  withTempDir((demoDir) => {
    writeFileSync(join(demoDir, 'index.html'), `<!doctype html><body>${'愈爪演示页'.repeat(60)}</body>`, 'utf8')
    writeFileSync(join(demoDir, 'tiny.json'), '{"ok":true}', 'utf8')
    writeFileSync(join(demoDir, 'pic.webp'), Buffer.alloc(4096, 0x11))

    const stats = gzipDirectory(demoDir)
    assert.equal(stats.written, 1)
    assert.equal(stats.skipped, 1)
    assert.equal(existsSync(join(demoDir, 'index.html.gz')), true)
    assert.equal(existsSync(join(demoDir, 'tiny.json.gz')), false)
    assert.equal(existsSync(join(demoDir, 'pic.webp.gz')), false)
  })
})

test('index.html gzip 超过 14 KB 时拦截失败', () => {
  withTempDir((distDir) => {
    const incompressible = randomBytes(HTML_GZIP_MAX + 1024).toString('base64')
    createValidFixture(distDir, {
      html: `<!DOCTYPE html><html><head><script type="module" src="/assets/index-abc123.js"></script></head><body><!-- ${incompressible} --></body></html>`
    })

    const result = validateBundleBudget(distDir)
    assert.equal(result.ok, false)
    assert.ok(result.violations.some((v) => v.rule === 'html-gzip-budget'))
  })
})

test('首屏入口关键路径总 gzip 超过 165 KB 时拦截失败', () => {
  withTempDir((distDir) => {
    // 不可压缩的随机数据（base64 编码后 gzip 仍约原字节 75%，240 KB raw -> ~180 KB gz > 165 KB）
    const blob = randomBytes(Math.ceil(ENTRY_GZIP_MAX * 1.15)).toString('base64')
    createValidFixture(distDir, {
      indexJsContent: `export const payload = "${blob}";`
    })

    const result = validateBundleBudget(distDir)
    assert.equal(result.ok, false)
    assert.ok(result.violations.some((v) => v.rule === 'entry-gzip-budget'))
  })
})

test('入口关键路径 chunk 文件名或内容包含 echarts 时拦截失败', () => {
  withTempDir((distDir) => {
    createValidFixture(distDir, {
      indexJsContent: `const renderer = window.__ECHARTS__DEFAULT__RENDERER__;\n${'// pad\n'.repeat(40)}`
    })
    const byContent = validateBundleBudget(distDir)
    assert.equal(byContent.ok, false)
    assert.ok(byContent.violations.some((v) => v.rule === 'echarts-in-entry'))
  })

  withTempDir((distDir) => {
    createValidFixture(distDir, {
      vendorJsName: 'assets/echarts-core-123.js'
    })
    const byPath = validateBundleBudget(distDir)
    assert.equal(byPath.ok, false)
    assert.ok(byPath.violations.some((v) => v.rule === 'echarts-in-entry'))
  })
})

test('dist/assets/ 单张图片超过 50 KB 时拦截失败', () => {
  withTempDir((distDir) => {
    createValidFixture(distDir, {
      imageBuffer: Buffer.alloc(IMAGE_MAX + 1, 0x33)
    })

    const result = validateBundleBudget(distDir)
    assert.equal(result.ok, false)
    assert.ok(result.violations.some((v) => v.rule === 'image-size-budget'))
  })
})

test('关键文本资产缺失同名 .gz 时拦截失败', () => {
  withTempDir((distDir) => {
    createValidFixture(distDir)
    rmSync(join(distDir, 'assets', 'index-abc123.js.gz'))

    const result = validateBundleBudget(distDir)
    assert.equal(result.ok, false)
    assert.ok(result.violations.some((v) => v.rule === 'missing-gzip' && v.asset === '/assets/index-abc123.js'))
  })
})

test('demo-preload.json 缺失或指向不存在资产时拦截失败', () => {
  withTempDir((distDir) => {
    createValidFixture(distDir)
    rmSync(join(distDir, 'demo-preload.json'))

    const missingFile = validateBundleBudget(distDir)
    assert.equal(missingFile.ok, false)
    assert.ok(missingFile.violations.some((v) => v.rule === 'missing-demo-preload'))
  })

  withTempDir((distDir) => {
    createValidFixture(distDir)
    writeFileSync(
      join(distDir, 'demo-preload.json'),
      JSON.stringify({ version: 1, assets: ['/assets/does-not-exist.js'] }, null, 2),
      'utf8'
    )

    const dangling = validateBundleBudget(distDir)
    assert.equal(dangling.ok, false)
    assert.ok(dangling.violations.some((v) => v.rule === 'missing-demo-preload-asset'))
  })
})
