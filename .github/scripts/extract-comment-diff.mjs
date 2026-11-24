#!/usr/bin/env node
import { execFileSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { pathToFileURL } from 'node:url'
import { scanCodeLexical, splitVueSfc } from '../../tools/comment-audit-baseline.mjs'

const HIGH_RISK_FE_FILES = new Set([
  'frontend/src/api/http.js',
  'frontend/src/api/http.ts',
  'frontend/src/api/httpError.js',
  'frontend/src/api/httpError.ts',
  'frontend/src/utils/consultSocket.js',
  'frontend/src/utils/consultSocket.ts',
  'frontend/src/utils/idempotencyKey.js',
  'frontend/src/utils/idempotencyKey.ts',
  'frontend/src/utils/token.js',
  'frontend/src/utils/token.ts',
  'frontend/src/stores/session.js',
  'frontend/src/stores/session.ts',
])

const HIGH_RISK_BE_PATH_PARTS = [
  '/security/',
  '/websocket/',
  '/consult/ws/',
  '/task/',
  '/common/TraceIdFilter.java',
  '/common/BatchLookup.java',
  '/config/SlowSqlInterceptor.java',
]

function isSupportedSourceFile(relPath) {
  if (relPath.startsWith('.guardrails/')) return false
  if (relPath.endsWith('/schema.d.ts') || relPath === 'frontend/src/api/schema.d.ts') return false
  if (relPath.startsWith('backend/src/main/java/') && relPath.endsWith('.java')) return true
  if (relPath.startsWith('frontend/src/') && /\.(?:js|ts|vue)$/.test(relPath)) return true
  return false
}

export function parseAddedLineRanges(diffText) {
  const changedByFile = new Map()
  let currentFile = ''
  let currentLine = 0

  for (const line of diffText.split(/\r?\n/)) {
    if (line.startsWith('+++ b/')) {
      currentFile = line.slice(6)
      continue
    }
    if (line.startsWith('+++ /dev/null')) {
      currentFile = ''
      continue
    }
    if (line.startsWith('@@ ')) {
      const m = /\+(\d+)(?:,(\d+))?/.exec(line)
      currentLine = m ? Number(m[1]) : 0
      continue
    }
    if (line.startsWith('+') && !line.startsWith('+++')) {
      if (currentFile && isSupportedSourceFile(currentFile)) {
        if (!changedByFile.has(currentFile)) changedByFile.set(currentFile, new Set())
        changedByFile.get(currentFile).add(currentLine)
      }
      currentLine++
    }
  }
  return changedByFile
}

function scanTemplateHtmlComments(text, baseLine) {
  const results = []
  const re = /<!--([\s\S]*?)-->/g
  let m
  while ((m = re.exec(text)) !== null) {
    const before = text.slice(0, m.index)
    const startLine = baseLine + (before.match(/\n/g) || []).length
    const linesSpan = (m[0].match(/\n/g) || []).length + 1
    results.push({
      kind: 'template_comment',
      startLine,
      endLine: startLine + linesSpan - 1,
      raw: m[0],
      body: m[1],
    })
  }
  return results
}

function extractFileComments(relPath, content) {
  if (relPath.endsWith('.vue')) {
    const sfc = splitVueSfc(content)
    const comments = []
    for (const blk of sfc.scripts) {
      comments.push(...scanCodeLexical(blk.text, blk.startLine).comments)
    }
    for (const tpl of sfc.templates) {
      comments.push(...scanTemplateHtmlComments(tpl.text, tpl.startLine))
    }
    return comments.sort((a, b) => a.startLine - b.startLine)
  }
  return scanCodeLexical(content, 1).comments
}

/**
 * Find approximate function/method line ranges so changes inside high-risk functions
 * also pull in the attached doc/header comment even if > 5 lines away.
 */
function findHighRiskFunctionRanges(relPath, lines) {
  const isHighRiskFile =
    HIGH_RISK_FE_FILES.has(relPath) || HIGH_RISK_BE_PATH_PARTS.some((p) => relPath.includes(p))
  const ranges = []
  const sigRe =
    /(?:@Transactional|@Scheduled|export\s+(?:async\s+)?function\b|public\s+(?:static\s+)?[\w<>\[\], ?]+\s+\w+\s*\(|(?:ForUpdate|tryOccupy|selectByUserAndIdemKey)\s*\()/

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]
    if (!isHighRiskFile && !sigRe.test(line)) continue
    if (!sigRe.test(line) && !/(?:export\s+|public\s+|async\s+\w+\s*\()/.test(line)) continue

    // Scan forward to matching brace
    let start = i + 1
    let braceIdx = i
    while (braceIdx < lines.length && braceIdx < i + 6 && !lines[braceIdx].includes('{')) {
      braceIdx++
    }
    if (braceIdx >= lines.length || !lines[braceIdx].includes('{')) continue

    let depth = 0
    let end = braceIdx + 1
    for (let j = braceIdx; j < lines.length; j++) {
      for (const ch of lines[j]) {
        if (ch === '{') depth++
        else if (ch === '}') depth--
      }
      if (depth <= 0 && j >= braceIdx) {
        end = j + 1
        break
      }
    }
    ranges.push({ start, end })
  }
  return ranges
}

function buildContextExcerpt(lines, startLine, endLine, beforePad = 6, afterPad = 20) {
  const from = Math.max(1, startLine - beforePad)
  const to = Math.min(lines.length, endLine + afterPad)
  const excerptLines = []
  const allowedLines = []
  for (let ln = from; ln <= to; ln++) {
    excerptLines.push(`L${ln}: ${lines[ln - 1]}`)
    allowedLines.push(ln)
  }
  return {
    excerpt: excerptLines.join('\n'),
    allowedLines,
    contextStartLine: from,
    contextEndLine: to,
  }
}

export function extractCommentTargetsFromContent({ relPath, content, changedLinesSet }) {
  if (!isSupportedSourceFile(relPath)) return []
  const lines = content.split(/\r?\n/)
  const comments = extractFileComments(relPath, content)
  if (comments.length === 0) return []

  const changedLines = [...changedLinesSet].sort((a, b) => a - b)
  const highRiskRanges = findHighRiskFunctionRanges(relPath, lines)

  const changedHighRiskStarts = new Set()
  for (const r of highRiskRanges) {
    if (changedLines.some((ln) => ln >= r.start && ln <= r.end)) {
      changedHighRiskStarts.add(r.start)
    }
  }

  const targets = []
  for (const c of comments) {
    let reason = null
    // 1. Comment itself added/modified
    if (changedLines.some((ln) => ln >= c.startLine && ln <= c.endLine)) {
      reason = 'added_or_modified'
    }
    // 2. Adjacent <= 5 lines from changed code
    if (!reason && changedLines.some((ln) => ln >= c.startLine - 5 && ln <= c.endLine + 5)) {
      reason = 'adjacent_to_diff'
    }
    // 3. Attached to modified high-risk function (within 3 lines above its signature)
    if (!reason) {
      for (const fnStart of changedHighRiskStarts) {
        if (c.endLine >= fnStart - 3 && c.startLine <= fnStart) {
          reason = 'high_risk_function'
          break
        }
      }
    }

    if (!reason) continue

    const ctx = buildContextExcerpt(lines, c.startLine, c.endLine)
    targets.push({
      id: `${relPath}:${c.startLine}-${c.endLine}`,
      file: relPath,
      startLine: c.startLine,
      endLine: c.endLine,
      kind: c.kind,
      reason,
      commentText: c.raw,
      codeContext: ctx.excerpt,
      contextStartLine: ctx.contextStartLine,
      contextEndLine: ctx.contextEndLine,
      allowedLines: ctx.allowedLines,
    })
  }

  return targets
}

export function extractCommentDiff({ repoDir = '.', baseSha, headSha = 'HEAD' }) {
  const resolvedRepo = path.resolve(repoDir)
  const diffText = execFileSync(
    'git',
    [
      '-C',
      resolvedRepo,
      '-c',
      'core.hooksPath=/dev/null',
      'diff',
      '-U0',
      `${baseSha}..${headSha}`,
      '--',
      'backend/src/main/java',
      'frontend/src',
      ':!.guardrails',
    ],
    { encoding: 'utf8', maxBuffer: 20 * 1024 * 1024 }
  )

  const changedByFile = parseAddedLineRanges(diffText)
  const items = []
  const files = []

  for (const [relPath, changedLinesSet] of changedByFile.entries()) {
    const absPath = path.join(resolvedRepo, relPath)
    if (!fs.existsSync(absPath)) continue
    const content = fs.readFileSync(absPath, 'utf8')
    const fileItems = extractCommentTargetsFromContent({ relPath, content, changedLinesSet })
    if (fileItems.length > 0) {
      files.push(relPath)
      items.push(...fileItems)
    }
  }

  return {
    baseSha,
    headSha,
    hasTargets: items.length > 0,
    files,
    items,
  }
}

function main() {
  const args = process.argv.slice(2)
  let repoDir = '.'
  let outPath = ''
  let baseSha = process.env.BASE_SHA || ''
  let headSha = process.env.HEAD_SHA || 'HEAD'
  let githubOutput = process.env.GITHUB_OUTPUT || ''

  for (let i = 0; i < args.length; i++) {
    if (args[i] === '--repo' && args[i + 1]) repoDir = args[++i]
    else if (args[i] === '--out' && args[i + 1]) outPath = args[++i]
    else if (args[i] === '--base' && args[i + 1]) baseSha = args[++i]
    else if (args[i] === '--head' && args[i + 1]) headSha = args[++i]
    else if (args[i] === '--github-output' && args[i + 1]) githubOutput = args[++i]
  }

  if (!baseSha) {
    console.error('[extract-comment-diff] 缺少 BASE_SHA / --base')
    process.exit(1)
  }

  const payload = extractCommentDiff({ repoDir, baseSha, headSha })
  const json = JSON.stringify(payload, null, 2)
  if (outPath) {
    fs.mkdirSync(path.dirname(path.resolve(outPath)), { recursive: true })
    fs.writeFileSync(path.resolve(outPath), json, 'utf8')
  } else {
    console.log(json)
  }

  if (githubOutput) {
    fs.appendFileSync(githubOutput, `has_targets=${payload.hasTargets ? 'true' : 'false'}\n`, 'utf8')
  }
  console.error(
    `[extract-comment-diff] has_targets=${payload.hasTargets} (files=${payload.files.length}, items=${payload.items.length})`
  )
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main()
}
