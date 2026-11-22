#!/usr/bin/env node
/**
 * 构建后静态资产处理脚本（纯 Node，零外部依赖）：
 *   1. 默认模式：读取 dist/.vite/manifest.json，从 index.html 入口递归提取静态
 *      file / css / imports（严格排除 dynamicImports），写入 dist/demo-preload.json；
 *      再为 dist/ 下所有可压缩文本文件（>= 256 B 且 gzip 体积严格更小）生成同名 .gz。
 *   2. --gzip-only <dir> 模式：跳过 manifest 解析，仅对指定目录预生成 .gz 文件
 *      （供 deploy/web/Dockerfile 预压缩 /demo/ 静态落地页使用）。
 */
import { existsSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { extname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { gzipSync } from 'node:zlib'

export const DEFAULT_DIST_DIR = fileURLToPath(new URL('../dist', import.meta.url))
export const MIN_GZIP_BYTES = 256
export const GZIP_EXTENSIONS = new Set(['.js', '.css', '.html', '.svg', '.json'])

/**
 * 从 Vite manifest 收集 index.html 入口的最小静态资产 URL 列表。
 * 仅遍历入口自身的 file / css 与静态 imports，绝不跟随 dynamicImports。
 *
 * @param {Record<string, { file?: string, css?: string[], imports?: string[], isEntry?: boolean }>} manifest
 * @returns {string[]}
 */
export function collectEntryAssets(manifest) {
  if (!manifest || typeof manifest !== 'object') {
    throw new Error('无效的 Vite manifest 内容')
  }
  const entryKey = Object.prototype.hasOwnProperty.call(manifest, 'index.html')
    ? 'index.html'
    : Object.keys(manifest).find((key) => manifest[key]?.isEntry)
  if (!entryKey || !manifest[entryKey]) {
    throw new Error('manifest.json 中未找到 index.html 入口节点')
  }

  const visited = new Set()
  const seenUrls = new Set()
  const assets = []

  const pushAsset = (relPath) => {
    if (typeof relPath !== 'string' || !relPath) return
    const normalized = relPath.startsWith('/') ? relPath : `/${relPath.replace(/^\.?\//, '')}`
    if (!seenUrls.has(normalized)) {
      seenUrls.add(normalized)
      assets.push(normalized)
    }
  }

  const visit = (key) => {
    if (visited.has(key)) return
    visited.add(key)
    const node = manifest[key]
    if (!node) return
    if (node.file) pushAsset(node.file)
    if (Array.isArray(node.css)) {
      for (const cssPath of node.css) pushAsset(cssPath)
    }
    if (Array.isArray(node.imports)) {
      for (const depKey of node.imports) visit(depKey)
    }
  }

  visit(entryKey)
  return assets
}

/**
 * 根据 dist/.vite/manifest.json 生成 dist/demo-preload.json。
 *
 * @param {string} distDir
 * @returns {{ manifestPath: string, outputPath: string, assets: string[] }}
 */
export function writeDemoPreloadManifest(distDir) {
  const manifestPath = join(distDir, '.vite', 'manifest.json')
  if (!existsSync(manifestPath)) {
    throw new Error(`未找到 Vite manifest：${manifestPath}（请确认 vite.config.js 已开启 build.manifest: true）`)
  }
  const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'))
  const assets = collectEntryAssets(manifest)
  if (!assets.length) {
    throw new Error('index.html 入口未解析出任何静态资产，无法生成 demo-preload.json')
  }
  const outputPath = join(distDir, 'demo-preload.json')
  const payload = `${JSON.stringify({ version: 1, assets }, null, 2)}\n`
  writeFileSync(outputPath, payload, 'utf8')
  return { manifestPath, outputPath, assets }
}

/**
 * 递归为目录内所有符合条件的文本资产预生成 level 9 的同名 .gz 文件。
 *
 * @param {string} targetDir
 * @param {{ minBytes?: number }} [options]
 * @returns {{ scanned: number, written: number, skipped: number }}
 */
export function gzipDirectory(targetDir, options = {}) {
  const minBytes = options.minBytes ?? MIN_GZIP_BYTES
  if (!existsSync(targetDir) || !statSync(targetDir).isDirectory()) {
    throw new Error(`预压缩目标目录不存在或不是目录：${targetDir}`)
  }

  let scanned = 0
  let written = 0
  let skipped = 0

  const walk = (dir) => {
    for (const entry of readdirSync(dir)) {
      if (entry === '.vite') continue
      const fullPath = join(dir, entry)
      const st = statSync(fullPath)
      if (st.isDirectory()) {
        walk(fullPath)
        continue
      }
      const ext = extname(entry).toLowerCase()
      if (!GZIP_EXTENSIONS.has(ext)) continue

      scanned++
      const raw = readFileSync(fullPath)
      const gzPath = `${fullPath}.gz`
      if (raw.length < minBytes) {
        if (existsSync(gzPath)) rmSync(gzPath, { force: true })
        skipped++
        continue
      }
      const compressed = gzipSync(raw, { level: 9 })
      if (compressed.length >= raw.length) {
        if (existsSync(gzPath)) rmSync(gzPath, { force: true })
        skipped++
        continue
      }
      writeFileSync(gzPath, compressed)
      written++
    }
  }

  walk(targetDir)
  return { scanned, written, skipped }
}

export function runPostbuild(argv = process.argv.slice(2)) {
  const gzipOnlyIndex = argv.indexOf('--gzip-only')
  if (gzipOnlyIndex >= 0) {
    const rawTarget = argv[gzipOnlyIndex + 1]
    if (!rawTarget) {
      throw new Error('用法：node tools/postbuild-assets.mjs --gzip-only <dir>')
    }
    const targetDir = resolve(process.cwd(), rawTarget)
    const stats = gzipDirectory(targetDir)
    console.log(`[postbuild-assets] gzip-only ${targetDir}: 生成 ${stats.written} 个 .gz（扫描 ${stats.scanned}，跳过 ${stats.skipped}）`)
    return
  }

  const distDir = argv[0] ? resolve(process.cwd(), argv[0]) : DEFAULT_DIST_DIR
  const { assets } = writeDemoPreloadManifest(distDir)
  const stats = gzipDirectory(distDir)
  console.log(
    `[postbuild-assets] demo-preload.json 写入 ${assets.length} 个入口资产；生成 ${stats.written} 个 .gz（扫描 ${stats.scanned}，跳过 ${stats.skipped}）`
  )
}

const isMain = process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)
if (isMain) {
  try {
    runPostbuild()
  } catch (err) {
    console.error(`[postbuild-assets] 失败：${err.message}`)
    process.exit(1)
  }
}
