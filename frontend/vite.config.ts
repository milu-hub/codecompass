import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 开发期通过 dev server 代理转发到后端，前端一律使用相对路径 /health。
// 这样浏览器视角同源，后端不需要任何 CORS 配置。
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/health': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
