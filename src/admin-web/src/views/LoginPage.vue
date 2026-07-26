<script setup>
import { useRouter } from 'vue-router'
import { useAdminStore } from '../stores/admin'

const router = useRouter()
const store = useAdminStore()

async function handleLogin() {
  await store.login()
  if (store.token) {
    router.push('/')
  }
}
</script>

<template>
  <main class="login-page">
    <section class="login-panel">
      <p class="eyebrow">Dayliane Admin</p>
      <h1>日程提醒管理后台</h1>
      <p class="muted">请使用管理员账号登录后台。</p>
      <form class="login-form" @submit.prevent="handleLogin">
        <label>管理员账号<input v-model="store.loginForm.username" autocomplete="username" /></label>
        <label>密码<input v-model="store.loginForm.password" type="password" autocomplete="current-password" /></label>
        <button class="primary" :disabled="store.loading">登录后台</button>
      </form>
    </section>
    <div v-if="store.toast" class="toast">{{ store.toast }}</div>
  </main>
</template>
