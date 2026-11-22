// node tests/business-rules.test.mjs
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { canPay } from '../src/utils/format.js'
import { closeImageViewerFor } from '../src/utils/imageViewer.js'

const source = path => readFileSync(new URL('../src/' + path, import.meta.url), 'utf8')
function component(path, bindings, exports) {
  const code = source(path).match(/<script setup>([\s\S]*?)<\/script>/)[1]
    .replace(/import[\s\S]*?from\s+['"][^'"]+['"]/g, '')
  return new Function(...Object.keys(bindings), code + '\n' + exports)(...Object.values(bindings))
}
const pending = { status: 0, payStatus: 0, payDeadline: '2026-10-16 12:00:00' }
const deadline = new Date('2026-10-16T12:00:00').getTime()
assert.equal(canPay(pending, deadline - 1), true)
assert.equal(canPay(pending, deadline), false)
assert.equal(canPay({ ...pending, payStatus: 1 }, deadline - 1), false)
assert.equal(canPay({ ...pending, payDeadline: null }, deadline - 1), false)
for (const path of ['views/client/MyOrders.vue', 'views/client/OrderDetail.vue']) {
  let calls = 0
  const orderPage = component(path, { computed, ref, onMounted: () => {}, onBeforeUnmount: () => {},
    useRoute: () => ({ params: { id: 9 }, query: {} }), useRouter: () => ({}), canPay,
    payAppointment: async () => { calls++ }
  }, path.includes('MyOrders') ? 'return { pay, now }' : 'return { pay, order, now }')
  orderPage.now.value = deadline
  if (orderPage.order) orderPage.order.value = pending
  await orderPage.pay(pending)
  assert.equal(calls, 0)
}
const warnings = [], successes = [], errors = []
const vue = { computed, reactive, ref, watch, nextTick, onMounted: () => {}, onBeforeUnmount: () => {} }
let resolveSend, rejectSend, sendCount = 0
class Socket {
  close() {}
  read() {}
  connect() {}
  chatConfirmed() { sendCount++; return new Promise((resolve, reject) => { resolveSend = resolve; rejectSend = reject }) }
}
const props = reactive({ sessionId: 9, kind: 'admin', closed: false })
const events = []
const panel = component('components/ConsultPanel.vue', { ...vue,
  defineEmits: () => (...args) => events.push(args), defineProps: () => props,
  useRouter: () => ({}), ConsultSocket: Socket, closeImageViewerFor,
  ElMessage: { warning: s => warnings.push(s), success: s => successes.push(s), error: s => errors.push(s) }
}, 'return { rows, suggestNote, suggestOpen, suggesting, roomClosed, historyReady, suggest, openConsult, onFrame, online, send, draft }')
panel.openConsult()
panel.onFrame({ type: 'HISTORY', sessionId: 9, messages: [{ id: 1, msgType: 'TEXT', content: '原内容' }] })
panel.onFrame({ type: 'PRESENCE', sessionId: 9, doctorOnline: true })
assert.equal(panel.online.value, true)
panel.onFrame({ type: 'MESSAGE_VISIBILITY', sessionId: 9, messageId: 1, hidden: 1 })
assert.equal(panel.rows.value.length, 0)
// 治理事件先到、旧快照后到：屏蔽内容仍不能回到页面。
panel.onFrame({ type: 'HISTORY', sessionId: 9, messages: [{ id: 1, content: '旧快照' }] })
assert.equal(panel.rows.value.length, 0)
panel.onFrame({ type: 'MESSAGE_VISIBILITY', sessionId: 9, messageId: 1, hidden: 0, message: { id: 1, sessionId: 9, content: '已恢复' } })
assert.equal(panel.rows.value[0].content, '已恢复')
panel.suggestOpen.value = true
panel.suggestNote.value = '保留附言'
const failedSend = panel.suggest()
assert.equal(panel.suggesting.value, true)
assert.equal(successes.length, 0)
assert.equal(panel.suggestNote.value, '保留附言')
rejectSend(new Error('服务端拒绝'))
await failedSend
assert.equal(panel.suggestNote.value, '保留附言')
assert.equal(successes.length, 0)
const savedSend = panel.suggest()
resolveSend({ id: 2 })
await savedSend
assert.equal(panel.suggestNote.value, '')
assert.equal(successes.length, 1)
panel.suggestOpen.value = true
panel.suggestNote.value = '关闭时输入'
panel.onFrame({ type: 'SESSION', sessionId: 9, status: 2 })
await nextTick()
assert.equal(panel.roomClosed.value, true)
assert.equal(panel.suggestOpen.value, false)
await panel.suggest()
assert.equal(sendCount, 2)
panel.draft.value = '关闭后文字'
await panel.send()
assert.equal(panel.draft.value, '关闭后文字')
panel.onFrame({ type: 'MODERATION', sessionId: 9, moderateStatus: 2 })
assert.equal(panel.rows.value.length, 0)
assert.equal(panel.historyReady.value, false)

