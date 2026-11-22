<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Calendar, Picture, Top, ZoomIn } from '@element-plus/icons-vue'
import { consultMessages } from '@/api/user'
import { consultMessages as adminMessages } from '@/api/admin'
import { upload } from '@/api/http'
import { toClock, toDate, toDateTime } from '@/utils/format'
import { closeImageViewerFor, openImageViewer } from '@/utils/imageViewer'
import { ConsultSocket } from '@/utils/consultSocket'
import StatusChip from '@/components/StatusChip.vue'

/**
 * 图文问诊聊天面板，用户端与医生工作台共用。
 *
 * 消息只走 WebSocket 收发，历史一律以库为准：首次和重连从 HISTORY 恢复最近 20 条，
 * 更早历史走 REST beforeId 游标，不再使用总条数和递增页码。
 */
const emit = defineEmits(['statusChanged', 'presenceChanged'])

const props = defineProps({
  sessionId: { type: Number, required: true },
  kind: { type: String, default: 'client' },
  closed: { type: Boolean, default: false },
  peerLabel: { type: String, default: '医生' },
  previewOwner: { type: Symbol, default: null },
  /** 患者侧「去预约挂号」用：建议到院卡片要跳去这位医生的挂号页 */
  doctorId: { type: Number, default: null }
})

const PAGE = 20
const hasMore = ref(false)
const historyReady = ref(false)
let epoch = 0
let pendingMessages = []
// 只记下服务端明确确认的消息ID；不能把较小ID推断成已读。
const confirmedReadIds = new Set()
const moreLabel = computed(() => `加载更早的消息（已载入 ${rows.value.length} 条）`)
const rows = ref([])
const draft = ref('')
const online = ref(false)
const state = ref('connect')
const loadingMore = ref(false)
const box = ref(null)
const file = ref(null)
const suggestOpen = ref(false)
const suggestNote = ref('')
const suggesting = ref(false)
const serverClosed = ref(false)
const roomClosed = computed(() => props.closed || serverClosed.value)
const presence = computed(() => {
  if (roomClosed.value || !historyReady.value || state.value !== 'open') return ''
  return online.value ? `${props.peerLabel}在线` : `${props.peerLabel}暂时离线，可先留言`
})
watch(presence, value => emit('presenceChanged', value), { immediate: true })
const hiddenIds = new Set()
const previewOwner = props.previewOwner || Symbol('consult-preview')
const router = useRouter()
let sock = null

/**
 * 页面是否真的在看这个会话。
 *
 * 已读是"有人看见了"的事实，不是"连接建立了"或"有一帧到了"。后台标签页、
 * 手机切走后连接照样开着、消息照样落进 rows，这时标已读等于替用户替他把消息读了。
 * 所以发 READ 帧前必须同时满足：页面可见 + 历史已渲染。连接没打开时 sock.read()
 * 自己就返回 false（见 ConsultSocket#read），这里不重复判断连接状态。
 */
const pageVisible = ref(true)

/** 取当前页面可见性。测试与 SSR 里没有 document，按"可见"处理，与浏览器前台行为一致 */
function readVisibility() {
  const doc = globalThis.document
  return doc ? doc.visibilityState !== 'hidden' : true
}

/**
 * 发一次已读。前置门是"页面可见 ∧ 历史已渲染"。
 *
 * <p>为什么不需要补发队列：断线与重连退避期间 `historyReady` 已被清成 false，
 * 这个门会直接挡掉调用，`ConsultSocket#read()` 根本不会被调用——不存在
 * "发出去了但被丢掉"的帧。真正的发帧时刻是 HISTORY 渲染完成（`onFrame` 里同样调这里），
 * 而服务端每次 SUBSCRIBE 都会回 HISTORY，所以重连后一定会补上。
 *
 * <p>（曾尝试过在 `onState('open')` 里 flush 一个 pending 标记，但真实时序是
 * `close → connect → open → HISTORY`，`'connect'` 走断开分支会清掉标记，
 * 那段代码永远跑不到。删掉比留着好排查。）
 */
function markReadIfSeen() {
  if (!pageVisible.value || !historyReady.value) return
  sock?.read()
}

