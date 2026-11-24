export function groupSlotsByDate(slots) {
  const map = new Map()
  for (const s of slots) {
    if (!map.has(s.workDate)) map.set(s.workDate, [])
    map.get(s.workDate).push(s)
  }
  return [...map.entries()].map(([date, list]) => ({ date, list }))
}

// 原因: 提交前兜底校验需读取实时值，不可复用 60 秒轮询时钟缓存以免放行过期时段
export function localNow(date = new Date()) {
  return {
    date: `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`,
    hhmm: `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
  }
}
