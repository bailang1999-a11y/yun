import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { applySystemTheme } from './stores/theme'
import './style.css'

const pinia = createPinia()
applySystemTheme()

createApp(App).use(pinia).use(router).mount('#app')