const myRole = computed(() => (props.kind === 'admin' ? 'DOCTOR' : 'USER'))
const isMine = (m) => m.senderRole === myRole.value

/** 只认服务端精确确认过的ID：不在集合内的消息（含恢复的被屏蔽消息）一律不算已读 */
function isConfirmedRead(m) {
  return m.senderRole === myRole.value && confirmedReadIds.has(m.id)
}

/** 同一天连续的消息只在第一条上标日期，界面更干净 */
function showDate(i) {
  const cur = toDate(rows.value[i]?.createTime)
  return i === 0 || toDate(rows.value[i - 1]?.createTime) !== cur
}

/** 已载入消息里的图片。点开一张就能左右翻完整场问诊的患部照片 */
const shots = computed(() => rows.value.filter((m) => m.msgType === 'IMAGE'))
const shotUrls = computed(() => shots.value.map((m) => m.content))
const shotCaptions = computed(() =>
  shots.value.map((m) => {
    const who = m.senderName || (m.senderRole === myRole.value ? '我' : props.peerLabel)
    return `${who} 发送于 ${toDateTime(m.createTime)}`
  })
)

function openShot(m) {
  const i = shots.value.findIndex((x) => x.id === m.id)
  if (i < 0) return
  openImageViewer(shotUrls.value, i, { captions: shotCaptions.value, title: '问诊患部照片', owner: previewOwner })
}

async function scrollBottom() {
  await nextTick()
  if (box.value) box.value.scrollTop = box.value.scrollHeight
}

/** 按 id 去重合并、正序展示；isRead 只能前进（READ 已到本地就不再被旧快照回退成未读） */
function mergeMessages(messages) {
  const byId = new Map(rows.value.map((m) => [m.id, m]))
  for (const m of messages) {
    if (!m || m.id == null || hiddenIds.has(m.id)) continue
    const old = byId.get(m.id)
    byId.set(m.id, { ...old, ...m, isRead: old?.isRead || m.isRead || (isConfirmedRead(m) ? 1 : 0) })
  }
  rows.value = [...byId.values()].sort((a, b) => a.id - b.id)
}

function push(msg) {
  mergeMessages([msg])
  scrollBottom()
}

async function loadEarlier() {
  if (loadingMore.value || !historyReady.value || !hasMore.value || !rows.value.length) return
  const version = epoch
  const sid = props.sessionId
  const beforeId = rows.value[0].id
  const api = props.kind === 'admin' ? adminMessages : consultMessages
  loadingMore.value = true
  try {
    const page = await api(sid, { pageNum: 1, pageSize: PAGE, beforeId })
    if (version !== epoch || sid !== props.sessionId) return
    const oldHeight = box.value?.scrollHeight || 0
    const oldTop = box.value?.scrollTop || 0
    mergeMessages(page.records)
    hasMore.value = page.records.length < page.total
    await nextTick()
    if (version === epoch && box.value) {
      box.value.scrollTop = oldTop + box.value.scrollHeight - oldHeight
    }
  } catch {
    // HTTP 封装已经显示错误；保留游标和 hasMore，允许重试。
  } finally {
    if (version === epoch) loadingMore.value = false
  }
}

