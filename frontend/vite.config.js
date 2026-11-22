import { readFileSync } from 'node:fs'
import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
import { BRAND } from './src/brand.js'

// 后端 server.servlet.context-path 就是 /api，所以代理不重写路径，
// 前端所有请求写成 /api/xxx 即可与生产部署（同域反代）保持一致。
const BACKEND = process.env.VITE_BACKEND || 'http://127.0.0.1:8081'
const ELEMENT_PLUS_CSS_RE = /[\\/]node_modules[\\/]element-plus[\\/]theme-chalk[\\/].*\.css$/
const ELEMENT_PLUS_EN_LOCALE_RE = /[\\/]node_modules[\\/]element-plus[\\/]es[\\/]locale[\\/]lang[\\/]en\.mjs$/
const ZH_CN_LOCALE_PATH = fileURLToPath(new URL('./node_modules/element-plus/es/locale/lang/zh-cn.mjs', import.meta.url))

/** 把 index.html 的品牌占位符换成 brand.js 里的常量，dev 与 build 共用。 */
function brandHtml() {
  return {
    name: 'brand-html',
    transformIndexHtml: {
      order: 'pre',
      handler: (html) => html
        .replaceAll('%BRAND_TITLE%', BRAND.title)
        .replaceAll('%BRAND_DESCRIPTION%', BRAND.description)
        .replaceAll('%BRAND_NAME%', BRAND.name)
        .replaceAll('%BRAND_TAGLINE%', BRAND.tagline)
    }
  }
}

/**
 * 1. 将 Element Plus theme-chalk 样式统一包入 @layer element-plus，
 *    保证未分层的 main.css / admin.css / scoped 样式按 CSS Cascade Layers 恒定优先，
 *    避免按需拆包后异步 chunk 晚加载时反覆盖项目样式。
 * 2. 将 Element Plus use-locale 默认回退的 en.mjs 直接替换为 zh-cn.mjs，
 *    使所有懒加载路由中的 Element Plus 组件天然使用中文文案，无需在根组件 App.vue
 *    挂载 <el-config-provider>（从而让首屏入口彻底剥离 element-plus 运行时）。
 */
function elementPlusLayer() {
  return {
    name: 'element-plus-layer',
    enforce: 'pre',
    load(id) {
      if (!id || id.startsWith('\0')) return null
      const [cleanId] = id.split('?')
      if (ELEMENT_PLUS_EN_LOCALE_RE.test(cleanId)) {
        return readFileSync(ZH_CN_LOCALE_PATH, 'utf8')
      }
      return null
    },
    transform(code, id) {
      if (!id || id.startsWith('\0')) return null
      const [cleanId, query = ''] = id.split('?')
      if (/(?:^|&)(?:raw|url)(?:=|&|$)/.test(query)) return null
      if (!ELEMENT_PLUS_CSS_RE.test(cleanId)) return null
      return {
        code: `@layer element-plus {\n${code}\n}`,
        map: null
      }
    }
  }
}

export default defineConfig({
  plugins: [
    brandHtml(),
    elementPlusLayer(),
    vue(),
    Components({
      dts: false,
      resolvers: [ElementPlusResolver({ importStyle: 'css', directives: true })]
    })
  ],
  optimizeDeps: {
    esbuildOptions: {
      plugins: [
        {
          name: 'element-plus-default-zh-cn',
          setup(build) {
            build.onLoad({ filter: ELEMENT_PLUS_EN_LOCALE_RE }, () => ({
              contents: readFileSync(ZH_CN_LOCALE_PATH, 'utf8'),
              loader: 'js'
            }))
          }
        }
      ]
    }
  },
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) }
  },
  server: {
    host: '127.0.0.1',
    port: 5173,
    open: true,
    proxy: {
      '/api': {
        target: BACKEND,
        changeOrigin: true,
        // 图文问诊走 WebSocket，握手路径同样在 /api 下，交给 http-proxy 升协议
        ws: true,
        configure(proxy) {
          // 后端没起来时 vite 会抛 ECONNREFUSED，不接住会把整个 dev server 带走
          proxy.on('error', (err, req) => {
            console.warn('[proxy] 后端不可达', req?.url, err.message)
          })
        }
      }
    }
  },
  build: {
    manifest: true,
    chunkSizeWarningLimit: 1200
  }
})
