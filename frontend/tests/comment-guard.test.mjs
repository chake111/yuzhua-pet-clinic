import assert from 'node:assert/strict'
import { test } from 'node:test'
import { ESLint } from 'eslint'
import pluginVue from 'eslint-plugin-vue'
import commentGuard from '../tools/eslint-plugin-comment-guard.mjs'

async function lintText(code, filePath, ruleConfig = {}) {
  const isTest = filePath.startsWith('tests/')
  const eslint = new ESLint({
    overrideConfigFile: true,
    overrideConfig: [
      ...pluginVue.configs['flat/essential'],
      {
        files: ['**/*.{js,mjs,ts,vue}'],
        languageOptions: { ecmaVersion: 'latest', sourceType: 'module' },
        plugins: { 'comment-guard': commentGuard },
        rules: {
          'vue/multi-word-component-names': 'off',
          'no-console': 'error',
          ...(isTest
            ? { 'comment-guard/test-guard': ['error', ruleConfig] }
            : { 'comment-guard/src-comments': ['error', ruleConfig] })
        }
      }
    ]
  })
  const [result] = await eslint.lintText(code, { filePath })
  return result.messages
}

function assertHasCode(messages, code, detail = '') {
  assert.ok(
    messages.some((m) => m.message.includes(`[${code}]`)),
    `期望命中 [${code}]${detail ? `（${detail}）` : ''}，实际: ${JSON.stringify(messages)}`
  )
}

test('高风险白名单文件：export 必须带 <=16 行且无类型标注的 JSDoc，非 export 禁止 JSDoc', async () => {
  const valid = [
    '/**',
    ' * 读取当前端登录令牌。',
    ' */',
    'export function getToken() {',
    "  return localStorage.getItem('k') || ''",
    '}'
  ].join('\n')
  assert.deepEqual(await lintText(valid, 'src/utils/token.js'), [])

  const missingDoc = 'export function getToken() { return "" }\n'
  assertHasCode(await lintText(missingDoc, 'src/utils/token.js'), 'COMMENT-RISK-DOC')

  const typedParam = [
    '/**',
    ' * 读取当前端登录令牌。',
    ' * @param {string} kind',
    ' */',
    'export function getToken(kind) { return kind }'
  ].join('\n')
  assertHasCode(await lintText(typedParam, 'src/api/httpError.js'), 'COMMENT-RISK-DOC')

  const tooLongDoc = [
    '/**',
    ...Array.from({ length: 16 }, (_, i) => ` * 契约说明第 ${i + 1} 行`),
    ' */',
    'export function newIdempotencyKey() { return "k" }'
  ].join('\n')
  assertHasCode(await lintText(tooLongDoc, 'src/utils/idempotencyKey.js'), 'COMMENT-DENSITY')

  const nonExportDoc = [
    '/** 内部辅助函数不允许 JSDoc */',
    'function helper() { return 1 }',
    '/** 导出契约 */',
    'export function createSession() { return helper() }'
  ].join('\n')
  assertHasCode(await lintText(nonExportDoc, 'src/stores/session.js'), 'COMMENT-UNEXPECTED-DOC')
})

test('非白名单源文件与 Vue 组件严禁写 JSDoc', async () => {
  const jsCode = '/** 业务封装 */\nexport const listUsers = () => []\n'
  assertHasCode(await lintText(jsCode, 'src/api/user.js'), 'COMMENT-UNEXPECTED-DOC')

  const vueCode = '<script setup>\n/** 组件说明 */\nconst count = 1\n</script>\n<template><div>{{ count }}</div></template>\n'
  assertHasCode(await lintText(vueCode, 'src/components/SampleCard.vue'), 'COMMENT-UNEXPECTED-DOC')
})

