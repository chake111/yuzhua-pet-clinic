#!/usr/bin/env node
import { execFileSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { pathToFileURL } from 'node:url'
import { loadMaintainers, verifyLabelActor } from './verify-label-actor.mjs'

const SKIP_PATTERNS = [
  { re: /-Dcheckstyle\.skip\b/i, label: '-Dcheckstyle.skip' },
  { re: /-Dmaven\.test\.skip\b/i, label: '-Dmaven.test.skip' },
  { re: /-DskipTests\b/i, label: '-DskipTests' },
  { re: /--no-verify\b/, label: '--no-verify' },
  { re: /\bSKIP_LLM_COMMENT_GATE\b/, label: 'SKIP_LLM_COMMENT_GATE' },
]

const GUARDRAIL_TEST_PREFIXES = [
  'backend/src/test/java/com/yuzhua/arch/',
  'frontend/tests/comment-guard.test.mjs',
  'frontend/tests/eslint-rules.test.mjs',
  '.github/scripts/',
]

function isGuardrailTestFile(filePath) {
  if (filePath.startsWith('.github/scripts/') && !filePath.endsWith('.test.mjs')) {
    return false
  }
  return GUARDRAIL_TEST_PREFIXES.some((p) => filePath.startsWith(p))
}

function isFixtureOrSelfTest(filePath) {
  return (
    filePath.startsWith('.guardrails/redteam-fixtures/') ||
    filePath.startsWith('.guardrails/golden-comments/') ||
    filePath === '.github/scripts/check-gate-integrity.mjs' ||
    filePath === '.github/scripts/comment-gate.test.mjs' ||
    filePath === 'frontend/tests/comment-guard.test.mjs' ||
    filePath === 'frontend/tests/eslint-rules.test.mjs' ||
    filePath === 'backend/src/test/java/com/yuzhua/arch/CommentGuardrailsTest.java'
  )
}

function isNegativeTestLine(line) {
  return /(?:@Test|\btest\s*\(|\bit\s*\(|assertThrows|assertFalse|rejects|forbid|invalid|violation|should\s*(?:fail|reject|block))/i.test(
    line
  )
}

/**
 * Pure diff analyzer for gate integrity checks (skip flags, negative test deletions,
 * and suppression-budget.json increases).
 */
export function analyzeIntegrityDiff({ diffText, baseBudget, headBudget }) {
  const errors = []
  let requiresBudgetApproval = false

  const lines = diffText.split(/\r?\n/)
  let currentFile = ''
  let oldFile = ''
  let currentLine = 0
  const testCaseBalance = new Map()
  const deletedNegativeLines = []

  const bump = (file, kind, delta) => {
    if (!testCaseBalance.has(file)) {
      testCaseBalance.set(file, { added: 0, deleted: 0 })
    }
    testCaseBalance.get(file)[kind] += delta
  }

  for (const line of lines) {
    if (line.startsWith('diff --git ')) {
      currentFile = ''
      oldFile = ''
      continue
    }
    if (line.startsWith('deleted file mode ')) {
      if (oldFile && isGuardrailTestFile(oldFile)) {
        errors.push(`禁止删除门禁/负例测试文件: ${oldFile}`)
      }
      continue
    }
    if (line.startsWith('--- a/')) {
      oldFile = line.slice(6)
      continue
    }
    if (line.startsWith('--- /dev/null')) {
      oldFile = ''
      continue
    }
    if (line.startsWith('+++ b/')) {
      currentFile = line.slice(6)
      continue
    }
    if (line.startsWith('+++ /dev/null')) {
      if (oldFile && isGuardrailTestFile(oldFile)) {
        errors.push(`禁止删除门禁/负例测试文件: ${oldFile}`)
      }
      currentFile = ''
      continue
    }
    if (line.startsWith('@@ ')) {
      const m = /\+(\d+)/.exec(line)
      currentLine = m ? Number(m[1]) : 0
      continue
    }

    if (line.startsWith('+') && !line.startsWith('+++')) {
      const content = line.slice(1)
      if (currentFile && !isFixtureOrSelfTest(currentFile)) {
        for (const pat of SKIP_PATTERNS) {
          if (pat.re.test(content)) {
            errors.push(`${currentFile}:${currentLine} — 禁止引入门禁跳过参数 ${pat.label}`)
          }
        }
      }
      if (currentFile && isGuardrailTestFile(currentFile)) {
        if (/(?:@Test|\btest\s*\(|\bit\s*\()/.test(content)) {
          bump(currentFile, 'added', 1)
        }
      }
      currentLine++
    } else if (line.startsWith('-') && !line.startsWith('---')) {
      const content = line.slice(1)
      const targetFile = currentFile || oldFile
      if (targetFile && isGuardrailTestFile(targetFile)) {
        if (/(?:@Test|\btest\s*\(|\bit\s*\()/.test(content)) {
          bump(targetFile, 'deleted', 1)
        } else if (isNegativeTestLine(content)) {
          deletedNegativeLines.push({ file: targetFile, text: content.trim() })
        }
      }
    }
  }

  for (const [file, counts] of testCaseBalance.entries()) {
    if (counts.deleted > counts.added) {
      errors.push(
        `${file} — 禁止净删减门禁/负例测试用例（删除 ${counts.deleted} 个，新增 ${counts.added} 个）`
      )
    }
  }
  // If negative assertion lines were deleted in a guardrail test without replacing test cases
  if (deletedNegativeLines.length > 0 && errors.length === 0) {
    for (const del of deletedNegativeLines) {
      const bal = testCaseBalance.get(del.file)
      if (!bal || bal.added === 0) {
        errors.push(`${del.file} — 疑似删减负例断言: ${del.text.slice(0, 80)}`)
      }
    }
  }

  if (headBudget && typeof headBudget === 'object') {
    const base = baseBudget && typeof baseBudget === 'object' ? baseBudget : {}
    for (const [k, headVal] of Object.entries(headBudget)) {
      if (typeof headVal !== 'number') continue
      const baseVal = typeof base[k] === 'number' ? base[k] : 0
      if (headVal > baseVal) {
        requiresBudgetApproval = true
      }
    }
  }

  return {
    ok: errors.length === 0,
    errors,
    requiresBudgetApproval,
  }
}

export async function runCheckGateIntegrity({
  repoDir = '.',
  baseSha,
  headSha = 'HEAD',
  maintainersPath,
  repoSlug,
  prNumber,
  prAuthor,
  token,
  apiBase = 'https://api.github.com',
  fetchImpl = fetch,
}) {
  const resolvedRepo = path.resolve(repoDir)
  const diffText = execFileSync(
    'git',
    ['-C', resolvedRepo, '-c', 'core.hooksPath=/dev/null', 'diff', '-U0', `${baseSha}..${headSha}`],
    { encoding: 'utf8', maxBuffer: 20 * 1024 * 1024 }
  )

  let baseBudget = null
  try {
    const rawBase = execFileSync(
      'git',
      ['-C', resolvedRepo, '-c', 'core.hooksPath=/dev/null', 'show', `${baseSha}:.guardrails/suppression-budget.json`],
      { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }
    )
    baseBudget = JSON.parse(rawBase)
  } catch {
    baseBudget = { backend_main: 0, frontend_src: 0, backend_test: 15 }
  }

  let headBudget = null
  const headBudgetPath = path.join(resolvedRepo, '.guardrails', 'suppression-budget.json')
  if (fs.existsSync(headBudgetPath)) {
    headBudget = JSON.parse(fs.readFileSync(headBudgetPath, 'utf8'))
  }

  const analysis = analyzeIntegrityDiff({ diffText, baseBudget, headBudget })
  const errors = [...analysis.errors]

  if (analysis.requiresBudgetApproval) {
    if (!token || !repoSlug || !prNumber) {
      errors.push('检测到 .guardrails/suppression-budget.json 配额上调，但缺少 GitHub API 上下文以校验 guardrail:budget-approved 标签')
    } else {
      const maintainersSet = loadMaintainers(maintainersPath)
      const labelRes = await verifyLabelActor({
        apiBase,
        repo: repoSlug,
        issueNumber: prNumber,
        labelName: 'guardrail:budget-approved',
        prAuthor,
        maintainersSet,
        token,
        fetchImpl,
      })
      if (!labelRes.ok) {
        errors.push(`禁止未经人类批准上调 suppression-budget.json: ${labelRes.reason}`)
      }
    }
  }

  return {
    ok: errors.length === 0,
    errors,
    requiresBudgetApproval: analysis.requiresBudgetApproval,
  }
}

async function main() {
  const args = process.argv.slice(2)
  let repoDir = '.'
  let baseSha = process.env.BASE_SHA || ''
  let headSha = process.env.HEAD_SHA || 'HEAD'
  let maintainersPath = path.resolve(
    path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')),
    '../../.guardrails/human-maintainers.json'
  )

  for (let i = 0; i < args.length; i++) {
    if (args[i] === '--repo' && args[i + 1]) repoDir = args[++i]
    else if (args[i] === '--base' && args[i + 1]) baseSha = args[++i]
    else if (args[i] === '--head' && args[i + 1]) headSha = args[++i]
    else if (args[i] === '--maintainers' && args[i + 1]) maintainersPath = args[++i]
  }

  if (!baseSha) {
    console.error('[check-gate-integrity] 缺少 BASE_SHA / --base')
    process.exit(1)
  }

  const res = await runCheckGateIntegrity({
    repoDir,
    baseSha,
    headSha,
    maintainersPath,
    repoSlug: process.env.GITHUB_REPOSITORY,
    prNumber: process.env.PR_NUMBER ? Number(process.env.PR_NUMBER) : null,
    prAuthor: process.env.PR_AUTHOR,
    token: process.env.GITHUB_TOKEN,
  })

  if (!res.ok) {
    for (const err of res.errors) {
      console.error(`[check-gate-integrity] ${err}`)
    }
    process.exit(1)
  }
  console.log('[check-gate-integrity] passed')
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main()
}
