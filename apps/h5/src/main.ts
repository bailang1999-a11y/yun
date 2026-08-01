import { createApp } from 'vue'
import { createPinia } from 'pinia'
import 'vant/lib/index.css'
import './style.css'
import App from './App.vue'
import router from './router'
import 'altcha'

createApp(App).use(createPinia()).use(router).mount('#app')
