import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
// 页面级底座（背景光晕 + 中性色/圆角令牌）；组件样式仍在各自 .vue 里 scoped
import './styles/global.css'

// Element Plus 组件与样式由 unplugin-vue-components 按需引入（见 vite.config.ts），
// 这里不再全量注册组件，也不再引入 element-plus/dist/index.css。
//
// 例外：el-notification / el-message 是**编程式调用**（ElNotification.success(...)、
// ElMessage.error(...)），解析器只认得模板标签，不会带上它们的样式。不显式引入的后果是
// 通知/消息退化成无样式的裸 div —— 它是 static 定位，会被当成 body 末尾的普通块级内容，
// 于是「成就解锁」提示跑到页面最底部、还把文档高度撑出 125px（凭空多出一条滚动条）。
import 'element-plus/es/components/notification/style/css'
import 'element-plus/es/components/message/style/css'

createApp(App).use(createPinia()).mount('#app')