function onFrame(f) {
  if (f.sessionId != null && f.sessionId !== props.sessionId) return
  if (f.type === 'HISTORY') {
    const messages = f.messages || []
    // 已读状态只前进：换出旧窗口前记下本地已确认的已读，避免旧快照把 READ 退回未读
    const confirmedRead = new Map([...rows.value, ...pendingMessages]
      .filter((m) => m.isRead).map((m) => [m.id, m.isRead]))
    rows.value = []
    mergeMessages(messages)
    mergeMessages(pendingMessages)
    pendingMessages = []
    if (confirmedRead.size) {
      for (const m of rows.value) {
        const r = confirmedRead.get(m.id)
        if (r && !m.isRead) m.isRead = r
      }
    }
    hasMore.value = messages.length === PAGE
    historyReady.value = true
    scrollBottom()
    // 进房/重连只补历史；已读由这里按"页面是否可见"决定，不再由服务端在 SUBSCRIBE 时代办
    markReadIfSeen()
  } else if (f.type === 'MSG') {
    if (!f.message || f.message.sessionId !== props.sessionId) return
    if (!historyReady.value) pendingMessages.push(f.message)
    else push(f.message)
    if (f.message.senderRole !== myRole.value) markReadIfSeen()
  } else if (f.type === 'PRESENCE') {
    online.value = !!f.doctorOnline
  } else if (f.type === 'READ') {
    const peerRole = myRole.value === 'USER' ? 'DOCTOR' : 'USER'
    if (f.sessionId !== props.sessionId || f.role !== peerRole ||
        !Array.isArray(f.readMessageIds) || f.readMessageIds.length > 200 ||
        !f.readMessageIds.every((id) => Number.isSafeInteger(id) && id > 0)) return
    for (const id of f.readMessageIds) confirmedReadIds.add(id)
    for (const m of [...rows.value, ...pendingMessages]) {
      if (isConfirmedRead(m)) m.isRead = 1
    }
  } else if (f.type === 'SESSION') {
    // REST 侧接诊/结束在事务提交后推来的帧：系统消息按 ID 走同一条合并路径（不重复），
    // 会话状态/小结由父组件 load() 刷新——发送区关闭与小结横幅都来自父组件的会话数据
    if (f.status === 2) serverClosed.value = true
    if (f.message?.id != null) {
      if (!historyReady.value) pendingMessages.push(f.message)
      else push(f.message)
    }
    emit('statusChanged', f)
  } else if (f.type === 'MESSAGE_VISIBILITY') {
    if (f.hidden === 1) {
      closeImageViewerFor(previewOwner)
      hiddenIds.add(f.messageId)
      rows.value = rows.value.filter(m => m.id !== f.messageId)
      pendingMessages = pendingMessages.filter(m => m.id !== f.messageId)
    } else {
      hiddenIds.delete(f.messageId)
      if (!f.message || f.message.sessionId !== props.sessionId) return
      if (!historyReady.value) pendingMessages.push(f.message)
      else {
        push(f.message)
        if (f.message.senderRole !== myRole.value) markReadIfSeen()
      }
    }
  } else if (f.type === 'MODERATION') {
    if (f.moderateStatus !== 0) {
      serverClosed.value = true
      rows.value = []
      stop()
    }
    emit('statusChanged', f)
  } else if (f.type === 'ERROR') {
    if (!historyReady.value) {
      // HISTORY 初始化失败：socket 已不可用，进入终止态并停掉重连，提示整页刷新
      stop()
      state.value = 'sync-failed'
      ElMessage.error(f.msg || '会话同步失败，请刷新页面重试')
    } else {
      // 业务层失败（如会话已结束仍发消息）只提示，连接本身没坏，不改连接状态
      ElMessage.error(f.msg || '发送失败')
    }
  }
}

function onState(s) {
  // 'error' 不当成连接状态：ws.onerror 可能在没有紧接 close 的情况下单独触发，
  // 此时连接实际还开着。若把它写成 state，模板会常驻"连接或消息处理失败"横幅、
  // 在线状态也一直为空，而聊天其实可用；若再顺手清 historyReady，就再也不会收到
  // 新的 HISTORY（没断就不会重连），markReadIfSeen 永久提前返回，未读再也清不掉。
  if (s === 'error') {
    online.value = false
    return
  }
  state.value = s
  if (s === 'connect') {
    // 新连接重新以服务端历史为准；本次连接随后收到的屏蔽事件仍保留到 HISTORY 合并。
    hiddenIds.clear()
    closeImageViewerFor(previewOwner)
  }
  if (s === 'open') {
    return
  }
  epoch += 1
  historyReady.value = false
  loadingMore.value = false
  pendingMessages = []
  online.value = false
}

function onSendKeydown(event) {
  // 契约动机: 防止确认候选词时误发送草稿——输入法上屏确认同样触发回车，不先排掉组合事件
  // （isComposing / keyCode 229）就会把没打完的草稿发出去、还会清掉它。
  if (event.isComposing || event.keyCode === 229) return
  event.preventDefault()
  send()
}

/**
 * 发送中状态：按钮在此期间不可再次触发，但输入框保持可编辑。
 * 成功只清掉"本次发出去的那段"，发送期间新敲的字留在输入框里。
 */
const sending = ref(false)
const sendingImage = ref(false)

