import { getToken } from './token.js'

/**
 * 图文问诊 WebSocket 客户端（用户端与医生工作台共用）。
 *
 * 浏览器建连无法自定义请求头，token 仅能通过 query 传参；握手携带 sessionId 自动订阅。
 * 每次 HISTORY 重置最近消息窗口；指数退避重连上限 4 次，reason='replaced' 时直接终止。
 * chatConfirmed 仅在收到匹配 requestId 的 ACK 时 resolve；ERROR、断连或 10s 超时均 reject 以保留草稿。
 */
export class ConsultSocket {
  constructor({ kind = 'client', onFrame, onState } = {}) {
    this.kind = kind
    this.onFrame = onFrame || (() => {})
    this.onState = onState || (() => {})
    this.room = null
    this.ws = null
    this.pingTimer = null
    this.retry = 0
    this.dead = false
    this.timer = null
    this.pending = new Map()
    this.sequence = 0
  }

  buildUrl(sessionId) {
    const proto = location.protocol === 'https:' ? 'wss' : 'ws'
    let url = `${proto}://${location.host}/api/ws/consult?token=${encodeURIComponent(getToken(this.kind))}`
    if (sessionId) url += `&sessionId=${sessionId}`
    return url
  }

  connect(sessionId) {
    if (sessionId) this.room = sessionId
    if (this.dead || this.ws) return
    if (!getToken(this.kind)) {
      this.dead = true
      this.onState('failed')
      return
    }
    clearTimeout(this.timer)
    this.timer = null
    this.onState('connect')
    const ws = new WebSocket(this.buildUrl(this.room))
    this.ws = ws
    const current = () => !this.dead && this.ws === ws
    ws.onopen = () => {
      if (!current()) return
      this.onState('open')
      this.startPing()
    }
    ws.onmessage = (e) => {
      if (!current()) return
      let frame
      try { frame = JSON.parse(e.data) } catch { return }
      if (!frame || typeof frame !== 'object') return
      const request = this.pending.get(frame.requestId)
      if (request && (frame.type === 'ACK' || frame.type === 'ERROR')) {
        clearTimeout(request.timer)
        this.pending.delete(frame.requestId)
        frame.type === 'ACK' ? request.resolve(frame.message) : request.reject(new Error(frame.msg || '发送失败'))
        return
      }
      if (frame.type === 'HISTORY') {
        if (frame.sessionId !== this.room) return
        this.retry = 0
      }
      this.onFrame(frame)
    }
    ws.onclose = (e) => {
      if (!current()) return
      this.ws = null
      this.rejectPending()
      this.stopPing()
      if (e.reason === 'replaced') {
        this.dead = true
        this.onState('replaced')
        return
      }
      if (this.retry >= 4) {
        this.dead = true
        this.onState('failed')
        return
      }
      this.onState('close')
      const wait = 500 * 2 ** this.retry++
      this.timer = setTimeout(() => this.connect(), wait)
    }
    ws.onerror = () => {
      if (current()) this.onState('error')
    }
  }

  startPing() {
    this.stopPing()
    // 原因: 空闲连接会被代理中间层切断，需每 30 秒发送 PING 保活
    this.pingTimer = setInterval(() => this.send({ type: 'PING' }), 30000)
  }

  stopPing() {
    if (this.pingTimer) clearInterval(this.pingTimer)
    this.pingTimer = null
  }

  send(payload) {
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(payload))
      return true
    }
    return false
  }

  chatConfirmed(content, msgType) {
    if (this.ws?.readyState !== WebSocket.OPEN) {
      return Promise.reject(new Error('连接已断开，消息未发送'))
    }
    const requestId = String(++this.sequence)
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(requestId)
        reject(new Error('尚未收到保存回执，请核对聊天记录后重试'))
      }, 10000)
      this.pending.set(requestId, { resolve, reject, timer })
      if (!this.send({ type: 'CHAT', sessionId: this.room, msgType, content, requestId })) this.rejectPending()
    })
  }

  rejectPending() {
    for (const request of this.pending.values()) {
      clearTimeout(request.timer)
      request.reject(new Error('连接已断开，请核对聊天记录后重试'))
    }
    this.pending.clear()
  }

  read() {
    return this.send({ type: 'READ', sessionId: this.room })
  }

  close() {
    this.rejectPending()
    this.dead = true
    this.stopPing()
    clearTimeout(this.timer)
    this.timer = null
    this.ws?.close()
    this.ws = null
  }
}
