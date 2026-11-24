import js from '@eslint/js'
import pluginVue from 'eslint-plugin-vue'
import globals from 'globals'
import commentGuard from './tools/eslint-plugin-comment-guard.mjs'

// 禁止全量导入 Element Plus / 图标库，防止首屏关键路径包体回退。
// 在 ESLint no-restricted-imports 中限制 importNames: ['default'] 会同时阻断默认导入与 `import * as` 命名空间导入。
const BASE_RESTRICTED_IMPORTS = [
  {
    name: 'element-plus',
    importNames: ['default'],
    message: '禁止全量或命名空间导入 element-plus，请由 unplugin-vue-components 按需解析或按需具名导入'
  },
  {
    name: 'element-plus/dist/index.css',
    message: '禁止全量导入 element-plus/dist/index.css，组件样式由按需解析自动引入'
  },
  {
    name: '@element-plus/icons-vue',
    importNames: ['default'],
    message: '禁止全量或命名空间导入 @element-plus/icons-vue，请按需具名导入所需图标'
  }
]

export default [
  { ignores: ['dist/**', 'node_modules/**', '.oil-ui/**'] },
  js.configs.recommended,
  ...pluginVue.configs['flat/essential'],
  {
    files: ['**/*.{js,mjs,vue}'],
    languageOptions: {
      ecmaVersion: 'latest',
      sourceType: 'module',
      globals: { ...globals.browser }
    },
    rules: {
      // 页面组件按业务命名（Home、Booking），不强制多词
      'vue/multi-word-component-names': 'off',
      // 允许为文档完整性保留未使用参数，但未使用变量仍是错误；
      // ignoreRestSiblings 让 `const { confirm, ...payload } = form` 这类"剔除字段"的写法不必改名
      'no-unused-vars': ['error', { argsIgnorePattern: '^_', varsIgnorePattern: '^_', ignoreRestSiblings: true }],
      // 与本项目既有写法一致：单文件组件允许 <style scoped>
      'vue/valid-v-slot': 'error',
      'no-restricted-imports': [
        'error',
        {
          paths: [
            ...BASE_RESTRICTED_IMPORTS,
            {
              name: 'element-plus',
              importNames: ['ElMessageBox'],
              message: '请从 @/utils/messageBox 导入 ElMessageBox，确保样式按需加载与中文按钮默认文案生效'
            }
          ]
        }
      ]
    }
  },
  {
    // 封装模块自身允许从 element-plus 导入原始 ElMessageBox，其余全量导入限制照旧生效
    files: ['src/utils/messageBox.js'],
    rules: {
      'no-restricted-imports': ['error', { paths: BASE_RESTRICTED_IMPORTS }]
    }
  },
  {
    // 首屏关键路径（/、/login、/admin/login 及其直连依赖）严禁静态导入 element-plus、图标桶或 axios，
    // 防止 100+ KB 组件库运行时或第三方 HTTP 库重新混入首屏入口 chunk。
    files: [
      'src/main.js',
      'src/App.vue',
      'src/router/index.js',
      'src/api/http.js',
      'src/layouts/ClientLayout.vue',
      'src/views/client/Home.vue',
      'src/views/client/Login.vue',
      'src/views/admin/Login.vue',
      'src/components/LoginForm.vue',
      'src/components/LoginScene.vue',
      'src/components/DoctorCard.vue',
      'src/components/LoadFailure.vue',
      'src/components/DeptIcon.vue',
      'src/components/StatusChip.vue',
      'src/components/EntryIcon.vue',
      'src/utils/toast.js'
    ],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          paths: [
            {
              name: 'element-plus',
              message: '首屏关键路径禁止导入 element-plus（提示请用 @/utils/toast，控件请用原生 HTML + entry-el.css）'
            },
            {
              name: '@element-plus/icons-vue',
              message: '首屏关键路径禁止导入 @element-plus/icons-vue（请用 @/components/EntryIcon.vue）'
            },
            {
              name: 'axios',
              message: 'src/api/http.js 已改为原生 fetch，禁止重新引入 axios'
            }
          ],
          patterns: [
            {
              group: ['element-plus/*'],
              message: '首屏关键路径禁止导入 element-plus 子模块'
            }
          ]
        }
      ]
    }
  },
  {
    files: ['src/**/*.{js,ts,vue}'],
    plugins: { 'comment-guard': commentGuard },
    linterOptions: { reportUnusedDisableDirectives: 'error' },
    rules: {
      'comment-guard/src-comments': 'error',
      complexity: ['error', 15],
      'max-lines-per-function': ['error', { max: 60, skipBlankLines: true, skipComments: true }],
      'max-depth': ['error', 3],
      'max-params': ['error', 5]
    }
  },
  {
    files: ['tests/**/*.{js,mjs,ts}'],
    plugins: { 'comment-guard': commentGuard },
    linterOptions: { reportUnusedDisableDirectives: 'error' },
    rules: {
      'comment-guard/test-guard': 'error'
    }
  },
  {
    // 工具脚本与测试跑在 Node 里，不是浏览器
    files: ['tools/**/*.mjs', 'tests/**/*.mjs', 'eslint.config.js'],
    languageOptions: { globals: { ...globals.node } }
  }
]
