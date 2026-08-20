import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
/* Element Plus 官方暗色变量：兜底 message/notification/popper 等 append-to-body 弹层 */
import 'element-plus/theme-chalk/dark/css-vars.css'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'

/* ---- Design System ---- */
import './styles/design-system.css'
import './styles/animations.css'

/* ---- Inter Font (自托管可变字体，免 CDN 依赖) ---- */
import '@fontsource-variable/inter'

/* ---- Dev-only: impeccable live reload bridge ----
 * 仅在 `vite dev` 模式下注入；构建产物（vite build）不会执行 import.meta.env.DEV 为 true 的分支。
 * 提供 `live.js` 用于本地 UI 调优回放，不参与生产环境。 */
if (import.meta.env.DEV) {
  const live = document.createElement('script')
  live.src = 'http://localhost:8400/live.js'
  document.body.appendChild(live)
}

const app = createApp(App)

for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

app.use(createPinia())
app.use(router)
app.use(ElementPlus)
app.mount('#app')
