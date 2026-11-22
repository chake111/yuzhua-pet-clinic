#!/usr/bin/env node
/**
 * 首屏包体、图片体积与静态预压缩门禁脚本（纯 Node，零外部依赖）。
 *
 * 校验项：
 *   1. dist/index.html gzip 体积 <= HTML_GZIP_MAX（默认 14 KB）；
 *   2. 首屏入口关键路径（<script type="module">、<link rel="stylesheet">、
 *      <link rel="modulepreload">）gzip 总字节 <= ENTRY_GZIP_MAX（默认 85 KB）；
 *   3. 关键路径 chunk 路径与内容均不得包含 echarts；
 *   4. dist/assets/ 下所有 .png/.jpg/.jpeg/.webp 单图 <= IMAGE_MAX（默认 50 KB）；
 *   5. dist/demo-preload.json 存在、非空、所列路径均真实存在于 dist/ 且不含 echarts；
 *   6. 所有符合预压缩条件（>= 256 B 且 gzip 严格更小）的关键文本资产与 demo-preload.json
 *      均具备同名 .gz 文件。
 *
 * 纯校验函数与 CLI 分离，方便单测直接构造临时 dist 目录验证每条不变量。
 */
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs'
import { extname, join, relative, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'
import { gzipSync } from 'node:zlib'

export const DEFAULT_DIST_DIR = fileURLToPath(new URL('../dist', import.meta.url))
export const HTML_GZIP_MAX = 14 * 1024
export const ENTRY_GZIP_MAX = 85 * 1024
export const IMAGE_MAX = 50 * 1024
export const MIN_GZIP_BYTES = 256

const IMAGE_EXTENSIONS = new Set(['.png', '.jpg', '.jpeg', '.webp'])
const TEXT_EXTENSIONS = new Set(['.js', '.mjs', '.css', '.html', '.svg', '.json'])
const EXTERNAL_URL_RE = /^(?:https?:)?\/\/|^data:/i
const ECHARTS_RE = /echarts/i

function parseTagAttributes(attrText) {
  const attrs = {}
  const attrRe = /([a-zA-Z_:][-a-zA-Z0-9_:.]*)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+)))?/g
  let match
  while ((match = attrRe.exec(attrText))) {
    attrs[match[1].toLowerCase()] = match[2] ?? match[3] ?? match[4] ?? ''
  }
  return attrs
}

