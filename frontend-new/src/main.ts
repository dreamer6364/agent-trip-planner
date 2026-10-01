import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import i18n, { applyLocale } from './i18n'
import './assets/styles/main.css'
import './assets/styles/transitions.css'

// 启动即应用已保存语言（vue-i18n / html lang / dayjs）
applyLocale(localStorage.getItem('tf_locale'))

const app = createApp(App)

app.use(createPinia())
app.use(router)
app.use(i18n)

app.mount('#app')
