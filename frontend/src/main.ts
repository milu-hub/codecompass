import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
// 页面级底座（背景光晕 + 中性色/圆角令牌）；组件样式仍在各自 .vue 里 scoped
import './styles/global.css'

// Element Plus 组件与样式由 unplugin-vue-components 按需引入（见 vite.config.ts），
// 这里不再全量注册组件，也不再引入 element-plus/dist/index.css。
createApp(App).use(createPinia()).mount('#app')
