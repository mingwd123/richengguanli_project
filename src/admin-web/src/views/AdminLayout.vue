<script setup>
import { onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAdminStore } from '../stores/admin'

const store = useAdminStore()
const router = useRouter()
const route = useRoute()

const sidebarItems = [
  { path: '/', label: '总览' },
  { path: '/users', label: '用户管理' },
  { path: '/teams', label: '团队管理' },
  { path: '/schedules', label: '日程查看' },
  { path: '/team-tasks', label: '团队任务' },
  { path: '/notifications', label: '通知记录' },
  { path: '/reminders', label: '提醒记录' },
  { path: '/admin-users', label: '管理员账号' },
  { path: '/operation-logs', label: '操作日志' },
]

function isActive(itemPath) {
  if (itemPath === '/') return route.path === '/'
  return route.path.startsWith(itemPath)
}

function navigate(path) {
  router.push(path)
}

function handleLogout() {
  store.logout()
  router.push('/login')
}

onMounted(async () => {
  if (store.token) {
    await store.loadProfile()
  }
})
</script>

<template>
  <main v-if="store.token" class="admin-shell">
    <aside class="sidebar">
      <div class="brand"><span>D</span><strong>管理后台</strong></div>
      <nav>
        <button
          v-for="item in sidebarItems"
          :key="item.path"
          :class="{ active: isActive(item.path) }"
          @click="navigate(item.path)"
        >
          {{ item.label }}
        </button>
      </nav>
      <div class="account">
        <strong>{{ store.profile?.username || '管理员' }}</strong>
        <small>{{ store.profile?.role }}</small>
        <button @click="handleLogout">退出</button>
      </div>
    </aside>

    <section class="workspace">
      <router-view />
    </section>

    <div v-if="store.toast" class="toast">{{ store.toast }}</div>
  </main>
</template>
