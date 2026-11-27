const pad = (n) => String(n).padStart(2, '0')

export function toDate(v) {
  if (!v) return ''
  return String(v).replace('T', ' ').slice(0, 10)
}

export function toDateTime(v) {
  if (!v) return '—'
  return String(v).replace('T', ' ').slice(0, 16)
}

export function toClock(v) {
  if (!v) return '—'
  return String(v).replace('T', ' ').slice(11, 16)
}

export function today() {
  const d = new Date()
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

// 原因: 需由调用方注入响应式时钟触发更新；同本地时区零填充字符串按字典序比较可避开 Date 解析差异
export function slotEnded(s, todayStr, nowHHmm) {
  if (!s?.workDate || !s?.endTime) return false
  return s.workDate < todayStr ||
         (s.workDate === todayStr && s.endTime.slice(0, 5) <= nowHHmm)
}

export function since(v) {
  if (!v) return ''
  const t = new Date(String(v).replace('T', ' ')).getTime()
  const min = Math.floor((Date.now() - t) / 60000)
  if (min < 1) return '刚刚'
  if (min < 60) return `${min} 分钟前`
  if (min < 1440) return `${Math.floor(min / 60)} 小时前`
  return toDate(v)
}

export function money(v) {
  const n = Number(v ?? 0)
  return `¥ ${n.toFixed(2)}`
}

export function canPay(row, now = Date.now()) {
  return row?.status === 0 && row.payStatus === 0 && !!row.payDeadline &&
    new Date(String(row.payDeadline).replace(' ', 'T')).getTime() > now
}

export function maskPhone(v) {
  const s = String(v || '')
  return s.length === 11 ? `${s.slice(0, 3)}****${s.slice(7)}` : s || '—'
}

export function dayLabel(dateStr) {
  const t = today()
  if (dateStr === t) return '今天'
  const tomorrow = new Date(Date.now() + 86400000)
  const tm = `${tomorrow.getFullYear()}-${pad(tomorrow.getMonth() + 1)}-${pad(tomorrow.getDate())}`
  if (dateStr === tm) return '明天'
  const week = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
  const d = new Date(String(dateStr).replace(/-/g, '/'))
  return `${d.getMonth() + 1}月${d.getDate()}日 ${week[d.getDay()]}`
}
