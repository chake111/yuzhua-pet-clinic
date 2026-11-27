#!/usr/bin/env node
import { execFileSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { pathToFileURL } from 'node:url'
import { loadMaintainers, verifyLabelActor } from '../.github/scripts/verify-label-actor.mjs'

const ISSUE_REF_RE = /TODO\(#(\d+)\)|@(?:ts-expect-error|SuppressWarnings)\b[^\n]*?#(\d+)|eslint-disable-next-line\b[^\n]*?#(\d+)/g
const BAD_TODO_RE = /\b(?:FIXME|HACK|XXX)\b|\bTODO\b(?!\(#\d+\):)/

function walkSourceFiles(repoDir) {
  const roots = [
    path.join(repoDir, 'backend', 'src'),
    path.join(repoDir, 'frontend', 'src'),
    path.join(repoDir, 'frontend', 'tests'),
  ]
  const exts = ['.java', '.js', '.ts', '.vue']
  const out = []

  function walk(dir) {
    if (!fs.existsSync(dir)) return
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name)
      if (entry.isDirectory()) {
        if (entry.name === 'node_modules' || entry.name === 'target' || entry.name === 'dist' || entry.name === '.guardrails') {
          continue
        }
        walk(full)
      } else if (exts.some((e) => entry.name.endsWith(e))) {
        out.push(full)
      }
    }
  }

  for (const r of roots) walk(r)
  return out.sort()
}

export function extractIssueRefsFromLine(line) {
  const ids = []
  ISSUE_REF_RE.lastIndex = 0
  let m
  while ((m = ISSUE_REF_RE.exec(line)) !== null) {
    const id = Number(m[1] || m[2] || m[3])
    if (Number.isInteger(id) && id > 0) ids.push(id)
  }
  return ids
}

export function scanPrDiffRefs({ repoDir, baseSha, headSha }) {
  const diff = execFileSync(
    'git',
    ['-C', repoDir, '-c', 'core.hooksPath=/dev/null', 'diff', '-U0', `${baseSha}..${headSha}`, '--', 'backend/src', 'frontend/src', 'frontend/tests', ':!.guardrails'],
    { encoding: 'utf8', maxBuffer: 20 * 1024 * 1024 }
  )

  const refs = new Map()
  const badTodos = []
  let currentFile = ''
  let currentLine = 0

  for (const line of diff.split(/\r?\n/)) {
    if (line.startsWith('+++ b/')) {
      currentFile = line.slice(6)
      continue
    }
    if (line.startsWith('@@ ')) {
      const m = /\+(\d+)/.exec(line)
      currentLine = m ? Number(m[1]) : 0
      continue
    }
    if (line.startsWith('+') && !line.startsWith('+++')) {
      const content = line.slice(1)
      if (!currentFile.startsWith('.guardrails/')) {
        if (BAD_TODO_RE.test(content)) {
          badTodos.push({ file: currentFile, line: currentLine, text: content.trim() })
        }
        for (const id of extractIssueRefsFromLine(content)) {
          if (!refs.has(id)) refs.set(id, [])
          refs.get(id).push({ file: currentFile, line: currentLine })
        }
      }
      currentLine++
    }
  }

  return { refs, badTodos }
}

export function scanRepoSweepRefs({ repoDir }) {
  const refs = new Map()
  const badTodos = []
  for (const absPath of walkSourceFiles(repoDir)) {
    const rel = path.relative(repoDir, absPath).replace(/\\/g, '/')
    if (rel.startsWith('.guardrails/')) continue
    const lines = fs.readFileSync(absPath, 'utf8').split(/\r?\n/)
    for (let i = 0; i < lines.length; i++) {
      const line = lines[i]
      if (BAD_TODO_RE.test(line)) {
        // Ignore non-comment strings when possible: only check lines that have comment markers
        if (/\/\/|\/\*|<!--/.test(line)) {
          badTodos.push({ file: rel, line: i + 1, text: line.trim() })
        }
      }
      for (const id of extractIssueRefsFromLine(line)) {
        if (!refs.has(id)) refs.set(id, [])
        refs.get(id).push({ file: rel, line: i + 1 })
      }
    }
  }
  return { refs, badTodos }
}

export async function verifyIssueRefs({
  refs,
  badTodos = [],
  apiBase = 'https://api.github.com',
  repoSlug,
  prAuthor,
  maintainersPath,
  token,
  fetchImpl = fetch,
}) {
  const errors = []

  for (const bad of badTodos) {
    errors.push(`${bad.file}:${bad.line} — 禁止 FIXME/HACK/XXX 或无编号 TODO: ${bad.text.slice(0, 80)}`)
  }

  if (refs.size === 0) {
    return { ok: errors.length === 0, checkedIssues: 0, errors }
  }

  if (!repoSlug || !token) {
    errors.push('检测到 Issue 引用但缺少 GITHUB_REPOSITORY 或 GITHUB_TOKEN，按 Fail-Closed 阻断')
    return { ok: false, checkedIssues: 0, errors }
  }

  const maintainersSet = loadMaintainers(maintainersPath)

  for (const [issueNumber, sites] of refs.entries()) {
    const siteStr = sites.map((s) => `${s.file}:${s.line}`).slice(0, 3).join(', ')
    let issueData
    try {
      const res = await fetchImpl(`${apiBase}/repos/${repoSlug}/issues/${issueNumber}`, {
        headers: {
          Accept: 'application/vnd.github+json',
          Authorization: `Bearer ${token}`,
          'User-Agent': 'yuzhua-guardrails',
        },
      })
      if (!res.ok) {
        errors.push(`#${issueNumber} (${siteStr}) 查询失败: HTTP ${res.status}`)
        continue
      }
      issueData = await res.json()
    } catch (err) {
      errors.push(`#${issueNumber} (${siteStr}) 请求异常: ${err.message}`)
      continue
    }

    if (issueData.pull_request) {
      errors.push(`#${issueNumber} (${siteStr}) 是 PR 而非 Issue，必须关联真实跟踪 Issue`)
      continue
    }
    if (issueData.state !== 'open') {
      errors.push(`#${issueNumber} (${siteStr}) 状态为 "${issueData.state}"，必须为 open`)
      continue
    }
    const labels = Array.isArray(issueData.labels) ? issueData.labels.map((l) => l.name) : []
    if (!labels.includes('guardrail:tracked')) {
      errors.push(`#${issueNumber} (${siteStr}) 缺少 guardrail:tracked 标签`)
      continue
    }

    const actorRes = await verifyLabelActor({
      apiBase,
      repo: repoSlug,
      issueNumber,
      labelName: 'guardrail:tracked',
      prAuthor,
      maintainersSet,
      token,
      fetchImpl,
    })
    if (!actorRes.ok) {
      errors.push(`#${issueNumber} (${siteStr}) guardrail:tracked 校验失败: ${actorRes.reason}`)
    }
  }

  return { ok: errors.length === 0, checkedIssues: refs.size, errors }
}

async function main() {
  const args = process.argv.slice(2)
  let mode = 'pr-diff'
  let repoDir = '.'
  let baseSha = process.env.BASE_SHA || ''
  let headSha = process.env.HEAD_SHA || 'HEAD'
  let maintainersPath = path.resolve(path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')), '../.guardrails/human-maintainers.json')

  for (let i = 0; i < args.length; i++) {
    if (args[i] === '--mode' && args[i + 1]) mode = args[++i]
    else if (args[i] === '--repo' && args[i + 1]) repoDir = args[++i]
    else if (args[i] === '--base' && args[i + 1]) baseSha = args[++i]
    else if (args[i] === '--head' && args[i + 1]) headSha = args[++i]
    else if (args[i] === '--maintainers' && args[i + 1]) maintainersPath = args[++i]
  }

  const resolvedRepo = path.resolve(repoDir)
  let scan
  if (mode === 'pr-diff') {
    if (!baseSha) {
      console.error('pr-diff 模式缺少 BASE_SHA / --base')
      process.exit(1)
    }
    scan = scanPrDiffRefs({ repoDir: resolvedRepo, baseSha, headSha })
  } else if (mode === 'repo-sweep') {
    scan = scanRepoSweepRefs({ repoDir: resolvedRepo })
  } else {
    console.error(`未知模式: ${mode}`)
    process.exit(1)
  }

  const result = await verifyIssueRefs({
    refs: scan.refs,
    badTodos: scan.badTodos,
    repoSlug: process.env.GITHUB_REPOSITORY,
    prAuthor: process.env.PR_AUTHOR,
    maintainersPath,
    token: process.env.GITHUB_TOKEN,
  })

  if (!result.ok) {
    for (const err of result.errors) {
      console.error(`[check-issue-refs] ${err}`)
    }
    process.exit(1)
  }
  console.log(`[check-issue-refs] mode=${mode} passed (checked ${result.checkedIssues} issue ref(s))`)
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main()
}
