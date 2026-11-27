#!/usr/bin/env node
import fs from 'node:fs'
import path from 'node:path'
import { pathToFileURL } from 'node:url'

const WHITELISTED_FE_DOC_BASES = new Set([
  'src/api/http.js',
  'src/api/http.ts',
  'src/api/httpError.js',
  'src/api/httpError.ts',
  'src/utils/consultSocket.js',
  'src/utils/consultSocket.ts',
  'src/utils/idempotencyKey.js',
  'src/utils/idempotencyKey.ts',
  'src/utils/token.js',
  'src/utils/token.ts',
  'src/stores/session.js',
  'src/stores/session.ts',
])

const BANNED_BE_DOC_PATH_PARTS = ['/vo/', '/dto/', '/entity/', '/props/', '/controller/']

const DEAD_CODE_PATTERNS = [
  /;\s*$/,
  /[{}]\s*$/,
  /\b(?:if|for|while)\s*\(/,
  /\breturn\s+[^;]+/,
  /^\s*(?:import|export|const|let|var|private|public|protected)\s+/,
  /\.[a-zA-Z0-9_]+\(/,
  /\b(?:console|log)\./,
  /<(?:el-[a-z0-9-]+|div|span|template|button|input|form|table)[>\s/]/i,
]

const CHANGE_LOG_PATTERNS = [
  /本次(?:修改|新增|修复|优化|重构|调整|删除)/,
  /这次改/,
  /改成.*了/,
  /以前是.*现在/,
  /\bhotfix\b/i,
  /临时(?:改|加|绕过)/,
]

const FORBIDDEN_DIRECTIVE_PATTERNS = [
  { re: /\/\*\s*node:coverage\b/i, label: 'node:coverage' },
  { re: /\/\*\s*v8\s+ignore\b/i, label: 'v8 ignore' },
  { re: /\/\*\s*eslint-disable\b/i, label: 'block eslint-disable' },
  { re: /@ts-ignore\b/, label: '@ts-ignore' },
  { re: /@ts-nocheck\b/, label: '@ts-nocheck' },
  { re: /\/\/\s*(?:CHECKSTYLE|spotless):(?:OFF|ON)\b/i, label: 'CHECKSTYLE/spotless toggle' },
]

function walkFiles(dir, exts) {
  if (!fs.existsSync(dir)) return []
  const out = []
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) {
      if (entry.name === 'node_modules' || entry.name === 'target' || entry.name === 'dist' || entry.name === '.guardrails') {
        continue
      }
      out.push(...walkFiles(full, exts))
    } else if (exts.some((ext) => entry.name.endsWith(ext))) {
      out.push(full)
    }
  }
  return out.sort()
}

/**
 * Extract top-level <script> blocks and the outer <template> block (with line offsets)
 * from a Vue SFC while ignoring <style> blocks.
 */
export function splitVueSfc(source) {
  const lines = source.split(/\r?\n/)
  const scripts = []
  const templates = []

  let mode = null
  let templateDepth = 0
  let buf = []
  let startLine = 1

  for (let idx = 0; idx < lines.length; idx++) {
    const line = lines[idx]
    const lineNo = idx + 1

    if (!mode) {
      if (/^\s*<script\b[^>]*>/i.test(line)) {
        if (/<\/script>/i.test(line)) {
          const inner = line.replace(/^\s*<script\b[^>]*>/i, '').replace(/<\/script>.*$/i, '')
          scripts.push({ text: inner, startLine: lineNo })
        } else {
          mode = 'script'
          buf = []
          startLine = lineNo + 1
        }
        continue
      }
      if (/^\s*<template\b[^>]*>/i.test(line)) {
        mode = 'template'
        templateDepth = 1
        buf = [line.replace(/^\s*<template\b[^>]*>/i, '')]
        startLine = lineNo
        // Check if closed on same line
        const rest = buf[0]
        const closes = (rest.match(/<\/template>/gi) || []).length
        if (closes >= 1) {
          templates.push({ text: rest.replace(/<\/template>\s*$/i, ''), startLine })
          mode = null
          templateDepth = 0
        }
        continue
      }
      if (/^\s*<style\b[^>]*>/i.test(line)) {
        if (!/<\/style>/i.test(line)) mode = 'style'
        continue
      }
    } else if (mode === 'script') {
      if (/<\/script>/i.test(line)) {
        scripts.push({ text: buf.join('\n'), startLine })
        mode = null
      } else {
        buf.push(line)
      }
    } else if (mode === 'style') {
      if (/<\/style>/i.test(line)) {
        mode = null
      }
    } else if (mode === 'template') {
      const opens = (line.match(/<template\b[^>]*>/gi) || []).length
      const closes = (line.match(/<\/template>/gi) || []).length
      templateDepth += opens - closes
      if (templateDepth <= 0) {
        const cleaned = line.replace(/<\/template>\s*$/i, '')
        buf.push(cleaned)
        templates.push({ text: buf.join('\n'), startLine })
        mode = null
        templateDepth = 0
      } else {
        buf.push(line)
      }
    }
  }

  return { scripts, templates }
}

