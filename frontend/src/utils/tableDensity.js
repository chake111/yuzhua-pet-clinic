// 原因: 按容器实测宽度而非 window.innerWidth 收起次要列，避免侧栏折叠在边界宽度引起 66px 溢出
export const DOCTOR_COLUMNS = [
  { key: 'doctor', label: '医生', width: 160 },
  { key: 'department', label: '科室', width: 96 },
  { key: 'specialty', label: '擅长', width: 168 },
  { key: 'experience', label: '经验', width: 72 },
  { key: 'fee', label: '问诊费', width: 86 },
  { key: 'rating', label: '评分', width: 92 },
  { key: 'appointments', label: '接诊', width: 72 },
  { key: 'online', label: '在线', width: 76 },
  { key: 'status', label: '状态', width: 80 },
  { key: 'actions', label: '操作', width: 210 }
]

export const DOCTOR_LADDER = [
  { min: 1112, drop: [] },
  { min: 968, drop: ['experience', 'appointments'] },
  { min: 790, drop: ['experience', 'appointments', 'fee', 'rating'] },
  { min: 714, drop: ['experience', 'appointments', 'fee', 'rating', 'online'] },
  { min: 618, drop: ['experience', 'appointments', 'fee', 'rating', 'online', 'department'] },
  { min: 0, drop: ['experience', 'appointments', 'fee', 'rating', 'online', 'department', 'specialty'] }
]

const ALWAYS_VISIBLE = new Set(['doctor', 'status', 'actions'])

export function hiddenColumns(width, ladder = DOCTOR_LADDER) {
  const tier = ladder.find((step) => width >= step.min) ?? ladder[ladder.length - 1]
  return new Set(tier.drop.filter((key) => !ALWAYS_VISIBLE.has(key)))
}

export function visibleWidth(width, columns = DOCTOR_COLUMNS, ladder = DOCTOR_LADDER) {
  const hidden = hiddenColumns(width, ladder)
  return columns.reduce((sum, column) => sum + (hidden.has(column.key) ? 0 : column.width), 0)
}
