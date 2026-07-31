import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { fetch as tauriFetch } from '@tauri-apps/plugin-http'
import App from './App.vue'
import router from './router'
import { runningInTauri } from './services/native'
import '@web/style.css'
import './desktop.css'

if (runningInTauri) {
  window.__DAYLIANE_HTTP_FETCH__ = tauriFetch as typeof fetch
}

createApp(App)
  .use(createPinia())
  .use(router)
  .mount('#app')
