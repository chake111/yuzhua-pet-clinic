export const APPT_STATUS = {
  0: { text: '待接诊', tag: 'warning' },
  1: { text: '就诊中', tag: 'ongoing' },
  2: { text: '已完成', tag: 'success' },
  3: { text: '已取消', tag: 'terminated' },
  4: { text: '未到诊', tag: 'terminated' }
}

export const PAY_STATUS = {
  0: { text: '未支付', tag: 'warning' },
  1: { text: '已支付', tag: 'success' },
  2: { text: '已退款', tag: 'terminated' }
}

export const CONSULT_TYPE = { 1: '在线图文问诊', 2: '到院门诊' }

export const PERIOD = { 1: '上午', 2: '下午', 3: '夜间' }

export const SESSION_STATUS = {
  0: { text: '待接诊', tag: 'warning' },
  1: { text: '进行中', tag: 'ongoing' },
  2: { text: '已结束', tag: 'terminated' }
}

export const MODERATE_STATUS = {
  0: { text: '未处置', tag: 'success' },
  1: { text: '无效关闭', tag: 'warning' },
  2: { text: '违规屏蔽', tag: 'danger' }
}

export const CONSULT_MSG_STATUS = {
  0: { text: '正常', tag: 'success' },
  1: { text: '已屏蔽', tag: 'danger' }
}

export const SPECIES = ['猫', '狗', '兔', '鸟', '爬行', '其他']
export const PET_GENDER = { 0: '未知', 1: '公', 2: '母' }
export const USER_GENDER = { 0: '保密', 1: '男', 2: '女' }
export const VACCINE = {
  0: { text: '未接种', tag: 'warning' },
  1: { text: '部分接种', tag: 'warning' },
  2: { text: '已完成', tag: 'success' }
}
export const DEWORM = {
  0: { text: '待做', tag: 'warning' },
  1: { text: '已做', tag: 'success' }
}

export const WORK_STATUS = { 0: { text: '停诊', tag: 'danger' }, 1: { text: '正常', tag: 'success' } }
export const ONLINE = { 0: '离线', 1: '在线' }

export const REVIEW_STATUS = { 0: { text: '已隐藏', tag: 'terminated' }, 1: { text: '显示中', tag: 'success' } }
export const NOTICE_TYPE = { 1: '公告', 2: '活动', 3: '养宠科普' }
export const NOTICE_STATUS = {
  0: { text: '草稿', tag: 'terminated' },
  1: { text: '已发布', tag: 'success' },
  2: { text: '已下架', tag: 'warning' }
}

export const ACCOUNT_STATUS = { 0: { text: '禁用', tag: 'danger' }, 1: { text: '正常', tag: 'success' } }
export const CANCEL_BY = { USER: '用户取消', DOCTOR: '医生取消', ADMIN: '管理员取消', SYSTEM: '系统超时取消' }

export const options = (map) =>
  Object.entries(map).map(([k, v]) => ({ value: Number(k), label: typeof v === 'string' ? v : v.text }))

export const text = (map, key, fallback = '—') => map[key]?.text ?? map[key] ?? fallback

export const tagType = (map, key) => map[key]?.tag ?? 'plain'
