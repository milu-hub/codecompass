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

const app = createApp(App)

/**
 * 组件出错时不要把整页搞白。
 *
 * 实测教训：Vue 默认会让渲染期异常一路冒泡，patch 在出错的那个组件处中断，
 * 于是"顶栏还在、输入行和状态条全没了"——看起来像布局被删了，其实是渲染中断。
 * 装上 errorHandler 后错误只影响出错的那棵子树，其余部分照常渲染，控制台里能看到原因。
 */
app.config.errorHandler = (error, _instance, info) => {
  console.error('[CodeCompass] 组件出错（已隔离，不影响其它区域）：', info, error)
}

app.use(createPinia()).mount('#app')
