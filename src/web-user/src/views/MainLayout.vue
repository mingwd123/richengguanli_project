<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useAppStore } from '../stores/app'
import SideNav from '../components/SideNav.vue'

const route = useRoute()
const store = useAppStore()

const routeName = computed(() => {
  const map: Record<string, string> = {
    '/': '首页',
    '/calendar': '日历',
    '/schedules': '日程',
    '/teams': '团队',
    '/tasks': '任务',
    '/notifications': '通知',
    '/profile': '我的',
    '/profile/settings': '个人设置',
  }
  return map[route.path] || '首页'
})
</script>

<template>
  <main class="app-frame">
    <SideNav />
    <section class="main-area">
      <header class="page-topbar">
        <div>
          <h1>{{ routeName }}</h1>
          <p>{{ new Date().toLocaleDateString('zh-CN') }} 今天</p>
        </div>
        <div class="top-actions">
          <button class="primary" @click="store.loadAll">刷新</button>
        </div>
      </header>
      <router-view />
    </section>
    <div v-if="store.toast" class="toast">{{ store.toast }}</div>
  </main>
</template>
