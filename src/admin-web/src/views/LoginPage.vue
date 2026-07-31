<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { Hide, Lock, Monitor, Right, User, View } from '@element-plus/icons-vue'
import { useAdminStore } from '../stores/admin'

const router = useRouter()
const store = useAdminStore()
const showPassword = ref(false)

async function handleLogin() {
  await store.login()
  if (store.token) {
    router.push('/')
  }
}
</script>

<template>
  <main class="login-page">
    <div class="admin-login-visual" aria-hidden="true"></div>
    <section class="admin-login-story">
      <div class="admin-login-brand"><span class="logo-mark">D</span><strong>Dayliane</strong></div>
      <div class="admin-login-copy">
        <span><el-icon><Monitor /></el-icon> Admin console</span>
        <h1>清晰掌握，<br />每一次系统运行。</h1>
        <p>集中处理用户、团队、日程和系统记录。</p>
      </div>
      <div class="admin-secure-signal"><span></span>安全访问通道</div>
    </section>
    <section class="login-panel">
      <div class="admin-login-panel-head">
        <span class="admin-login-icon"><Lock /></span>
        <div><p class="eyebrow">管理员登录</p><h2>进入管理控制台</h2></div>
      </div>
      <form class="login-form" @submit.prevent="handleLogin">
        <label>管理员账号<span class="admin-input"><el-icon><User /></el-icon><input v-model="store.loginForm.username" autocomplete="username" placeholder="请输入管理员账号" /></span></label>
        <label>密码<span class="admin-input"><el-icon><Lock /></el-icon><input v-model="store.loginForm.password" :type="showPassword ? 'text' : 'password'" autocomplete="current-password" placeholder="请输入密码" /><button type="button" :title="showPassword ? '隐藏密码' : '显示密码'" @click="showPassword = !showPassword"><el-icon><Hide v-if="showPassword" /><View v-else /></el-icon></button></span></label>
        <button class="primary admin-login-submit" :disabled="store.loading"><span>{{ store.loading ? '验证中...' : '登录后台' }}</span><el-icon><Right /></el-icon></button>
      </form>
      <p class="admin-login-note">仅限授权管理员访问</p>
    </section>
    <div v-if="store.toast" class="toast">{{ store.toast }}</div>
  </main>
</template>