const REGEX_PRECEDING_KEYWORDS = new Set([
  'return',
  'throw',
  'case',
  'delete',
  'void',
  'typeof',
  'in',
  'instanceof',
  'new',
  'of',
  'await',
  'yield',
  'else',
  'do',
])

/**
 * Lexical scanner for Java/JS/TS code that accurately separates string literals,
 * JS/TS RegExp literals (when allowRegex=true), line comments (whole-line vs trailing),
 * non-doc block comments, and doc blocks.
 */
export function scanCodeLexical(text, baseLine = 1, { allowRegex = false } = {}) {
  let i = 0
  let line = baseLine
  const lineHasCode = new Set()
  const lineHasComment = new Set()
  const comments = []

  let inSingle = false
  let inDouble = false
  let inBacktick = false
  let inTextBlock = false
  let inRegex = false
  let inRegexClass = false
  let lineContentBeforeComment = false
  let canStartRegex = true

  const linesArr = text.split(/\r?\n/)
  const getIndent = (ln) => {
    const raw = linesArr[ln - baseLine] || ''
    const m = /^(\s*)/.exec(raw)
    return m ? m[1] : ''
  }

  while (i < text.length) {
    const c = text[i]
    const n = text[i + 1]

    if (inTextBlock) {
      lineHasCode.add(line)
      if (c === '"' && n === '"' && text[i + 2] === '"') {
        inTextBlock = false
        canStartRegex = false
        i += 3
        continue
      }
      if (c === '\n') {
        line++
        lineContentBeforeComment = false
      }
      i++
      continue
    }

    if (inSingle) {
      lineHasCode.add(line)
      if (c === '\\') {
        i += 2
        continue
      }
      if (c === "'") {
        inSingle = false
        canStartRegex = false
      }
      if (c === '\n') {
        line++
        inSingle = false
        lineContentBeforeComment = false
      }
      i++
      continue
    }

    if (inDouble) {
      lineHasCode.add(line)
      if (c === '\\') {
        i += 2
        continue
      }
      if (c === '"') {
        inDouble = false
        canStartRegex = false
      }
      if (c === '\n') {
        line++
        inDouble = false
        lineContentBeforeComment = false
      }
      i++
      continue
    }

    if (inBacktick) {
      lineHasCode.add(line)
      if (c === '\\') {
        i += 2
        continue
      }
      if (c === '`') {
        inBacktick = false
        canStartRegex = false
      }
      if (c === '\n') {
        line++
      }
      i++
      continue
    }

    if (inRegex) {
      lineHasCode.add(line)
      if (c === '\\') {
        i += n && n !== '\n' ? 2 : 1
        continue
      }
      if (c === '[') {
        inRegexClass = true
        i++
        continue
      }
      if (c === ']') {
        inRegexClass = false
        i++
        continue
      }
      if (c === '/' && !inRegexClass) {
        inRegex = false
        inRegexClass = false
        i++
        while (i < text.length && /[a-zA-Z]/.test(text[i])) i++
        canStartRegex = false
        lineContentBeforeComment = true
        continue
      }
      if (c === '\n') {
        inRegex = false
        inRegexClass = false
        line++
        lineContentBeforeComment = false
        i++
        continue
      }
      i++
      continue
    }

    if (c === '\n') {
      line++
      lineContentBeforeComment = false
      i++
      continue
    }

    if (c === ' ' || c === '\t' || c === '\r') {
      i++
      continue
    }

    if (c === '/' && n === '/') {
      const startLine = line
      const trailing = lineContentBeforeComment
      const indent = getIndent(startLine)
      lineHasComment.add(line)
      const startIdx = i
      while (i < text.length && text[i] !== '\n') i++
      const raw = text.slice(startIdx, i)
      comments.push({
        kind: trailing ? 'trailing_line' : 'whole_line',
        startLine,
        endLine: startLine,
        indent,
        raw,
        body: raw.slice(2),
      })
      continue
    }

    if (c === '/' && n === '*') {
      const startLine = line
      const isDoc = text[i + 2] === '*' && text[i + 3] !== '/'
      const startIdx = i
      i += 2
      lineHasComment.add(line)
      while (i < text.length) {
        if (text[i] === '*' && text[i + 1] === '/') {
          i += 2
          break
        }
        if (text[i] === '\n') {
          line++
          lineHasComment.add(line)
        }
        i++
      }
      const endLine = line
      const raw = text.slice(startIdx, i)
      comments.push({
        kind: isDoc ? 'doc_block' : 'non_doc_block',
        startLine,
        endLine,
        indent: getIndent(startLine),
        raw,
        body: raw.replace(/^\/\*\*?/, '').replace(/\*\/$/, ''),
      })
      continue
    }

    if (allowRegex && c === '/' && canStartRegex) {
      inRegex = true
      inRegexClass = false
      lineHasCode.add(line)
      lineContentBeforeComment = true
      i++
      continue
    }

    lineHasCode.add(line)
    lineContentBeforeComment = true
    if (c === '"' && n === '"' && text[i + 2] === '"') {
      inTextBlock = true
      i += 3
      continue
    }
    if (c === "'") {
      inSingle = true
      i++
      continue
    }
    if (c === '"') {
      inDouble = true
      i++
      continue
    }
    if (c === '`') {
      inBacktick = true
      i++
      continue
    }
    if ((c === '+' && n === '+') || (c === '-' && n === '-')) {
      canStartRegex = false
      i += 2
      continue
    }
    if (c === '=' && n === '>') {
      canStartRegex = true
      i += 2
      continue
    }
    if (/[A-Za-z_$]/.test(c)) {
      const start = i
      while (i < text.length && /[A-Za-z0-9_$]/.test(text[i])) i++
      const word = text.slice(start, i)
      canStartRegex = REGEX_PRECEDING_KEYWORDS.has(word)
      continue
    }
    if (/[0-9]/.test(c)) {
      canStartRegex = false
      i++
      continue
    }
    if (c === ')' || c === ']' || c === '}' || c === '.') {
      canStartRegex = false
      i++
      continue
    }
    canStartRegex = true
    i++
  }

  const commentOnlyLines = [...lineHasComment].filter((l) => !lineHasCode.has(l)).length
  const codeLines = lineHasCode.size
  const allCommentLines = lineHasComment.size
  const denom = codeLines + commentOnlyLines
  const density = denom > 0 ? (allCommentLines / denom) * 100 : 0

  return {
    comments,
    codeLines,
    commentOnlyLines,
    allCommentLines,
    density,
  }
}

