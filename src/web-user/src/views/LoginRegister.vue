<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAppStore } from '../stores/app'

const router = useRouter()
const store = useAppStore()

const activeTab = ref<'login' | 'register'>('login')

async function handleLogin() {
  const success = await store.login()
  if (success) router.push('/')
}

async function handleRegister() {
  const success = await store.register()
  if (success) router.push('/')
}
</script>

<template>
  <main class="login-canvas">
    <section class="login-card">
      <p class="eyebrow">Dayliane</p>
      <h1>日程提醒</h1>
      <p class="muted">个人日程与小团队任务协作工具。</p>

      <div class="register-line">
        <button :class="{ primary: activeTab === 'login' }" @click="activeTab = 'login'">登录</button>
        <button :class="{ primary: activeTab === 'register' }" @click="activeTab = 'register'">注册</button>
      </div>

      <!-- 登录表单 -->
      <form v-if="activeTab === 'login'" class="login-form" @submit.prevent="handleLogin">
        <label>
          手机号
          <input v-model="store.loginForm.phone" autocomplete="username" />
        </label>
        <label>
          密码
          <input v-model="store.loginForm.password" type="password" autocomplete="current-password" />
        </label>
        <button class="primary block" :disabled="store.loading">登录</button>
      </form>

      <!-- 注册表单 -->
      <form v-else class="login-form" @submit.prevent="handleRegister">
        <label>
          手机号
          <input v-model="store.registerForm.phone" autocomplete="username" />
        </label>
        <label>
          密码
          <input v-model="store.registerForm.password" type="password" autocomplete="new-password" />
        </label>
        <label>
          确认密码
          <input v-model="store.registerForm.confirmPassword" type="password" autocomplete="new-password" />
        </label>
        <label>
          昵称
          <input v-model="store.registerForm.nickname" />
        </label>
        <button class="primary block" :disabled="store.loading">注册</button>
      </form>

      <p class="hint">演示账号：13800138000 / Abc12345</p>
    </section>
  </main>
</template>