test('全文件非文档注释白名单：仅限缩进一致 <=6 行的 // 原因: 或 // TODO(#n):，禁行尾与块注释', async () => {
  const valid = [
    'export function calc(x) {',
    '  // 原因: 兼容历史时间戳格式',
    '  // 并保留毫秒精度',
    '  // TODO(#42): 下阶段统一为毫秒时间戳',
    '  return x + 1',
    '}'
  ].join('\n')
  assert.deepEqual(await lintText(valid, 'src/utils/format.js'), [])

  const badPrefix = 'export const a = () => {\n  // 说明一下\n  return 1\n}\n'
  assertHasCode(await lintText(badPrefix, 'src/utils/format.js'), 'COMMENT-INLINE-PREFIX')

  const badIndent = [
    'export function f() {',
    '  // 原因: 第一行',
    '    // 缩进不一致导致拆块',
    '  return 1',
    '}'
  ].join('\n')
  assertHasCode(await lintText(badIndent, 'src/utils/format.js'), 'COMMENT-INLINE-PREFIX')

  const trailing = 'export const x = 1 // 行尾注释\n'
  assertHasCode(await lintText(trailing, 'src/utils/format.js'), 'COMMENT-INLINE-PREFIX')

  const blockComment = 'export const x = /* 块注释 */ 1\n'
  assertHasCode(await lintText(blockComment, 'src/utils/format.js'), 'COMMENT-INLINE-PREFIX')

  const tooManyLines = [
    'export function f() {',
    '  // 原因: 超过六行',
    '  // 第二行',
    '  // 第三行',
    '  // 第四行',
    '  // 第五行',
    '  // 第六行',
    '  // 第七行',
    '  return 1',
    '}'
  ].join('\n')
  assertHasCode(await lintText(tooManyLines, 'src/utils/format.js'), 'COMMENT-DENSITY')
})

test('Vue <template> 注释仅限 <=3 行 <!-- 原因: / TODO(#n): -->，且 <style> 豁免', async () => {
  const validVue = [
    '<template>',
    '  <!-- 原因: 时段放首位防止占号冲突 -->',
    '  <div>ok</div>',
    '</template>',
    '<style scoped>',
    '/* 样式分区注释豁免 */',
    '.box { color: red; }',
    '</style>'
  ].join('\n')
  assert.deepEqual(await lintText(validVue, 'src/views/client/Booking.vue'), [])

  const badTpl = '<template>\n  <!-- 普通注释 -->\n  <div>ok</div>\n</template>\n'
  assertHasCode(await lintText(badTpl, 'src/views/client/Booking.vue'), 'COMMENT-INLINE-PREFIX')

  const longTpl = [
    '<template>',
    '  <!-- 原因: 第一行',
    '       第二行',
    '       第三行',
    '       第四行 -->',
    '  <div>ok</div>',
    '</template>'
  ].join('\n')
  assertHasCode(await lintText(longTpl, 'src/views/client/Booking.vue'), 'COMMENT-DENSITY')
})

test('指令型注释豁免、禁令与抑制棘轮校验', async () => {
  const refInDts = '/// <reference types="vite/client" />\nexport {}\n'
  assert.deepEqual(await lintText(refInDts, 'src/env.d.ts'), [])

  const refInJs = '/// <reference types="vite/client" />\nexport const a = 1\n'
  assertHasCode(await lintText(refInJs, 'src/main.js'), 'COMMENT-INLINE-PREFIX')

  const viteOk = 'export function load(p) {\n  // 原因: 动态页面路由按需加载\n  return import(/* @vite-ignore */ p)\n}\n'
  assert.deepEqual(await lintText(viteOk, 'src/router/index.js'), [])

  const viteNoReason = 'export function load(p) {\n  return import(/* @vite-ignore */ p)\n}\n'
  assertHasCode(await lintText(viteNoReason, 'src/router/index.js'), 'COMMENT-INLINE-PREFIX')

  const tsExpect = '// @ts-expect-error: 原因: 第三方类型定义缺失 (#15)\nexport const a = 1\n'
  assertHasCode(await lintText(tsExpect, 'src/utils/demo.ts', { suppressionBudget: 0 }), 'COMMENT-SUPPRESSION-RATCHET')
  assert.deepEqual(await lintText(tsExpect, 'src/utils/demo.ts', { suppressionBudget: 1 }), [])

  const disableNext = '// eslint-disable-next-line no-console -- 原因: 调试输出 (#15)\nconsole.log(1)\n'
  assertHasCode(await lintText(disableNext, 'src/utils/demo.js', { suppressionBudget: 0 }), 'COMMENT-SUPPRESSION-RATCHET')
  assert.deepEqual(await lintText(disableNext, 'src/utils/demo.js', { suppressionBudget: 1 }), [])

  for (const bad of [
    '/* node:coverage disable */\nexport const a = 1\n',
    '/* v8 ignore next */\nexport const a = 1\n',
    '/* eslint-disable no-console */\nexport const a = 1\n',
    '// @ts-ignore\nexport const a = 1\n',
    '// @ts-nocheck\nexport const a = 1\n',
    '// CHECKSTYLE:OFF\nexport const a = 1\n',
    '// spotless:off\nexport const a = 1\n'
  ]) {
    assertHasCode(await lintText(bad, 'src/utils/demo.js'), 'COMMENT-SUPPRESSION-RATCHET')
  }

  for (const badTodo of [
    '// FIXME: 稍后修\nexport const a = 1\n',
    '// HACK: 临时处理\nexport const a = 1\n',
    '// XXX: 注意\nexport const a = 1\n',
    '// TODO: 无编号待办\nexport const a = 1\n'
  ]) {
    assertHasCode(await lintText(badTodo, 'src/utils/demo.js'), 'COMMENT-INLINE-PREFIX')
  }
})