/**
 * 收到保存回执后移除已发送内容。
 * 按发送瞬间的原文做前缀匹配：用户在等待期间追加的文字不会被一起清掉；
 * 若草稿已被改动到对不上，则整段保留——宁可多留一段，也不能吞掉用户刚敲的字。
 */
function consumeDraft(sent) {
  if (draft.value === sent) draft.value = ''
  else if (draft.value.startsWith(sent)) draft.value = draft.value.slice(sent.length)
}

async function send() {
  if (roomClosed.value) return ElMessage.warning('问诊已结束')
  if (!historyReady.value) return ElMessage.warning('会话尚未就绪，请稍后重试；连接已终止时请刷新页面')
  const sent = draft.value
  const text = sent.trim()
  if (!text) return
  if (sending.value) return
  sending.value = true
  try {
    await sock.chatConfirmed(text, 'TEXT')
    consumeDraft(sent)
  } catch (error) {
    // 未落库的内容留在输入框，用户可直接重试
    ElMessage.error(error.message)
  } finally {
    sending.value = false
  }
}

async function sendImage(e) {
  const f = e.target.files?.[0]
  e.target.value = ''
  if (!f) return
  if (roomClosed.value) return ElMessage.warning('问诊已结束')
  if (!historyReady.value) return ElMessage.warning('会话尚未就绪，请稍后重试')
  if (sendingImage.value) return
  const version = epoch
  const sender = sock
  sendingImage.value = true
  try {
    let url
    try {
      ({ url } = await upload(f, props.kind))
    } catch {
      return // 上传失败的提示由请求层统一给出，这里不重复弹
    }
    if (version !== epoch || sender !== sock || roomClosed.value) return
    try {
      await sender.chatConfirmed(url, 'IMAGE')
    } catch (error) {
      // 文件已上传但消息没落库：明确告知，避免用户以为发过了
      ElMessage.error(`图片未发送：${error.message}`)
    }
  } finally {
    sendingImage.value = false
  }
}

/** 医生建议到院：发一张 SUGGEST 卡片，患者点卡片直达挂号页。附言选填 */
async function suggest() {
  if (roomClosed.value) return ElMessage.warning('问诊已结束')
  if (suggesting.value) return
  if (!historyReady.value) return ElMessage.warning('会话尚未就绪，请稍后重试；连接已终止时请刷新页面')
  suggesting.value = true
  try {
    await sock.chatConfirmed(suggestNote.value.trim(), 'SUGGEST')
    suggestOpen.value = false
    suggestNote.value = ''
    ElMessage.success('已发送到院建议')
  } catch (error) {
    ElMessage.error(error.message)
  } finally {
    suggesting.value = false
  }
}

function goBook() {
  if (props.doctorId) router.push(`/book/${props.doctorId}`)
}

function stop() {
  closeImageViewerFor(previewOwner)
  epoch += 1
  sock?.close()
  sock = null
  loadingMore.value = false
  historyReady.value = false
  pendingMessages = []
}

function openConsult() {
  stop()
  confirmedReadIds.clear()
  hiddenIds.clear()
  serverClosed.value = false
  rows.value = []
  hasMore.value = false
  online.value = false
  state.value = 'connect'
  draft.value = ''
  suggestOpen.value = false
  suggestNote.value = ''
  if (!props.sessionId) return
  sock = new ConsultSocket({ kind: props.kind, onFrame, onState })
  sock.connect(props.sessionId)
}

/**
 * 切回页面时补一次已读：离开期间到达并已渲染（或排队待合并）的消息，
 * 在用户真正看到页面的这一刻才算读过。切走时不做任何事——正在标已读的是"看不见"。
 */
function onVisibilityChange() {
  pageVisible.value = readVisibility()
  if (pageVisible.value) markReadIfSeen()
}

watch(() => [props.sessionId, props.kind], openConsult)
watch(roomClosed, closed => { if (closed) suggestOpen.value = false })
onMounted(() => {
  pageVisible.value = readVisibility()
  globalThis.document?.addEventListener('visibilitychange', onVisibilityChange)
  openConsult()
})
onBeforeUnmount(() => {
  globalThis.document?.removeEventListener('visibilitychange', onVisibilityChange)
  stop()
})
</script>

