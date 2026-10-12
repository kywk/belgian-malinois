import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import router from './router'
import App from './App.vue'
import { installUniformPlugin } from './uniform/setup.js'
import './uniform/theme.css'

const app = createApp(App)
app.use(createPinia())
app.use(ElementPlus)
app.use(router)
// uni-form：manifests（core pack）＋widgets（Element Plus pack＋本地覆寫）
// ＋createCoreValidation。UfRenderer／UfDesigner 都靠這個 provide。
app.use(installUniformPlugin)
app.mount('#app')