// 管理员游标翻页执行页面实际逻辑。
const queries = []
const history = component('views/admin/ConsultRecords.vue', { ...vue, useAdminStore: () => ({}),
  consultMessages: async (id, q) => {
    queries.push(q)
    const records = q.beforeId ? Array.from({ length: 5 }, (_, i) => ({ id: 5 - i }))
      : Array.from({ length: 100 }, (_, i) => ({ id: 105 - i }))
    return { records, total: q.beforeId ? 5 : 105 }
  }
}, 'return { openRoom, loadEarlier, msgs, msgHasMore }')
await history.openRoom({ id: 9 })
assert.equal(history.msgHasMore.value, true)
await history.loadEarlier()
assert.equal(queries[1].beforeId, 6)
assert.deepEqual(history.msgs.value.map(m => m.id), Array.from({ length: 105 }, (_, i) => i + 1))
assert.equal(history.msgHasMore.value, false)

const schedule = component('views/admin/Schedules.vue', { ...vue, today: () => '2026-10-16' },
  'return { form, editing, doctorStopped, openEdit }')
schedule.openEdit({ id: 1, doctorId: 1, doctorAvailable: false, status: 0 })
assert.equal(schedule.doctorStopped.value, true)
// 编辑弹窗不再提供状态控件，剩下的状态单选只服务于新建：医生停诊时"出诊"不可选。
// editing 分支已经不进这个表达式（模板用 v-if="!editing" 整块隐藏）。
const disabled = source('views/admin/Schedules.vue').match(/:disabled="(o.value === 1[^"]+)"/)[1]
const restoreDisabled = new Function('o', 'doctorStopped', 'return ' + disabled)
assert.equal(restoreDisabled({ value: 1 }, true), true)
assert.equal(restoreDisabled({ value: 0 }, true), false)
assert.equal(restoreDisabled({ value: 1 }, false), false)
const mySchedule = component('views/admin/MySchedule.vue', { ...vue,
  localNow: () => ({ date: '2026-10-16', hhmm: '12:00' }),
  slotEnded: (await import('../src/utils/format.js')).slotEnded
}, 'return { slotState }')
assert.equal(mySchedule.slotState({ doctorAvailable: false, status: 1, remainNum: 2 }).label, '已停诊')
assert.equal(mySchedule.slotState({ status: 1, remainNum: 2, workDate: '2026-10-15', endTime: '12:00:00' }).label, '已结束')
assert.equal(mySchedule.slotState({ status: 1, remainNum: 2, workDate: '2026-10-17', endTime: '12:00:00' }).label, '可预约')
assert.equal(mySchedule.slotState({ status: 1, remainNum: 0, workDate: '2026-10-17', endTime: '12:00:00' }).label, '已约满')

// 公告：草稿允许空正文，发布才要求正文。
const noticeCalls = []
const noticeWarnings = []
const notices = component('views/admin/Notices.vue', { ...vue,
  ElMessage: { warning: m => noticeWarnings.push(m), success: () => {} },
  ElMessageBox: { confirm: () => Promise.resolve() },
  NOTICE_STATUS: {}, NOTICE_TYPE: {}, options: () => [], tagType: () => '', text: () => '', toDate: v => v || '',
  createNotice: async d => { noticeCalls.push(['create', d]); return {} },
  updateNotice: async (id, d) => { noticeCalls.push(['update', id, d]); return {} },
  noticePage: async () => ({ records: [], total: 0 }),
  noticeDetail: async () => ({}), noticeStatus: async () => {}, removeNotice: async () => {}
}, 'return { form, submit, editing }')

async function noticeSubmit(form) {
  noticeCalls.length = 0
  noticeWarnings.length = 0
  notices.editing.value = null
  notices.form.value = { title: '标题', cover: '', summary: '', content: '', type: 1, topFlag: 0, status: 1, ...form }
  await notices.submit()
  return { calls: [...noticeCalls], warnings: [...noticeWarnings] }
}

let result = await noticeSubmit({ status: 0, content: '' })
assert.equal(result.calls[0]?.[0], 'create', '草稿应允许空正文')
assert.deepEqual(result.warnings, [])
result = await noticeSubmit({ status: 1, content: '   ' })
assert.equal(result.calls.length, 0, '发布前空正文不应提交')
assert.match(result.warnings.at(-1), /请填写正文/)
result = await noticeSubmit({ status: 1, content: '正文' })
assert.equal(result.calls[0]?.[0], 'create')
result = await noticeSubmit({ title: '  ', status: 0, content: '' })
assert.equal(result.calls.length, 0, '标题始终必填')
assert.match(result.warnings.at(-1), /请填写标题/)

// ConsultSocket 协议层（ACK/ERROR/断连）的回执回归见 consult-send.test.mjs，不在此重复。
console.log('business-rules passed: payment deadline, governance, suggestion draft, admin history, schedule availability, notice draft')