test('拦截注释代码、变更叙述、超密度与禁用词表（含反引号豁免）', async () => {
  const deadCode = '// 原因: const oldVal = 1;\nexport const a = 1\n'
  assertHasCode(await lintText(deadCode, 'src/utils/a.js'), 'COMMENT-DEAD-CODE')

  const deadTpl = '<template>\n  <!-- 原因: <el-button>旧按钮</el-button> -->\n  <div />\n</template>\n'
  assertHasCode(await lintText(deadTpl, 'src/components/A.vue'), 'COMMENT-DEAD-CODE')

  const backtickSafe = '// 原因: 当 `if (flag)` 为真时提前返回\nexport const a = 1\n'
  assert.deepEqual(await lintText(backtickSafe, 'src/utils/a.js'), [])

  const changeLog = '// 原因: 本次修改了重试阈值\nexport const a = 1\n'
  assertHasCode(await lintText(changeLog, 'src/utils/a.js'), 'COMMENT-CHANGE-LOG')

  const bannedTerm = '// 原因: 原先由 axios 拦截器处理\nexport const a = 1\n'
  assertHasCode(await lintText(bannedTerm, 'src/utils/a.js'), 'COMMENT-BANNED-TERM')

  const highDensity = [
    ...Array.from({ length: 14 }, (_, i) => `// 原因: 注释块 ${i}\n// 补充说明 ${i}`).slice(0, 12),
    ...Array.from({ length: 20 }, (_, i) => `export const v${i} = ${i}`)
  ].join('\n')
  assertHasCode(await lintText(highDensity, 'src/utils/a.js'), 'COMMENT-DENSITY')
})

test('测试基线：禁止无断言空测试与测试文件中的死代码/变更叙述', async () => {
  const validTest = "import assert from 'node:assert/strict'\nimport { test } from 'node:test'\ntest('ok', () => { assert.equal(1, 1) })\n"
  assert.deepEqual(await lintText(validTest, 'tests/sample.test.mjs'), [])

  const noAssertionFile = 'const x = 1 + 1\nvoid x\n'
  assertHasCode(await lintText(noAssertionFile, 'tests/empty.test.mjs'), 'TEST-EMPTY-ASSERTION')

  const emptyCase = [
    "import assert from 'node:assert/strict'",
    "import { test } from 'node:test'",
    "test('valid', () => { assert.ok(true) })",
    "test('empty', () => { const a = 1 })"
  ].join('\n')
  assertHasCode(await lintText(emptyCase, 'tests/empty-case.test.mjs'), 'TEST-EMPTY-ASSERTION')

  const dirtyTest = [
    "import assert from 'node:assert/strict'",
    "import { test } from 'node:test'",
    '// const dead = 1;',
    '// 本次新增测试',
    "test('ok', () => { assert.ok(true) })"
  ].join('\n')
  const msgs = await lintText(dirtyTest, 'tests/dirty.test.mjs')
  assertHasCode(msgs, 'COMMENT-DEAD-CODE')
  assertHasCode(msgs, 'COMMENT-CHANGE-LOG')
})

test('.guardrails 目录显式排除不触发规则', async () => {
  const redteam = '// arbitrary comment\n/** bad doc */\nexport const x = 1\n'
  assert.deepEqual(await lintText(redteam, '.guardrails/redteam-fixtures/sample.js'), [])
})