<template>
  <div class="room focus-room" :class="{ 'client-room': kind === 'client', 'doctor-room': kind === 'admin' }">
    <div v-if="!historyReady || state !== 'open'" class="state" role="status">
      <StatusChip v-if="state === 'replaced'" tone="warning">此账号已在其他页面连接，请刷新后重新进入</StatusChip>
      <StatusChip v-else-if="state === 'failed'" tone="danger">连接已终止，请刷新页面重试</StatusChip>
      <StatusChip v-else-if="state === 'sync-failed'" tone="danger">会话同步失败，请刷新页面重试</StatusChip>
      <StatusChip v-else-if="state === 'close'" tone="warning">连接断开，正在重连…</StatusChip>
      <StatusChip v-else tone="warning">正在同步会话…</StatusChip>
    </div>

    <div ref="box" class="box" tabindex="0" role="log" aria-label="问诊消息记录">
      <div v-if="hasMore" class="more">
        <el-button link :disabled="!historyReady" :loading="loadingMore" @click="loadEarlier">{{ moreLabel }}</el-button>
      </div>

      <template v-for="(m, i) in rows" :key="m.id">
        <p v-if="showDate(i)" class="sep">{{ toDate(m.createTime) }}</p>

        <div v-if="m.msgType === 'SYSTEM'" class="sys">{{ m.content }}</div>

        <div v-else class="msg" :class="{ mine: isMine(m) }">
          <div class="message-content">
            <div class="message-meta">
              <span>{{ m.senderName || (m.senderRole === 'DOCTOR' ? '医生' : '宠物主人') }}</span>
              <time>{{ toClock(m.createTime) }}</time>
              <span v-if="isMine(m)">{{ m.isRead ? '已读' : '已送达' }}</span>
            </div>
          <div class="bubble">
            <button v-if="m.msgType === 'IMAGE'" type="button" class="shot" aria-label="放大查看这张患部照片"
                    @click="openShot(m)">
              <img :src="m.content" class="pic" alt="患部照片" loading="lazy" />
              <span class="hov"><el-icon :size="16"><ZoomIn /></el-icon></span>
            </button>
            <template v-else-if="m.msgType === 'SUGGEST'">
              <p class="sg-t">
                <el-icon><Calendar /></el-icon>
                {{ isMine(m) ? '已建议患者到院就诊' : '医生建议到院就诊' }}
              </p>
              <p v-if="m.content" class="sg-n">{{ m.content }}</p>
              <el-button v-if="kind === 'client' && doctorId" type="primary" size="small" @click="goBook">
                去预约挂号
              </el-button>
            </template>
            <p v-else>{{ m.content }}</p>
          </div>
          </div>
        </div>
      </template>

      <el-empty v-if="!rows.length" :image-size="60"
                :description="kind === 'client' ? '还没有消息，先说说宠物的情况' : (closed ? '暂无聊天记录' : '暂无消息，可先查看病情资料')" />
    </div>

    <div class="editor">
      <template v-if="roomClosed">
        <p class="pc-muted over">{{ kind === 'client' ? '本次问诊已结束，如需再问请到医生主页重新发起。' : '本次问诊已结束，可查看聊天记录与病情资料。' }}</p>
      </template>
      <div v-else class="reply-composer">
        <el-input v-model="draft" type="textarea" :autosize="{ minRows: 2, maxRows: 4 }" resize="none" maxlength="1000"
                  :aria-label="kind === 'client' ? '问诊消息' : '问诊回复'" :placeholder="kind === 'client' ? '描述宠物的情况…' : '输入回复，继续了解宠物的情况…'"
                  @keydown.enter.exact="onSendKeydown" />
        <div class="reply-tools">
          <el-button :disabled="!historyReady" @click="file.click()"><el-icon><Picture /></el-icon>图片</el-button>
          <el-button v-if="kind === 'admin'" :disabled="!historyReady" @click="suggestOpen = true"><el-icon><Calendar /></el-icon>建议到院</el-button>
          <input ref="file" type="file" accept="image/*" hidden @change="sendImage" />
          <span class="reply-hint pc-muted">Enter 发送 · Shift+Enter 换行</span>
          <span v-if="draft.length >= 900" class="reply-count pc-muted">{{ draft.length }} / 1000</span>
          <el-button class="send-button" type="primary" circle aria-label="发送消息" title="发送消息"
                     :disabled="!historyReady || !draft.trim() || sending" :loading="sending" @click="send"><el-icon v-if="!sending"><Top /></el-icon></el-button>
        </div>
      </div>

    </div>

    <el-dialog v-model="suggestOpen" title="建议到院预约" width="min(440px, 92vw)">
      <el-input v-model="suggestNote" type="textarea" :rows="3" maxlength="500" show-word-limit
                placeholder="想补充的话（选填），比如：建议明早空腹到院做个 B 超" />
      <template #footer>
        <el-button @click="suggestOpen = false">取消</el-button>
        <el-button type="primary" :loading="suggesting" :disabled="roomClosed || !historyReady" @click="suggest">发送建议</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.message-content { display: contents; }
