#!/usr/bin/env node
import fs from 'node:fs'
import path from 'node:path'

const OVERRIDE_COMMENT_RE = /\/comment-gate-override\s+原因:\s*(.+)/s

export function loadMaintainers(maintainersPath) {
  const abs = path.resolve(maintainersPath)
  if (!fs.existsSync(abs)) {
    throw new Error(`Maintainers allowlist not found: ${abs}`)
  }
  const parsed = JSON.parse(fs.readFileSync(abs, 'utf8'))
  if (!Array.isArray(parsed) || parsed.length === 0) {
    throw new Error(`Maintainers allowlist must be a non-empty JSON array: ${abs}`)
  }
  return new Set(parsed.map((x) => String(x)))
}

async function fetchJson(url, token, fetchImpl = fetch) {
  const headers = {
    Accept: 'application/vnd.github+json',
    'User-Agent': 'yuzhua-guardrails',
  }
  if (token) {
    headers.Authorization = `Bearer ${token}`
  }
  const res = await fetchImpl(url, { headers })
  if (!res.ok) {
    const body = await res.text().catch(() => '')
    throw new Error(`GitHub API ${res.status} for ${url}: ${body.slice(0, 200)}`)
  }
  return res.json()
}

async function fetchAllPaginated(baseUrl, token, fetchImpl = fetch, maxPages = 10) {
  const all = []
  for (let page = 1; page <= maxPages; page++) {
    const sep = baseUrl.includes('?') ? '&' : '?'
    const pageItems = await fetchJson(`${baseUrl}${sep}per_page=100&page=${page}`, token, fetchImpl)
    if (!Array.isArray(pageItems) || pageItems.length === 0) break
    all.push(...pageItems)
    if (pageItems.length < 100) break
  }
  return all
}

export async function getCommitTimestampIso({ apiBase = 'https://api.github.com', repo, sha, token, fetchImpl = fetch }) {
  const commit = await fetchJson(`${apiBase}/repos/${repo}/commits/${sha}`, token, fetchImpl)
  const committerDate = commit?.commit?.committer?.date
  const authorDate = commit?.commit?.author?.date
  const ts = committerDate || authorDate
  if (!ts) {
    throw new Error(`Unable to determine commit timestamp for ${sha}`)
  }
  return ts
}

/**
 * Shared verifier for controlled labels (`comment-gate:override`, `guardrail:tracked`,
 * `guardrail:budget-approved`) via GitHub Issue Events API.
 */
export async function verifyLabelActor({
  apiBase = 'https://api.github.com',
  repo,
  issueNumber,
  labelName,
  prAuthor,
  maintainersPath,
  maintainersSet,
  token,
  requiredAfterIso = null,
  requireOverrideComment = false,
  fetchImpl = fetch,
}) {
  if (!repo || !issueNumber || !labelName) {
    return { ok: false, reason: 'Missing required parameters (repo, issueNumber, labelName)' }
  }
  const maintainers = maintainersSet || loadMaintainers(maintainersPath)
  const events = await fetchAllPaginated(
    `${apiBase}/repos/${repo}/issues/${issueNumber}/events`,
    token,
    fetchImpl
  )

  // Inspect events in chronological order to track latest state of this label
  const relevant = events.filter(
    (ev) =>
      (ev.event === 'labeled' || ev.event === 'unlabeled') &&
      ev.label &&
      ev.label.name === labelName
  )

  if (relevant.length === 0) {
    return { ok: false, reason: `Label "${labelName}" has no labeled event on #${issueNumber}` }
  }

  const latest = relevant[relevant.length - 1]
  if (latest.event !== 'labeled') {
    return { ok: false, reason: `Label "${labelName}" was removed on #${issueNumber}` }
  }

  const actor = latest.actor || {}
  if (actor.type !== 'User') {
    return {
      ok: false,
      reason: `Label "${labelName}" actor "${actor.login || 'unknown'}" has type "${actor.type}", expected "User"`,
    }
  }
  if (!maintainers.has(actor.login)) {
    return {
      ok: false,
      reason: `Label "${labelName}" actor "${actor.login}" is not in human-maintainers.json`,
    }
  }
  if (prAuthor && actor.login === prAuthor) {
    return {
      ok: false,
      reason: `Label "${labelName}" actor "${actor.login}" cannot be the PR author (${prAuthor})`,
    }
  }

  if (requiredAfterIso) {
    const afterMs = Date.parse(requiredAfterIso)
    const labelMs = Date.parse(latest.created_at)
    if (!Number.isFinite(afterMs) || !Number.isFinite(labelMs) || labelMs <= afterMs) {
      return {
        ok: false,
        reason: `Label "${labelName}" timestamp (${latest.created_at}) is not strictly after head commit (${requiredAfterIso})`,
      }
    }
  }

  if (requireOverrideComment) {
    const comments = await fetchAllPaginated(
      `${apiBase}/repos/${repo}/issues/${issueNumber}/comments`,
      token,
      fetchImpl
    )
    const afterMs = requiredAfterIso ? Date.parse(requiredAfterIso) : null
    const validComment = comments.find((c) => {
      if (!c?.user || c.user.type !== 'User' || c.user.login !== actor.login) return false
      if (afterMs !== null) {
        const cMs = Date.parse(c.created_at)
        if (!Number.isFinite(cMs) || cMs <= afterMs) return false
      }
      const match = OVERRIDE_COMMENT_RE.exec(String(c.body || ''))
      if (!match) return false
      const reasonText = match[1].trim()
      return reasonText.length >= 10
    })

    if (!validComment) {
      return {
        ok: false,
        reason: `Missing post-HEAD "/comment-gate-override 原因: <>=10字>" comment from ${actor.login}`,
      }
    }
  }

  return {
    ok: true,
    actor: actor.login,
    labeledAt: latest.created_at,
  }
}
