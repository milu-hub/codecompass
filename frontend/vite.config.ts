import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

// 开发期通过 dev server 代理转发到后端，前端一律使用相对路径 /health 与 /api。
// 这样浏览器视角同源，后端不需要任何 CORS 配置。
// 注意：/api 代理是 T8 才补上的 —— T7 之前只有 /health，前端调 /api/repos 会 404。
export default defineConfig({
  plugins: [
    vue(),
    // Element Plus 按需引入：只打包模板里真正用到的组件及其样式，
    // 取代 main.ts 里原先的全量 use(ElementPlus) + 全量 CSS。
    // 组件类型由 tsconfig.app.json 的 "types": ["element-plus/global"] 提供，
    // 因此不会产生需要提交的 components.d.ts，也不存在"先类型检查还是先生成声明"的顺序问题。
    Components({ resolvers: [ElementPlusResolver()], dts: false }),
  ],
  build: {
    // mermaid 的动态 import 把 elk 布局引擎等拆成懒加载块（最大约 1.4MB），
    // 只在真正渲染图时才下载、不进首屏。此处阈值只为消除对这些懒块的常驻警告，
    // 避免它淹没真正的体积回归。
    chunkSizeWarningLimit: 1600,
  },
  server: {
    port: 5173,
    proxy: {
      '/health': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