.room { display: flex; flex-direction: column; min-height: 0; flex: 1; }
.state { display: flex; gap: 8px; align-items: center; padding-bottom: 8px; flex-wrap: wrap; }
.box {
  flex: 1; overflow-y: auto; background: var(--color-surface-soft); border: 1px solid var(--color-hairline);
  border-radius: var(--radius-md); padding: 16px 12px; min-height: 300px;
}
.more { text-align: center; margin-bottom: 8px; }
.sep { text-align: center; font-size: 13px; color: var(--color-muted); margin: 12px 0 8px; }
.sys {
  margin: 10px auto; max-width: 84%; text-align: center; font-size: 12px;
  color: var(--color-muted); background: var(--color-canvas);
  border-radius: var(--radius-full); padding: 4px 12px;
}
.msg { display: flex; gap: 8px; margin-bottom: 12px; }
.msg.mine { flex-direction: row-reverse; }
.ava { flex-shrink: 0; background: var(--color-surface-strong); color: var(--color-ink); }
.bubble {
  max-width: 74%; background: var(--color-canvas); border: 1px solid var(--color-hairline);
  border-radius: var(--radius-md); padding: 10px 12px;
}
/* 基础消息样式由下方会话主题统一覆盖。 */
.msg.mine .bubble {
  background: var(--color-ink); border-color: var(--color-ink); color: var(--color-on-primary);
}
.bubble p { margin: 0; font-size: 14px; line-height: 1.6; word-break: break-word; white-space: pre-wrap; }
.shot {
  position: relative; display: block; padding: 0; border: none; background: none;
  border-radius: var(--radius-sm); overflow: hidden; cursor: zoom-in;
}
.shot:focus-visible { outline: 2px solid var(--color-ink); outline-offset: 2px; }
.pic { max-width: 190px; border-radius: var(--radius-sm); display: block; }
.hov {
  position: absolute; inset: 0; display: grid; place-items: center; color: #fff;
  background: var(--color-overlay-soft); opacity: 0; transition: opacity .15s var(--ease); pointer-events: none;
}
.shot:focus-visible .hov { opacity: 1; }
@media (hover: hover) and (pointer: fine) {
  .shot:hover .hov { opacity: 1; }
}
.tm { display: block; margin-top: 4px; font-size: 13px; opacity: .72; text-align: right; }
.tm i { font-style: normal; margin-left: 4px; }
/* 到院建议保留预约入口。 */
.sg-t { margin: 0; font-size: 14px; font-weight: 600; display: flex; align-items: center; gap: 4px; }
.sg-n { margin: 6px 0 0; font-size: 14px; line-height: 1.6; white-space: pre-wrap; word-break: break-word; }
.bubble .el-button { margin-top: 8px; }
.editor { padding-top: 8px; }
.over { margin: 8px 0; text-align: center; }
.tools { display: flex; align-items: center; justify-content: space-between; margin-top: 8px; }

