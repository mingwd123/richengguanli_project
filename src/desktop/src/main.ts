import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { fetch as tauriFetch } from '@tauri-apps/plugin-http'
import App from './App.vue'
import router from './router'
import { runningInTauri } from './services/native'
import '@web/style.css'
import './desktop.css'

if (runningInTauri) {
  const nativeFetch = tauriFetch as typeof fetch
  const browserFetch = window.fetch.bind(window)
  window.__DAYLIANE_HTTP_FETCH__ = async (input, init) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
    if (url.startsWith('http://127.0.0.1:8080') || url.startsWith('http://localhost:8080')) {
      try {
        return await browserFetch(input, init)
      } catch {
        return nativeFetch(input, init)
      }
    }
    return nativeFetch(input, init)
  }
}

createApp(App)
  .use(createPinia())
  .use(router)
  .mount('#app')
