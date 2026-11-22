/**
 * 品牌单一事实源的回归测试：任何一处再出现旧名都视为漏改。
 * 正式文案以 src/brand.js 为准，不在测试里逐字复制。
 */
import assert from 'node:assert/strict'
import { test } from 'node:test'

import { BRAND } from '../src/brand.js'

// 拼接而非整段字面量：测试里也不留全量旧名，避免全仓 grep 审计误报。
const LEGACY_NAME = ['宠物医院预约', '问诊系统'].join('')

test('品牌常量不含旧名', () => {
  for (const [key, value] of Object.entries(BRAND)) {
    assert.equal(typeof value, 'string', `${key} 应为字符串`)
    assert.ok(!value.includes(LEGACY_NAME), `${key} 仍包含旧名：${value}`)
  }
})