function scanTemplateComments(text, baseLine) {
  const results = []
  const re = /<!--([\s\S]*?)-->/g
  let match
  while ((match = re.exec(text)) !== null) {
    const before = text.slice(0, match.index)
    const startLine = baseLine + (before.match(/\n/g) || []).length
    const lineSpan = (match[0].match(/\n/g) || []).length + 1
    results.push({
      kind: 'template_comment',
      startLine,
      endLine: startLine + lineSpan - 1,
      raw: match[0],
      body: match[1],
    })
  }
  return results
}

function stripBackticks(str) {
  return str.replace(/`[^`]*`/g, ' ')
}

function checkDeadCode(text) {
  const lines = text.split(/\r?\n/)
  for (const rawLine of lines) {
    const cleaned = stripBackticks(rawLine)
      .replace(/^\s*(?:\/\/|\/\*+|\*+\/|\*|<!--|-->)\s*/g, '')
      .replace(/\s*(?:\*\/|-->)\s*$/g, '')
      .replace(/^(?:原因|TODO\(#\d+\)):\s*/, '')
      .trim()
    if (!cleaned) continue
    for (const pat of DEAD_CODE_PATTERNS) {
      if (pat.test(cleaned)) return cleaned
    }
  }
  return null
}

function checkChangeLog(text) {
  for (const pat of CHANGE_LOG_PATTERNS) {
    if (pat.test(text)) return pat.source
  }
  return null
}

function loadBannedTerms(repoDir) {
  const cfgPath = path.join(repoDir, '.guardrails', 'banned-terms.json')
  if (fs.existsSync(cfgPath)) {
    try {
      const list = JSON.parse(fs.readFileSync(cfgPath, 'utf8'))
      if (Array.isArray(list)) return list.filter((x) => typeof x === 'string' && x.length > 0)
    } catch {
      // Fallback to default
    }
  }
  return ['axios']
}

function groupConsecutiveLineComments(comments) {
  const groups = []
  let current = null
  for (const c of comments) {
    if (c.kind !== 'whole_line') {
      if (current) {
        groups.push(current)
        current = null
      }
      groups.push({ kind: 'single', items: [c] })
      continue
    }
    if (
      current &&
      current.kind === 'whole_line_group' &&
      c.startLine === current.endLine + 1 &&
      c.indent === current.indent &&
      !isDirectiveLine(c.raw) &&
      !isDirectiveLine(current.items[0].raw)
    ) {
      current.items.push(c)
      current.endLine = c.endLine
    } else {
      if (current) groups.push(current)
      current = {
        kind: 'whole_line_group',
        startLine: c.startLine,
        endLine: c.endLine,
        indent: c.indent,
        items: [c],
      }
    }
  }
  if (current) groups.push(current)
  return groups
}

function isDirectiveLine(raw) {
  const trimmed = raw.trim()
  return (
    /^\/\/\/\s*<reference\s+types="[^"]+"\s*\/>$/.test(trimmed) ||
    /^\/\/\s*@ts-expect-error:/.test(trimmed) ||
    /^\/\/\s*eslint-disable-next-line\b/.test(trimmed)
  )
}

function validateStrictFile({ relPath, isBackend, isVue, rawText, scanResult, templateComments, bannedTerms }) {
  const violations = []
  const addViolation = (ruleId, line, message) => {
    violations.push({ file: relPath, line, ruleId, message })
  }

  // 1. Density check
  if (scanResult.codeLines >= 20 && scanResult.density > 35) {
    addViolation(
      'COMMENT-DENSITY',
      1,
      `注释密度 ${scanResult.density.toFixed(1)}% 超过 35%（有效代码行 ${scanResult.codeLines}，注释行 ${scanResult.allCommentLines}）`
    )
  }

  // 2. Check doc blocks
  for (const c of scanResult.comments) {
    if (c.kind !== 'doc_block') continue
    const lineCount = c.endLine - c.startLine + 1
    if (lineCount > 16) {
      addViolation('COMMENT-DENSITY', c.startLine, `文档注释块共 ${lineCount} 行，超过 16 行上限`)
    }
    if (!isBackend) {
      const normalized = relPath.replace(/^frontend\//, '')
      if (!WHITELISTED_FE_DOC_BASES.has(normalized)) {
        addViolation('COMMENT-UNEXPECTED-DOC', c.startLine, `非白名单前端文件禁止 JSDoc: ${normalized}`)
      }
      if (/@param\s+\{[^}]+\}/.test(c.raw)) {
        addViolation('COMMENT-UNEXPECTED-DOC', c.startLine, '全站禁止在 JSDoc 中使用 @param {type}')
      }
    } else {
      if (relPath.endsWith('/package-info.java') || BANNED_BE_DOC_PATH_PARTS.some((p) => relPath.includes(p))) {
        addViolation('COMMENT-UNEXPECTED-DOC', c.startLine, `禁止在 ${relPath} 使用 Javadoc`)
      }
    }
    for (const term of bannedTerms) {
      const re = new RegExp(`\\b${term.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\b`, 'i')
      if (re.test(c.raw)) {
        addViolation('COMMENT-BANNED-TERM', c.startLine, `注释包含禁用词 "${term}"`)
      }
    }
    const changeLog = checkChangeLog(c.raw)
    if (changeLog) {
      addViolation('COMMENT-CHANGE-LOG', c.startLine, '禁止在源码中写变更叙述注释')
    }
  }

  // 3. Check non-doc comments in code
  const nonDoc = scanResult.comments.filter((c) => c.kind !== 'doc_block')
  const groups = groupConsecutiveLineComments(nonDoc)
  const fileLines = rawText.split(/\r?\n/)

  for (const g of groups) {
    if (g.kind === 'single') {
      const c = g.items[0]
      if (c.kind === 'trailing_line') {
        addViolation('COMMENT-INLINE-PREFIX', c.startLine, '禁止行尾 // 注释，确有原因请独立成行改写为 // 原因: ...')
      } else if (c.kind === 'non_doc_block') {
        const isViteIgnore =
          !isBackend && /^\/\*\s*@vite-ignore\s*\*\/$/.test(c.raw.trim())
        if (isViteIgnore) {
          // Preceding non-blank line in script must be // 原因:
          let prevIdx = c.startLine - 2
          while (prevIdx >= 0 && fileLines[prevIdx].trim() === '') prevIdx--
          const prevLine = prevIdx >= 0 ? fileLines[prevIdx].trim() : ''
          if (!/^\/\/\s+原因:\s+\S/.test(prevLine)) {
            addViolation('COMMENT-INLINE-PREFIX', c.startLine, '/* @vite-ignore */ 前一行必须配 // 原因: <说明>')
          }
        } else {
          const hitDir = FORBIDDEN_DIRECTIVE_PATTERNS.find((d) => d.re.test(c.raw))
          if (hitDir) {
            addViolation('COMMENT-SUPPRESSION-RATCHET', c.startLine, `禁止指令型注释 ${hitDir.label}`)
          } else {
            addViolation('COMMENT-INLINE-PREFIX', c.startLine, '禁止普通块注释 /* */')
          }
        }
      }
    } else {
      const items = g.items
      const firstRaw = items[0].raw.trim()
      const joined = items.map((x) => x.body).join('\n')

      // Check forbidden directives first
      const hitDir = FORBIDDEN_DIRECTIVE_PATTERNS.find((d) => d.re.test(firstRaw) || d.re.test(joined))
      if (hitDir) {
        addViolation('COMMENT-SUPPRESSION-RATCHET', g.startLine, `禁止指令型注释 ${hitDir.label}`)
        continue
      }

      if (items.length > 6) {
        addViolation('COMMENT-DENSITY', g.startLine, `连续 // 注释块共 ${items.length} 行，超过 6 行上限`)
      }

      if (!isBackend && items.length === 1 && isDirectiveLine(firstRaw)) {
        if (/^\/\/\/\s*<reference\s+types="[^"]+"\s*\/>$/.test(firstRaw)) {
          if (!relPath.endsWith('.d.ts')) {
            addViolation('COMMENT-SUPPRESSION-RATCHET', g.startLine, '/// <reference types="..." /> 仅允许出现在 *.d.ts')
          }
        } else if (/^\/\/\s*@ts-expect-error:/.test(firstRaw)) {
          if (!/^\/\/\s*@ts-expect-error:\s*原因:\s*\S+.*\(#\d+\)\s*$/.test(firstRaw)) {
            addViolation('COMMENT-SUPPRESSION-RATCHET', g.startLine, '@ts-expect-error 须匹配 // @ts-expect-error: 原因: <说明> (#<n>)')
          }
        } else if (/^\/\/\s*eslint-disable-next-line\b/.test(firstRaw)) {
          if (!/^\/\/\s*eslint-disable-next-line\s+\S+\s+--\s+原因:\s*\S+.*\(#\d+\)\s*$/.test(firstRaw)) {
            addViolation('COMMENT-SUPPRESSION-RATCHET', g.startLine, 'eslint-disable-next-line 须匹配 // eslint-disable-next-line <rule> -- 原因: <说明> (#<n>)')
          }
        }
      } else {
        if (!/^\/\/\s(?:原因:\s+\S|TODO\(#\d+\):\s+\S)/.test(firstRaw)) {
          if (/\b(?:FIXME|HACK|XXX)\b/i.test(joined) || /\bTODO\b(?!\(#\d+\):)/.test(joined)) {
            addViolation('COMMENT-INLINE-PREFIX', g.startLine, '禁止 FIXME/HACK/XXX 与无编号 TODO，须使用 // TODO(#<n>): <动作>')
          } else {
            addViolation('COMMENT-INLINE-PREFIX', g.startLine, '非文档注释首行必须匹配 // 原因: <说明> 或 // TODO(#<n>): <动作>')
          }
        }
      }
    }

    const combinedRaw = g.items.map((x) => x.raw).join('\n')
    const combinedBody = g.items.map((x) => x.body).join('\n')
    if (/\b(?:FIXME|HACK|XXX)\b/i.test(combinedBody) || /\bTODO\b(?!\(#\d+\):)/.test(combinedBody)) {
      addViolation('COMMENT-INLINE-PREFIX', g.items[0].startLine, '禁止 FIXME/HACK/XXX 与无编号 TODO')
    }
    const dead = checkDeadCode(combinedBody)
    if (dead) {
      addViolation('COMMENT-DEAD-CODE', g.items[0].startLine, `疑似被注释掉的代码: ${dead.slice(0, 60)}`)
    }
    const cl = checkChangeLog(combinedRaw)
    if (cl) {
      addViolation('COMMENT-CHANGE-LOG', g.items[0].startLine, '禁止在源码中写变更叙述注释')
    }
    for (const term of bannedTerms) {
      const re = new RegExp(`\\b${term.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\b`, 'i')
      if (re.test(combinedRaw)) {
        addViolation('COMMENT-BANNED-TERM', g.items[0].startLine, `注释包含禁用词 "${term}"`)
      }
    }
  }

  // 4. Check Vue template comments
  if (isVue) {
    for (const tc of templateComments) {
      const span = tc.endLine - tc.startLine + 1
      if (span > 3) {
        addViolation('COMMENT-DENSITY', tc.startLine, `模板注释共 ${span} 行，超过 3 行上限`)
      }
      const trimmedBody = tc.body.trim()
      if (!/^(?:原因:\s+\S|TODO\(#\d+\):\s+\S)/.test(trimmedBody)) {
        addViolation('COMMENT-INLINE-PREFIX', tc.startLine, 'Vue <template> 注释仅允许 <!-- 原因: ... --> 或 <!-- TODO(#<n>): ... -->')
      }
      const dead = checkDeadCode(tc.body)
      if (dead) {
        addViolation('COMMENT-DEAD-CODE', tc.startLine, `疑似被注释掉的模板片段: ${dead.slice(0, 60)}`)
      }
      const cl = checkChangeLog(tc.body)
      if (cl) {
        addViolation('COMMENT-CHANGE-LOG', tc.startLine, '禁止在模板中写变更叙述注释')
      }
      for (const term of bannedTerms) {
        const re = new RegExp(`\\b${term.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\b`, 'i')
        if (re.test(tc.body)) {
          addViolation('COMMENT-BANNED-TERM', tc.startLine, `模板注释包含禁用词 "${term}"`)
        }
      }
    }
  }

  return violations
}

export function runAudit({ repoDir = '.', strict = false } = {}) {
  const resolvedRepo = path.resolve(repoDir)
  const bannedTerms = loadBannedTerms(resolvedRepo)

  const beDir = path.join(resolvedRepo, 'backend', 'src', 'main', 'java')
  const feDir = path.join(resolvedRepo, 'frontend', 'src')
  const beFiles = walkFiles(beDir, ['.java'])
  const feFiles = walkFiles(feDir, ['.js', '.ts', '.vue'])

  const summary = {
    backend: {
      files: beFiles.length,
      wholeLineSlash: 0,
      trailingSlash: 0,
      nonDocBlock: 0,
      nonDocTotal: 0,
      docBlocks: 0,
      overDensityFiles: [],
    },
    frontend: {
      files: feFiles.length,
      wholeLineSlash: 0,
      trailingSlash: 0,
      nonDocBlock: 0,
      templateComments: 0,
      nonDocTotal: 0,
      docBlocks: 0,
      overDensityFiles: [],
    },
    totals: {
      files: beFiles.length + feFiles.length,
      nonDocComments: 0,
      docBlocks: 0,
      overDensityFileCount: 0,
    },
    violations: [],
  }

  for (const absPath of beFiles) {
    const relPath = path.relative(resolvedRepo, absPath).replace(/\\/g, '/')
    const raw = fs.readFileSync(absPath, 'utf8')
    const res = scanCodeLexical(raw, 1)

    for (const c of res.comments) {
      if (c.kind === 'whole_line') summary.backend.wholeLineSlash++
      else if (c.kind === 'trailing_line') summary.backend.trailingSlash++
      else if (c.kind === 'non_doc_block') summary.backend.nonDocBlock++
      else if (c.kind === 'doc_block') summary.backend.docBlocks++
    }
    if (res.codeLines >= 20 && res.density > 35) {
      summary.backend.overDensityFiles.push({
        file: relPath,
        density: Number(res.density.toFixed(1)),
        codeLines: res.codeLines,
        commentLines: res.allCommentLines,
      })
    }
    if (strict) {
      summary.violations.push(
        ...validateStrictFile({
          relPath,
          isBackend: true,
          isVue: false,
          rawText: raw,
          scanResult: res,
          templateComments: [],
          bannedTerms,
        })
      )
    }
  }
  summary.backend.nonDocTotal =
    summary.backend.wholeLineSlash + summary.backend.trailingSlash + summary.backend.nonDocBlock

  for (const absPath of feFiles) {
    const relPath = path.relative(resolvedRepo, absPath).replace(/\\/g, '/')
    const raw = fs.readFileSync(absPath, 'utf8')
    const isVue = absPath.endsWith('.vue')

    let scriptCombined = ''
    let mergedComments = []
    let totalCodeLines = 0
    let totalCommentOnlyLines = 0
    let totalAllCommentLines = 0
    let templateComments = []

    if (isVue) {
      const sfc = splitVueSfc(raw)
      for (const blk of sfc.scripts) {
        const res = scanCodeLexical(blk.text, blk.startLine, { allowRegex: true })
        mergedComments.push(...res.comments)
        totalCodeLines += res.codeLines
        totalCommentOnlyLines += res.commentOnlyLines
        totalAllCommentLines += res.allCommentLines
      }
      for (const tpl of sfc.templates) {
        templateComments.push(...scanTemplateComments(tpl.text, tpl.startLine))
      }
      scriptCombined = raw
    } else {
      const res = scanCodeLexical(raw, 1, { allowRegex: true })
      mergedComments = res.comments
      totalCodeLines = res.codeLines
      totalCommentOnlyLines = res.commentOnlyLines
      totalAllCommentLines = res.allCommentLines
      scriptCombined = raw
    }

    const denom = totalCodeLines + totalCommentOnlyLines
    const density = denom > 0 ? (totalAllCommentLines / denom) * 100 : 0
    const scanResult = {
      comments: mergedComments,
      codeLines: totalCodeLines,
      commentOnlyLines: totalCommentOnlyLines,
      allCommentLines: totalAllCommentLines,
      density,
    }

    for (const c of mergedComments) {
      if (c.kind === 'whole_line') summary.frontend.wholeLineSlash++
      else if (c.kind === 'trailing_line') summary.frontend.trailingSlash++
      else if (c.kind === 'non_doc_block') summary.frontend.nonDocBlock++
      else if (c.kind === 'doc_block') summary.frontend.docBlocks++
    }
    summary.frontend.templateComments += templateComments.length

    if (totalCodeLines >= 20 && density > 35) {
      summary.frontend.overDensityFiles.push({
        file: relPath,
        density: Number(density.toFixed(1)),
        codeLines: totalCodeLines,
        commentLines: totalAllCommentLines,
      })
    }

    if (strict) {
      summary.violations.push(
        ...validateStrictFile({
          relPath,
          isBackend: false,
          isVue,
          rawText: scriptCombined,
          scanResult,
          templateComments,
          bannedTerms,
        })
      )
    }
  }
  summary.frontend.nonDocTotal =
    summary.frontend.wholeLineSlash +
    summary.frontend.trailingSlash +
    summary.frontend.nonDocBlock +
    summary.frontend.templateComments

  summary.totals.nonDocComments = summary.backend.nonDocTotal + summary.frontend.nonDocTotal
  summary.totals.docBlocks = summary.backend.docBlocks + summary.frontend.docBlocks
  summary.totals.overDensityFileCount =
    summary.backend.overDensityFiles.length + summary.frontend.overDensityFiles.length

  return summary
}

function main() {
  const args = process.argv.slice(2)
  let repoDir = '.'
  let strict = false
  let jsonOut = false

  for (let i = 0; i < args.length; i++) {
    if (args[i] === '--repo' && args[i + 1]) {
      repoDir = args[++i]
    } else if (args[i] === '--strict') {
      strict = true
    } else if (args[i] === '--json') {
      jsonOut = true
    }
  }

  const report = runAudit({ repoDir, strict })

  if (jsonOut) {
    console.log(JSON.stringify(report, null, 2))
  } else {
    console.log(
      `[comment-audit] backend(${report.backend.files} files): nonDoc=${report.backend.nonDocTotal} (//=${report.backend.wholeLineSlash}, trailing=${report.backend.trailingSlash}, /*=${report.backend.nonDocBlock}), doc=${report.backend.docBlocks}, >35%=${report.backend.overDensityFiles.length}`
    )
    if (report.backend.overDensityFiles.length > 0) {
      for (const f of report.backend.overDensityFiles) {
        console.log(`  >35% BE: ${f.file} (${f.density}%, code=${f.codeLines}, comment=${f.commentLines})`)
      }
    }
    console.log(
      `[comment-audit] frontend(${report.frontend.files} files): nonDoc=${report.frontend.nonDocTotal} (//=${report.frontend.wholeLineSlash}, trailing=${report.frontend.trailingSlash}, /*=${report.frontend.nonDocBlock}, template=${report.frontend.templateComments}), doc=${report.frontend.docBlocks}, >35%=${report.frontend.overDensityFiles.length}`
    )
    console.log(
      `[comment-audit] totals(${report.totals.files} files): nonDoc=${report.totals.nonDocComments}, doc=${report.totals.docBlocks}, >35%=${report.totals.overDensityFileCount}`
    )
    if (strict) {
      console.log(`[comment-audit] strict violations: ${report.violations.length}`)
      for (const v of report.violations.slice(0, 50)) {
        console.error(`  [${v.ruleId}] ${v.file}:${v.line} — ${v.message}`)
      }
      if (report.violations.length > 50) {
        console.error(`  ... and ${report.violations.length - 50} more violations`)
      }
    }
  }

  if (strict && (report.violations.length > 0 || report.totals.overDensityFileCount > 0)) {
    process.exit(1)
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main()
}
