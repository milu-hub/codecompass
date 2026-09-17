import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'

// Element Plus 组件与样式由 unplugin-vue-components 按需引入（见 vite.config.ts），
// 这里不再全量注册组件，也不再引入 element-plus/dist/index.css。
createApp(App).use(createPinia()).mount('#app')
