import { existsSync, readdirSync, readFileSync } from 'node:fs'
import { dirname, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = dirname(fileURLToPath(import.meta.url))
const FRONTEND_ROOT = resolve(__dirname, '..')
const GUARDRAILS_DIR = resolve(FRONTEND_ROOT, '..', '.guardrails')

const WHITELIST_DOC_RE = /^src\/(?:api\/http|api\/httpError|utils\/consultSocket|utils\/idempotencyKey|utils\/token|stores\/session)\.(?:js|ts)$/
const TEMPLATE_PREFIX_RE = /^\s*(?:原因: \S|TODO\(#\d+\): \S)/
const LINE_PREFIX_RE = /^ (?:原因: \S|TODO\(#\d+\): \S)/
const REFERENCE_DIRECTIVE_RE = /^\/ <reference types="[^"]+" \/>$/
const TS_EXPECT_ERROR_RE = /^ @ts-expect-error: 原因: \S.*#\d+/
const ESLINT_DISABLE_NEXT_RE = /^ eslint-disable-next-line [a-zA-Z0-9_@/-]+(?:\s*,\s*[a-zA-Z0-9_@/-]+)* -- 原因: \S.*#\d+/

const BANNED_DIRECTIVE_PATTERNS = [
  { re: /\bnode:coverage\b/i, code: 'COMMENT-SUPPRESSION-RATCHET' },
  { re: /\bv8 ignore\b/i, code: 'COMMENT-SUPPRESSION-RATCHET' },
  { re: /@ts-ignore\b/, code: 'COMMENT-SUPPRESSION-RATCHET' },
  { re: /@ts-nocheck\b/, code: 'COMMENT-SUPPRESSION-RATCHET' },
  { re: /\b(?:CHECKSTYLE|spotless)\s*:\s*(?:OFF|ON)\b/i, code: 'COMMENT-SUPPRESSION-RATCHET' },
  { re: /\b(?:FIXME|HACK|XXX)\b/i, code: 'COMMENT-INLINE-PREFIX' }
]

const DEAD_CODE_PATTERNS = [
  /;\s*$/,
  /(?:^\s*[{}]|\{\s*$|\)\s*\{|\}\s*(?:else|catch|finally)\b)/,
  /\b(?:if|for|while)\s*\(/,
  /\breturn\s+(?:[A-Za-z0-9_'"`[{(!]|$)/,
  /\b(?:import|export|const|let|private|public)\s+[A-Za-z_{*[]/,
  /\.\s*set[A-Z]\w*\s*\(|\b(?:console|log)\./,
  /<(?:el-|div\b|span\b)/i
]

const CHANGE_LOG_RE = /本次(?:修改|新增|修复|优化|重构|调整|删除)|这次改|改成.*了|以前是.*现在|\bhotfix\b|临时(?:改|加|绕过)/i

const MESSAGES = {
  'COMMENT-RISK-DOC': '[COMMENT-RISK-DOC] 高风险边界（事务/并发/鉴权/协议）缺少契约说明。禁止复述名称或类型；仅写锁顺序、前置条件、幂等或失败语义（<= 16 行）。',
  'COMMENT-UNEXPECTED-DOC': '[COMMENT-UNEXPECTED-DOC] 普通类型/方法/导出及 package-info.java 默认不写 Javadoc/JSDoc。若仅复述名称请直接删除；确有非显然约束请改写为 <= 6 行的「// 原因: ...」。',
  'COMMENT-INLINE-PREFIX': '[COMMENT-INLINE-PREFIX] 源码默认不写注释。若仅解说代码/模板动作请直接删除；仅当记录非显然原因时独立成行改写为「// 原因: ...」（模板用「<!-- 原因: ... -->」）或「TODO(#n): ...」。',
  'COMMENT-DEAD-CODE': '[COMMENT-DEAD-CODE] 禁止保留被注释掉的代码或模板片段，请直接删除（历史版本由 Git 保留）。',
  'COMMENT-CHANGE-LOG': '[COMMENT-CHANGE-LOG] 禁止在源码中写「本次修改/修复/以前是…」类变更叙述，请删去或迁入提交说明。',
  'COMMENT-DENSITY': '[COMMENT-DENSITY] 注释密度或单块长度超限。请删复述解说；长背景原理请迁入 docs/adr/，源码仅留核心不变量与文档路径。',
  'COMMENT-SUPPRESSION-RATCHET': '[COMMENT-SUPPRESSION-RATCHET] 抑制超基线或关联 Issue 无效。请修复根因；仅编译器已知误报可在 open Issue 获人类维护者打标 guardrail:tracked 及 guardrail:budget-approved 后登记。',
  'COMMENT-BANNED-TERM': '[COMMENT-BANNED-TERM] 注释中禁止出现已废弃术语「{{term}}」（见 .guardrails/banned-terms.json），请删除或更新为当前实现。',
  'TEST-EMPTY-ASSERTION': '[TEST-EMPTY-ASSERTION] 测试用例或文件缺少有效断言（assert* / expect / verify / rule.check 等），禁止提交无断言空测试。'
}

function readJsonOrFallback(filePath, fallback) {
  try {
    if (!existsSync(filePath)) return fallback
    return JSON.parse(readFileSync(filePath, 'utf8'))
  } catch {
    return fallback
  }
}

function getBannedTerms(options) {
  if (Array.isArray(options?.bannedTerms)) return options.bannedTerms
  const loaded = readJsonOrFallback(resolve(GUARDRAILS_DIR, 'banned-terms.json'), ['axios'])
  return Array.isArray(loaded) ? loaded : ['axios']
}

function getSuppressionBudget(options) {
  if (typeof options?.suppressionBudget === 'number') return options.suppressionBudget
  const loaded = readJsonOrFallback(resolve(GUARDRAILS_DIR, 'suppression-budget.json'), { frontend_src: 0 })
  return typeof loaded?.frontend_src === 'number' ? loaded.frontend_src : 0
}

function normalizeRelPath(filename) {
  if (!filename || filename === '<input>') return ''
  const abs = resolve(filename)
  return relative(FRONTEND_ROOT, abs).replace(/\\/g, '/')
}

function isExcludedPath(relPath, rawFilename) {
  const target = (relPath || rawFilename || '').replace(/\\/g, '/')
  return target.includes('.guardrails/') || target.startsWith('.guardrails/')
}

function isJsDocComment(comment) {
  return comment.type === 'Block' && comment.value.startsWith('*')
}

function stripCommentBody(comment) {
  if (comment.type === 'Line') {
    return comment.value.replace(/^\s*(?:原因:\s*|TODO\(#\d+\):\s*)?/, '')
  }
  if (comment.type === 'HTMLComment') {
    return comment.value.replace(/^\s*(?:原因:\s*|TODO\(#\d+\):\s*)?/, '')
  }
  return comment.value
    .split(/\r?\n/)
    .map((line) => line.replace(/^\s*\*?\s?/, '').replace(/^(?:原因:\s*|TODO\(#\d+\):\s*)/, ''))
    .join('\n')
}

function findBannedTerm(text, bannedTerms) {
  for (const term of bannedTerms) {
    if (!term) continue
    const escaped = term.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
    const re = /^[A-Za-z0-9_]+$/.test(term) ? new RegExp(`\\b${escaped}\\b`, 'i') : new RegExp(escaped, 'i')
    if (re.test(text)) return term
  }
  return null
}

function hasDeadCode(strippedText) {
  const withoutBackticks = strippedText.replace(/`[^`]*`/g, '')
  const lines = withoutBackticks.split(/\r?\n/)
  return lines.some((line) => {
    const trimmed = line.trim()
    if (!trimmed) return false
    return DEAD_CODE_PATTERNS.some((re) => re.test(trimmed))
  })
}

function hasUnnumberedTodo(text) {
  if (!/\bTODO\b/i.test(text)) return false
  const lines = text.split(/\r?\n/)
  return lines.some((line) => /\bTODO\b/i.test(line) && !/(?:^|\s)TODO\(#\d+\): \S/.test(line))
}

function classifyDirective(comment, relPath) {
  const val = comment.value
  if (comment.type === 'Line' && REFERENCE_DIRECTIVE_RE.test(val)) {
    return { kind: relPath.endsWith('.d.ts') ? 'allowed-directive' : 'bad-reference' }
  }
  if (comment.type === 'Line' && val.includes('@ts-expect-error')) {
    return { kind: TS_EXPECT_ERROR_RE.test(val) ? 'counted-suppression' : 'bad-suppression' }
  }
  if (comment.type === 'Line' && /\beslint-disable-next-line\b/.test(val)) {
    return { kind: ESLINT_DISABLE_NEXT_RE.test(val) ? 'counted-suppression' : 'bad-suppression' }
  }
  if (comment.type === 'Block' && !isJsDocComment(comment) && /^\s*eslint-(?:disable|enable)\b/.test(val)) {
    return { kind: 'bad-suppression' }
  }
  if (comment.type === 'Line' && /^\s*eslint-(?:disable|enable)\b/.test(val)) {
    return { kind: 'bad-suppression' }
  }
  if (comment.type === 'Block' && !isJsDocComment(comment) && val.trim() === '@vite-ignore') {
    return { kind: 'vite-ignore' }
  }
  for (const { re, code } of BANNED_DIRECTIVE_PATTERNS) {
    if (re.test(val)) return { kind: 'banned-directive', code }
  }
  if (hasUnnumberedTodo(val)) return { kind: 'banned-directive', code: 'COMMENT-INLINE-PREFIX' }
  return null
}

function report(context, nodeOrLoc, messageKey, data) {
  const loc = nodeOrLoc.loc ? nodeOrLoc.loc : nodeOrLoc
  let message = MESSAGES[messageKey]
  if (data && data.term) {
    message = message.replace('{{term}}', data.term)
  }
  context.report({ loc, message })
}

function checkCommonNoise(context, comment, bannedTerms) {
  const stripped = stripCommentBody(comment)
  if (hasDeadCode(stripped)) {
    report(context, comment, 'COMMENT-DEAD-CODE')
  }
  if (CHANGE_LOG_RE.test(stripped)) {
    report(context, comment, 'COMMENT-CHANGE-LOG')
  }
  const hitTerm = findBannedTerm(comment.value, bannedTerms)
  if (hitTerm) {
    report(context, comment, 'COMMENT-BANNED-TERM', { term: hitTerm })
  }
}

function countRepoSuppressions(srcDir, currentAbsPath, currentCount) {
  if (!existsSync(srcDir)) return currentCount
  let total = currentCount
  const stack = [srcDir]
  while (stack.length) {
    const dir = stack.pop()
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const full = resolve(dir, entry.name)
      if (entry.isDirectory()) {
        stack.push(full)
        continue
      }
      if (!/\.(?:js|ts|vue)$/.test(entry.name) || full === currentAbsPath) continue
      const text = readFileSync(full, 'utf8')
      const matches = text.match(/\/\/\s*(?:@ts-expect-error:|eslint-disable-next-line\b)/g)
      if (matches) total += matches.length
    }
  }
  return total
}

function validateWhitelistedJsDoc(context, sourceCode, node, attachedDoc, bannedTerms) {
  if (!attachedDoc) {
    report(context, node, 'COMMENT-RISK-DOC')
    return
  }
  const stripped = stripCommentBody(attachedDoc).trim()
  if (!stripped || /@(?:param|returns?|type)\s*\{[^}]*\}/.test(attachedDoc.value)) {
    report(context, attachedDoc, 'COMMENT-RISK-DOC')
  }
  const lines = attachedDoc.loc.end.line - attachedDoc.loc.start.line + 1
  if (lines > 16) {
    report(context, attachedDoc, 'COMMENT-DENSITY')
  }
  const directive = classifyDirective(attachedDoc, '')
  if (directive) {
    report(context, attachedDoc, directive.code || 'COMMENT-INLINE-PREFIX')
  }
  checkCommonNoise(context, attachedDoc, bannedTerms)
}

function isStandaloneLineComment(sourceCode, comment) {
  const lineText = sourceCode.lines[comment.loc.start.line - 1] ?? ''
  return lineText.slice(0, comment.loc.start.column).trim().length === 0
}

function checkStandaloneLineGroups(context, comments) {
  let group = []
  const flush = () => {
    if (!group.length) return
    const first = group[0]
    if (!LINE_PREFIX_RE.test(first.value)) {
      report(context, first, 'COMMENT-INLINE-PREFIX')
    }
    if (group.length > 6) {
      report(context, first, 'COMMENT-DENSITY')
    }
    group = []
  }

  for (const c of comments) {
    const prev = group[group.length - 1]
    const contiguous = prev
      && c.loc.start.line === prev.loc.end.line + 1
      && c.loc.start.column === prev.loc.start.column
    if (!contiguous) flush()
    group.push(c)
  }
  flush()
}

function checkScriptDensity(context, sourceCode, scriptComments, programNode) {
  const codeLines = new Set()
  for (const token of sourceCode.ast.tokens) {
    for (let l = token.loc.start.line; l <= token.loc.end.line; l++) {
      if ((sourceCode.lines[l - 1] ?? '').trim()) codeLines.add(l)
    }
  }
  if (codeLines.size < 20) return

  const commentLines = new Set()
  for (const c of scriptComments) {
    for (let l = c.loc.start.line; l <= c.loc.end.line; l++) {
      if ((sourceCode.lines[l - 1] ?? '').trim()) commentLines.add(l)
    }
  }
  const unionLines = new Set([...codeLines, ...commentLines])
  if (unionLines.size > 0 && commentLines.size / unionLines.size > 0.35) {
    report(context, programNode, 'COMMENT-DENSITY')
  }
}

function checkTemplateComments(context, programNode, bannedTerms) {
  const tplComments = programNode.templateBody?.comments || []
  for (const c of tplComments) {
    if (!TEMPLATE_PREFIX_RE.test(c.value)) {
      report(context, c, 'COMMENT-INLINE-PREFIX')
    }
    const lineCount = c.loc.end.line - c.loc.start.line + 1
    if (lineCount > 3) {
      report(context, c, 'COMMENT-DENSITY')
    }
    const directive = classifyDirective(c, '')
    if (directive) {
      report(context, c, directive.code || 'COMMENT-INLINE-PREFIX')
    }
    checkCommonNoise(context, c, bannedTerms)
  }
}

function processNonDocComment(context, sourceCode, comment, relPath, state) {
  const directive = classifyDirective(comment, relPath)
  if (directive) {
    if (directive.kind === 'counted-suppression') {
      state.suppressions.push(comment)
      return
    }
    if (directive.kind === 'bad-suppression') {
      report(context, comment, 'COMMENT-SUPPRESSION-RATCHET')
      return
    }
    if (directive.kind === 'banned-directive') {
      report(context, comment, directive.code)
      return
    }
    if (directive.kind === 'bad-reference') {
      report(context, comment, 'COMMENT-INLINE-PREFIX')
      return
    }
    if (directive.kind === 'vite-ignore') {
      const prevLineComment = state.standaloneByLine.get(comment.loc.start.line - 1)
      if (!prevLineComment || !/^ 原因: \S/.test(prevLineComment.value)) {
        report(context, comment, 'COMMENT-INLINE-PREFIX')
      }
      return
    }
    if (directive.kind === 'allowed-directive') return
  }

  if (comment.type === 'Block') {
    report(context, comment, 'COMMENT-INLINE-PREFIX')
    return
  }
  if (!isStandaloneLineComment(sourceCode, comment)) {
    report(context, comment, 'COMMENT-INLINE-PREFIX')
    return
  }
  state.standaloneLines.push(comment)
}

function inspectWhitelistedExports(context, sourceCode, programNode, scriptComments, bannedTerms) {
  const allowedJsDocs = new Set()
  for (const stmt of programNode.body) {
    const isExport = (stmt.type === 'ExportNamedDeclaration' && !stmt.source)
      || stmt.type === 'ExportDefaultDeclaration'
    if (!isExport) continue
    const before = sourceCode.getCommentsBefore(stmt).filter(isJsDocComment)
    const attached = before[before.length - 1] || null
    if (attached) allowedJsDocs.add(attached)
    validateWhitelistedJsDoc(context, sourceCode, stmt, attached, bannedTerms)
  }
  for (const c of scriptComments) {
    if (isJsDocComment(c) && !allowedJsDocs.has(c)) {
      report(context, c, 'COMMENT-UNEXPECTED-DOC')
      checkCommonNoise(context, c, bannedTerms)
    }
  }
}

function enforceSuppressionRatchet(context, options, suppressions) {
  if (!suppressions.length) return
  const budget = getSuppressionBudget(options)
  const total = budget === 0
    ? suppressions.length
    : countRepoSuppressions(resolve(FRONTEND_ROOT, 'src'), resolve(context.filename), suppressions.length)
  if (total > budget) {
    for (const c of suppressions) {
      report(context, c, 'COMMENT-SUPPRESSION-RATCHET')
    }
  }
}

const srcCommentsRule = {
  meta: {
    type: 'problem',
    docs: { description: 'Enforce Yuzhua source comment governance and anti-noise rules' },
    schema: [{
      type: 'object',
      properties: {
        bannedTerms: { type: 'array', items: { type: 'string' } },
        suppressionBudget: { type: 'number' },
        relPath: { type: 'string' }
      },
      additionalProperties: false
    }]
  },
  create(context) {
    const options = context.options[0] || {}
    const relPath = options.relPath || normalizeRelPath(context.filename)
    if (isExcludedPath(relPath, context.filename)) return {}

    const bannedTerms = getBannedTerms(options)
    const isWhitelisted = WHITELIST_DOC_RE.test(relPath)
    const sourceCode = context.sourceCode

    return {
      Program(programNode) {
        const scriptComments = sourceCode.getAllComments()
        if (isWhitelisted) {
          inspectWhitelistedExports(context, sourceCode, programNode, scriptComments, bannedTerms)
        } else {
          for (const c of scriptComments) {
            if (isJsDocComment(c)) {
              report(context, c, 'COMMENT-UNEXPECTED-DOC')
              checkCommonNoise(context, c, bannedTerms)
            }
          }
        }

        const nonDocs = scriptComments.filter((c) => !isJsDocComment(c))
        const standaloneByLine = new Map()
        for (const c of nonDocs) {
          if (c.type === 'Line' && isStandaloneLineComment(sourceCode, c)) {
            standaloneByLine.set(c.loc.start.line, c)
          }
        }

        const state = { standaloneLines: [], suppressions: [], standaloneByLine }
        for (const c of nonDocs) {
          processNonDocComment(context, sourceCode, c, relPath, state)
          checkCommonNoise(context, c, bannedTerms)
        }

        checkStandaloneLineGroups(context, state.standaloneLines)
        enforceSuppressionRatchet(context, options, state.suppressions)
        checkScriptDensity(context, sourceCode, scriptComments, programNode)
        checkTemplateComments(context, programNode, bannedTerms)
      }
    }
  }
}

function isAssertionCall(node) {
  if (node.type !== 'CallExpression') return false
  const callee = node.callee
  if (callee.type === 'Identifier') {
    return /^(?:assert\w*|assertThat|assertThrows|verify|andExpect|expect)$/i.test(callee.name)
  }
  if (callee.type === 'MemberExpression' && !callee.computed && callee.property.type === 'Identifier') {
    const objName = callee.object.type === 'Identifier' ? callee.object.name : ''
    const propName = callee.property.name
    if (objName === 'assert' || objName === 'Assertions') return true
    if (objName === 'rule' && propName === 'check') return true
    if (/^(?:assert\w*|assertThat|assertThrows|verify|andExpect|expect)$/i.test(propName)) return true
  }
  return false
}

function isTestCaseCall(node) {
  if (node.type !== 'CallExpression') return false
  const callee = node.callee
  if (callee.type === 'Identifier') return callee.name === 'test' || callee.name === 'it'
  if (callee.type === 'MemberExpression' && !callee.computed && callee.object.type === 'Identifier') {
    return (callee.object.name === 'test' || callee.object.name === 'it')
      && ['only', 'skip', 'todo'].includes(callee.property.name)
  }
  return false
}

function collectLocalHelpersWithAssertions(programNode, sourceCode) {
  const helpers = new Set()
  const visitorKeys = sourceCode.visitorKeys

  function containsAssertion(node) {
    if (!node || typeof node !== 'object') return false
    if (isAssertionCall(node)) return true
    for (const key of visitorKeys[node.type] || []) {
      const child = node[key]
      if (Array.isArray(child) ? child.some(containsAssertion) : containsAssertion(child)) return true
    }
    return false
  }

  for (const stmt of programNode.body) {
    if (stmt.type === 'FunctionDeclaration' && stmt.id?.name && containsAssertion(stmt.body)) {
      helpers.add(stmt.id.name)
    }
  }
  return helpers
}

function subtreeHasAssertion(root, visitorKeys, helperNames) {
  let found = false
  function walk(node) {
    if (found || !node || typeof node !== 'object') return
    if (isAssertionCall(node)) {
      found = true
      return
    }
    if (node.type === 'CallExpression' && node.callee.type === 'Identifier' && helperNames.has(node.callee.name)) {
      found = true
      return
    }
    for (const key of visitorKeys[node.type] || []) {
      const child = node[key]
      if (Array.isArray(child)) child.forEach(walk)
      else walk(child)
    }
  }
  walk(root)
  return found
}

const testGuardRule = {
  meta: {
    type: 'problem',
    docs: { description: 'Enforce anti-noise and non-empty assertion baseline on frontend tests' },
    schema: [{
      type: 'object',
      properties: {
        bannedTerms: { type: 'array', items: { type: 'string' } },
        suppressionBudget: { type: 'number' }
      },
      additionalProperties: false
    }]
  },
  create(context) {
    const options = context.options[0] || {}
    const relPath = normalizeRelPath(context.filename)
    if (isExcludedPath(relPath, context.filename)) return {}

    const bannedTerms = getBannedTerms(options)
    const sourceCode = context.sourceCode
    let fileHasAssertion = false
    const testCases = []

    return {
      CallExpression(node) {
        if (isAssertionCall(node)) fileHasAssertion = true
        if (isTestCaseCall(node)) testCases.push(node)
      },
      'Program:exit'(programNode) {
        for (const c of sourceCode.getAllComments()) {
          const directive = classifyDirective(c, relPath)
          if (directive && ['bad-suppression', 'counted-suppression'].includes(directive.kind)) {
            report(context, c, 'COMMENT-SUPPRESSION-RATCHET')
          } else if (directive && directive.kind === 'banned-directive') {
            report(context, c, directive.code)
          }
          checkCommonNoise(context, c, bannedTerms)
        }

        if (!fileHasAssertion) {
          report(context, programNode, 'TEST-EMPTY-ASSERTION')
          return
        }

        const helpers = collectLocalHelpersWithAssertions(programNode, sourceCode)
        for (const tc of testCases) {
          const fn = tc.arguments.find((a) => a.type === 'ArrowFunctionExpression' || a.type === 'FunctionExpression')
          if (fn && !subtreeHasAssertion(fn.body, sourceCode.visitorKeys, helpers)) {
            report(context, tc, 'TEST-EMPTY-ASSERTION')
          }
        }
      }
    }
  }
}

export default {
  meta: { name: 'comment-guard', version: '0.5.0' },
  rules: {
    'src-comments': srcCommentsRule,
    'test-guard': testGuardRule
  }
}