function normalizeAssetUrl(rawUrl) {
  if (typeof rawUrl !== 'string') return null
  const trimmed = rawUrl.trim()
  if (!trimmed || EXTERNAL_URL_RE.test(trimmed)) return null
  const withoutQuery = trimmed.split(/[?#]/)[0]
  if (!withoutQuery) return null
  return withoutQuery.startsWith('/') ? withoutQuery : `/${withoutQuery.replace(/^\.?\//, '')}`
}

/**
 * 从构建产物 index.html 中提取首屏关键路径资产 URL：
 * <script type="module" src="...">、<link rel="stylesheet" href="...">、
 * <link rel="modulepreload" href="...">。
 *
 * @param {string} html
 * @returns {string[]}
 */
export function parseEntryAssetsFromHtml(html) {
  const urls = []
  const seen = new Set()
  const add = (raw) => {
    const normalized = normalizeAssetUrl(raw)
    if (normalized && !seen.has(normalized)) {
      seen.add(normalized)
      urls.push(normalized)
    }
  }

  const scriptRe = /<script\b([^>]*)>/gi
  let match
  while ((match = scriptRe.exec(html))) {
    const attrs = parseTagAttributes(match[1])
    if ((attrs.type || '').toLowerCase() === 'module' && attrs.src) {
      add(attrs.src)
    }
  }

  const linkRe = /<link\b([^>]*)>/gi
  while ((match = linkRe.exec(html))) {
    const attrs = parseTagAttributes(match[1])
    const relTokens = (attrs.rel || '').toLowerCase().split(/\s+/).filter(Boolean)
    if ((relTokens.includes('stylesheet') || relTokens.includes('modulepreload')) && attrs.href) {
      add(attrs.href)
    }
  }

  return urls
}

function resolveInsideDist(distDir, assetUrl) {
  const rel = assetUrl.replace(/^\/+/, '')
  const root = resolve(distDir)
  const full = resolve(root, rel)
  if (full !== root && !full.startsWith(`${root}${sep}`)) {
    return null
  }
  return full
}

function measureAsset(fullPath) {
  const raw = readFileSync(fullPath)
  const gzPath = `${fullPath}.gz`
  const hasGz = existsSync(gzPath) && statSync(gzPath).isFile()
  const computedGzBytes = gzipSync(raw, { level: 9 }).length
  const gzipBytes = hasGz ? statSync(gzPath).size : computedGzBytes
  return { raw, rawBytes: raw.length, gzipBytes, computedGzBytes, hasGz, gzPath }
}

function checkSiblingGzip({ fullPath, label, measured, minGzipBytes, violations, checkedPaths }) {
  if (checkedPaths.has(fullPath)) return
  checkedPaths.add(fullPath)
  const ext = extname(fullPath).toLowerCase()
  if (!TEXT_EXTENSIONS.has(ext)) return
  if (measured.rawBytes >= minGzipBytes && measured.computedGzBytes < measured.rawBytes && !measured.hasGz) {
    violations.push({
      rule: 'missing-gzip',
      asset: label,
      message: `缺少预压缩文件 ${label}.gz（原始 ${measured.rawBytes} B，gzip 约 ${measured.computedGzBytes} B）`
    })
  }
}

function collectImages(assetsDir, distDir) {
  const images = []
  if (!existsSync(assetsDir) || !statSync(assetsDir).isDirectory()) return images
  const walk = (dir) => {
    for (const entry of readdirSync(dir)) {
      const fullPath = join(dir, entry)
      const st = statSync(fullPath)
      if (st.isDirectory()) {
        walk(fullPath)
        continue
      }
      if (IMAGE_EXTENSIONS.has(extname(entry).toLowerCase())) {
        images.push({
          path: `/${relative(distDir, fullPath).replace(/\\/g, '/')}`,
          bytes: st.size
        })
      }
    }
  }
  walk(assetsDir)
  return images
}

/**
 * 校验构建目录是否满足全部首屏包体、图片与预压缩预算。
 *
 * @param {string} distDir
 * @param {{ htmlGzipMax?: number, entryGzipMax?: number, imageMax?: number, minGzipBytes?: number }} [options]
 */
export function validateBundleBudget(distDir, options = {}) {
  const htmlGzipMax = options.htmlGzipMax ?? HTML_GZIP_MAX
  const entryGzipMax = options.entryGzipMax ?? ENTRY_GZIP_MAX
  const imageMax = options.imageMax ?? IMAGE_MAX
  const minGzipBytes = options.minGzipBytes ?? MIN_GZIP_BYTES

  const violations = []
  const checkedGzipPaths = new Set()
  const summary = {
    html: null,
    entryAssets: [],
    entryGzipTotal: 0,
    entryGzipMax,
    htmlGzipMax,
    imageMax,
    images: [],
    demoPreloadAssets: []
  }

  // 1. dist/index.html 体积与预压缩
  const htmlPath = join(distDir, 'index.html')
  if (!existsSync(htmlPath)) {
    violations.push({ rule: 'missing-html', message: `未找到构建产物：${htmlPath}` })
    return { ok: false, violations, summary }
  }
  const htmlMeasured = measureAsset(htmlPath)
  const htmlText = htmlMeasured.raw.toString('utf8')
  summary.html = {
    rawBytes: htmlMeasured.rawBytes,
    gzipBytes: htmlMeasured.gzipBytes,
    hasGz: htmlMeasured.hasGz
  }
  if (htmlMeasured.gzipBytes > htmlGzipMax) {
    violations.push({
      rule: 'html-gzip-budget',
      message: `dist/index.html gzip 体积 ${htmlMeasured.gzipBytes} B 超出上限 ${htmlGzipMax} B`
    })
  }
  checkSiblingGzip({
    fullPath: htmlPath,
    label: '/index.html',
    measured: htmlMeasured,
    minGzipBytes,
    violations,
    checkedPaths: checkedGzipPaths
  })

  // 2. 入口关键路径资产总 gzip、echarts 泄漏与预压缩
  const entryUrls = parseEntryAssetsFromHtml(htmlText)
  if (!entryUrls.length) {
    violations.push({
      rule: 'empty-entry-assets',
      message: 'dist/index.html 中未解析到任何入口 <script type="module"> / stylesheet / modulepreload 资产'
    })
  }

  for (const url of entryUrls) {
    const fullPath = resolveInsideDist(distDir, url)
    if (!fullPath || !existsSync(fullPath) || !statSync(fullPath).isFile()) {
      violations.push({
        rule: 'missing-entry-asset',
        asset: url,
        message: `index.html 引用的入口资产不存在：${url}`
      })
      continue
    }
    const measured = measureAsset(fullPath)
    summary.entryAssets.push({
      url,
      rawBytes: measured.rawBytes,
      gzipBytes: measured.gzipBytes,
      hasGz: measured.hasGz
    })
    summary.entryGzipTotal += measured.gzipBytes

    if (ECHARTS_RE.test(url)) {
      violations.push({
        rule: 'echarts-in-entry',
        asset: url,
        message: `入口关键路径文件名包含 echarts：${url}`
      })
    } else if (TEXT_EXTENSIONS.has(extname(fullPath).toLowerCase()) && ECHARTS_RE.test(measured.raw.toString('utf8'))) {
      violations.push({
        rule: 'echarts-in-entry',
        asset: url,
        message: `入口关键路径 chunk 内容检测到 echarts 引用：${url}`
      })
    }

    checkSiblingGzip({
      fullPath,
      label: url,
      measured,
      minGzipBytes,
      violations,
      checkedPaths: checkedGzipPaths
    })
  }

  if (summary.entryGzipTotal > entryGzipMax) {
    violations.push({
      rule: 'entry-gzip-budget',
      message: `首屏入口关键路径 gzip 总计 ${summary.entryGzipTotal} B 超出上限 ${entryGzipMax} B`
    })
  }

  // 3. dist/assets/ 单图体积上限
  const images = collectImages(join(distDir, 'assets'), distDir)
  summary.images = images
  for (const img of images) {
    if (img.bytes > imageMax) {
      violations.push({
        rule: 'image-size-budget',
        asset: img.path,
        message: `图片 ${img.path} 体积 ${img.bytes} B 超出单图上限 ${imageMax} B`
      })
    }
  }

  // 4. dist/demo-preload.json 完整性与预压缩
  const preloadPath = join(distDir, 'demo-preload.json')
  if (!existsSync(preloadPath) || !statSync(preloadPath).isFile()) {
    violations.push({
      rule: 'missing-demo-preload',
      message: '缺少 dist/demo-preload.json（请确认已执行 postbuild-assets.mjs）'
    })
  } else {
    const preloadMeasured = measureAsset(preloadPath)
    checkSiblingGzip({
      fullPath: preloadPath,
      label: '/demo-preload.json',
      measured: preloadMeasured,
      minGzipBytes,
      violations,
      checkedPaths: checkedGzipPaths
    })

    let parsed = null
    try {
      parsed = JSON.parse(preloadMeasured.raw.toString('utf8'))
    } catch (err) {
      violations.push({
        rule: 'invalid-demo-preload',
        message: `dist/demo-preload.json 不是合法 JSON：${err.message}`
      })
    }

    if (parsed) {
      const assets = Array.isArray(parsed.assets) ? parsed.assets : null
      if (!assets || assets.length === 0) {
        violations.push({
          rule: 'empty-demo-preload',
          message: 'dist/demo-preload.json 的 assets 列表为空'
        })
      } else {
        summary.demoPreloadAssets = assets
        for (const item of assets) {
          const normalized = normalizeAssetUrl(item)
          if (!normalized) {
            violations.push({
              rule: 'invalid-demo-preload-entry',
              message: `demo-preload.json 包含无效资产路径：${String(item)}`
            })
            continue
          }
          if (ECHARTS_RE.test(normalized)) {
            violations.push({
              rule: 'echarts-in-preload',
              asset: normalized,
              message: `demo-preload.json 不得包含 echarts 资产：${normalized}`
            })
          }
          const fullPath = resolveInsideDist(distDir, normalized)
          if (!fullPath || !existsSync(fullPath) || !statSync(fullPath).isFile()) {
            violations.push({
              rule: 'missing-demo-preload-asset',
              asset: normalized,
              message: `demo-preload.json 所列资产不存在于 dist/：${normalized}`
            })
            continue
          }
          const measured = measureAsset(fullPath)
          checkSiblingGzip({
            fullPath,
            label: normalized,
            measured,
            minGzipBytes,
            violations,
            checkedPaths: checkedGzipPaths
          })
        }
      }
    }
  }

  return { ok: violations.length === 0, violations, summary }
}

function formatKb(bytes) {
  return `${(bytes / 1024).toFixed(2)} KB (${bytes} B)`
}

export function formatBudgetReport(result) {
  const { summary, violations, ok } = result
  const lines = ['[bundle-budget] 首屏包体与预压缩门禁汇总：']
  if (summary.html) {
    lines.push(
      `  - /index.html: gzip ${formatKb(summary.html.gzipBytes)} / 上限 ${formatKb(summary.htmlGzipMax)}（raw ${summary.html.rawBytes} B，.gz ${summary.html.hasGz ? '有' : '无'}）`
    )
  }
  lines.push(
    `  - 首屏入口关键路径 (${summary.entryAssets.length} 项): gzip ${formatKb(summary.entryGzipTotal)} / 上限 ${formatKb(summary.entryGzipMax)}`
  )
  for (const item of summary.entryAssets) {
    lines.push(
      `      * ${item.url}: gzip ${formatKb(item.gzipBytes)}（raw ${item.rawBytes} B，.gz ${item.hasGz ? '有' : '无'}）`
    )
  }
  if (summary.images.length) {
    lines.push(`  - dist/assets 图片 (${summary.images.length} 张，单图上限 ${formatKb(summary.imageMax)}):`)
    for (const img of summary.images) {
      lines.push(`      * ${img.path}: ${formatKb(img.bytes)}`)
    }
  } else {
    lines.push('  - dist/assets 图片: 0 张')
  }
  lines.push(`  - /demo-preload.json: ${summary.demoPreloadAssets.length} 个预热资产`)

  if (ok) {
    lines.push('[bundle-budget] 门禁通过')
  } else {
    lines.push(`[bundle-budget] 门禁失败（共 ${violations.length} 项违规）：`)
    for (const v of violations) {
      lines.push(`  [FAIL:${v.rule}] ${v.message}`)
    }
  }
  return lines.join('\n')
}

const isMain = process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)
if (isMain) {
  const distDir = process.argv[2] ? resolve(process.cwd(), process.argv[2]) : DEFAULT_DIST_DIR
  const result = validateBundleBudget(distDir)
  console.log(formatBudgetReport(result))
  if (!result.ok) {
    process.exit(1)
  }
}