/* 两端共用阅读列、品牌气泡与回复区。 */
.focus-room { min-width: 0; }
.focus-room .state { flex-shrink: 0; padding: 8px 16px; }
.focus-room .state :deep(.chip) { height: auto; white-space: normal; line-height: 1.6; }
.focus-room .box { min-height: 0; border: 0; border-radius: 0; background: var(--color-canvas); padding: 20px max(20px, calc((100% - 820px) / 2)); overscroll-behavior: contain; }
.focus-room .box { scrollbar-width: thin; scrollbar-color: var(--color-hairline) transparent; }
.focus-room .box:hover, .focus-room .box:focus-visible { scrollbar-color: var(--color-border-strong) transparent; }
.focus-room .box::-webkit-scrollbar { width: 6px; }
.focus-room .box::-webkit-scrollbar-track { background: transparent; }
.focus-room .box::-webkit-scrollbar-thumb { background: var(--color-hairline); border-radius: var(--radius-full); }
.focus-room .box::-webkit-scrollbar-thumb:hover { background: var(--color-border-strong); }
.focus-room .msg { margin-bottom: 28px; }
.focus-room .msg:last-child { margin-bottom: 0; }
.focus-room .message-content { display: flex; flex-direction: column; align-items: flex-start; width: fit-content; min-width: 0; max-width: 82%; }
.focus-room .mine .message-content { align-items: flex-end; }
.message-meta { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; font-size: 12px; color: var(--color-muted); flex-wrap: wrap; }
.mine .message-meta { justify-content: flex-end; }
.focus-room .bubble { width: fit-content; max-width: 100%; padding: 12px 16px; border: 1px solid color-mix(in srgb, var(--color-primary) 10%, var(--color-canvas)); border-radius: 18px; background: color-mix(in srgb, var(--color-primary-soft) 45%, var(--color-canvas)); }
.focus-room .mine .bubble { background: var(--color-primary-soft); border-color: color-mix(in srgb, var(--color-primary) 18%, var(--color-canvas)); color: var(--color-ink); }
.focus-room .bubble p { font-size: 16px; line-height: 1.85; overflow-wrap: anywhere; }
.focus-room .shot { width: fit-content; max-width: 100%; }
.focus-room .pic { width: 190px; max-width: 100%; max-height: 240px; object-fit: contain; }
.focus-room .sg-t { flex-wrap: wrap; }
.focus-room .sg-n { margin-top: 8px; }
.focus-room .sys { background: transparent; font-size: 12px; line-height: 1.6; }
.focus-room .editor { flex-shrink: 0; width: min(100%, 900px); margin-inline: auto; padding: 12px 20px max(12px, env(safe-area-inset-bottom)); background: var(--color-canvas); }
.reply-composer { padding: 18px 20px 14px; border: 1px solid var(--color-hairline); border-radius: 24px; box-shadow: 0 3px 10px rgb(0 0 0 / 3%); transition: border-color .15s; }
.reply-composer:focus-within { border-color: color-mix(in srgb, var(--color-primary) 40%, var(--color-canvas)); box-shadow: 0 0 0 3px var(--color-primary-soft); }
.focus-room .reply-composer :deep(.el-textarea__inner) { padding: 0; font-size: 16px; line-height: 1.7; box-shadow: none; background: transparent; }
.focus-room .reply-composer :deep(.el-textarea__inner:focus) { outline: none; box-shadow: none; }
.reply-tools { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin-top: 12px; }
.reply-tools .el-button { margin: 0; border-radius: var(--radius-full); font-size: 13px; padding: 7px 12px; }
.reply-tools .el-icon { margin-right: 6px; }
.reply-tools .send-button { margin-left: auto; width: 36px; height: 36px; padding: 0; flex-shrink: 0; }
.reply-tools .send-button .el-icon { margin: 0; font-size: 20px; }
.reply-hint { margin-left: auto; font-size: 12px; }
.reply-count { font-size: 12px; }
.focus-room .over { font-size: 14px; line-height: 1.7; }
@media (max-width: 700px) {
  .focus-room .box { padding: 16px 6px; }
  .focus-room .message-content { max-width: 92%; }
  .focus-room .bubble { padding: 10px 14px; }
  .focus-room .editor { padding: 8px 0 max(8px, env(safe-area-inset-bottom)); }
  .reply-composer { padding: 14px; border-radius: 20px; }
  .client-room .box { padding-inline: 16px; }
  .client-room .editor { padding-inline: 12px; }
  .reply-hint { display: none; }
}
@media (prefers-reduced-motion: reduce) { .reply-composer { transition: none; } }
</style>
