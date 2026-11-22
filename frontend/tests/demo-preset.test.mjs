/**
 * 演示入口白名单解析（resolveDemoPreset）不变量测试：
 *   1. 合法角色映射与大小写/空白归一化；
 *   2. 跨端隔离（client 不得匹配 admin/doctor，admin 不得匹配 user）；
 *   3. 未传/空密码安全降级为空串（同时验证 Node 下 import.meta.env 为 undefined 时不报错）；
 *   4. 非字符串/数组/未知参数/原型键一律返回 null。
 */
import assert from 'node:assert/strict'
import { test } from 'node:test'

import { resolveDemoPreset } from '../src/utils/demoPreset.js'

test('合法角色按登录侧返回对应演示账号与传入口令', () => {
  assert.deepEqual(resolveDemoPreset('client', 'user', 'DemoPass#2026'), {
    username: 'zhangsan',
    password: 'DemoPass#2026'
  })
  assert.deepEqual(resolveDemoPreset('admin', 'doctor', 'DemoPass#2026'), {
    username: 'doctor01',
    password: 'DemoPass#2026'
  })
  assert.deepEqual(resolveDemoPreset('admin', 'admin', 'DemoPass#2026'), {
    username: 'admin',
    password: 'DemoPass#2026'
  })
  assert.deepEqual(resolveDemoPreset('client', ' USER ', 'DemoPass#2026'), {
    username: 'zhangsan',
    password: 'DemoPass#2026'
  })
})

test('跨端角色严格隔离，禁止用户端预填后台账号或反向越界', () => {
  assert.equal(resolveDemoPreset('client', 'admin', 'DemoPass#2026'), null)
  assert.equal(resolveDemoPreset('client', 'doctor', 'DemoPass#2026'), null)
  assert.equal(resolveDemoPreset('admin', 'user', 'DemoPass#2026'), null)
  assert.equal(resolveDemoPreset('unknown', 'user', 'DemoPass#2026'), null)
})

test('演示口令缺失或为空时安全降级为空串，不回退任何硬编码密码', () => {
  assert.deepEqual(resolveDemoPreset('client', 'user'), {
    username: 'zhangsan',
    password: ''
  })
  assert.deepEqual(resolveDemoPreset('admin', 'doctor', ''), {
    username: 'doctor01',
    password: ''
  })
  assert.deepEqual(resolveDemoPreset('admin', 'admin', null), {
    username: 'admin',
    password: ''
  })
})

test('非字符串、数组、空值、未知角色与原型属性名一律返回 null', () => {
  for (const invalid of [undefined, null, '', '   ', ['user'], ['admin', 'doctor'], 123, {}, 'root', 'toString', 'constructor']) {
    assert.equal(resolveDemoPreset('client', invalid, 'DemoPass#2026'), null)
    assert.equal(resolveDemoPreset('admin', invalid, 'DemoPass#2026'), null)
  }
})
