<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAppStore } from '../stores/app'
import { ArrowRight, CalendarCheck2, Eye, EyeOff, Sparkles } from 'lucide-vue-next'

const router = useRouter()
const store = useAppStore()

const activeTab = ref<'login' | 'register'>('login')
const showPassword = ref(false)

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
    <div class="login-atmosphere" aria-hidden="true"></div>
    <section class="login-brand-story">
      <div class="login-brand">
        <span class="login-brand-mark"><CalendarCheck2 :size="24" /></span>
        <strong>Dayliane</strong>
      </div>
      <div class="login-copy">
        <span class="login-kicker"><Sparkles :size="15" /> Your day, in flow</span>
        <h1>让每个安排，<br />都恰好发生。</h1>
        <p>在清晰的时间节奏里，完成今天的重要事项。</p>
      </div>
      <div class="login-signal">
        <span class="signal-pulse"></span>
        <span>今日节奏已就绪</span>
      </div>
    </section>

    <section class="login-card">
      <div class="login-card-head">
        <p class="eyebrow">欢迎回来</p>
        <h2>{{ activeTab === 'login' ? '进入你的工作台' : '创建 Dayliane 账号' }}</h2>
      </div>

      <div class="register-line" role="tablist" aria-label="账号操作">
        <button :class="{ active: activeTab === 'login' }" role="tab" @click="activeTab = 'login'">登录</button>
        <button :class="{ active: activeTab === 'register' }" role="tab" @click="activeTab = 'register'">注册</button>
      </div>

      <form v-if="activeTab === 'login'" class="login-form" @submit.prevent="handleLogin">
        <label>
          手机号
          <input v-model="store.loginForm.phone" autocomplete="username" placeholder="请输入手机号" />
        </label>
        <label>
          密码
          <span class="password-input">
            <input v-model="store.loginForm.password" :type="showPassword ? 'text' : 'password'" autocomplete="current-password" placeholder="请输入密码" />
            <button type="button" :title="showPassword ? '隐藏密码' : '显示密码'" @click="showPassword = !showPassword">
              <EyeOff v-if="showPassword" :size="17" />
              <Eye v-else :size="17" />
            </button>
          </span>
        </label>
        <button class="primary block login-submit" :disabled="store.loading">
          <span>{{ store.loading ? '登录中...' : '进入工作台' }}</span>
          <ArrowRight :size="17" />
        </button>
      </form>

      <form v-else class="login-form" @submit.prevent="handleRegister">
        <label>
          手机号
          <input v-model="store.registerForm.phone" autocomplete="username" placeholder="请输入手机号" />
        </label>
        <label>
          密码
          <input v-model="store.registerForm.password" :type="showPassword ? 'text' : 'password'" autocomplete="new-password" placeholder="至少 8 位" />
        </label>
        <label>
          确认密码
          <input v-model="store.registerForm.confirmPassword" :type="showPassword ? 'text' : 'password'" autocomplete="new-password" placeholder="再次输入密码" />
        </label>
        <label>
          昵称
          <input v-model="store.registerForm.nickname" placeholder="怎么称呼你" />
        </label>
        <button class="primary block login-submit" :disabled="store.loading">
          <span>{{ store.loading ? '创建中...' : '创建账号' }}</span>
          <ArrowRight :size="17" />
        </button>
      </form>

      <p class="hint demo-account">演示账号&nbsp; 13800138000&nbsp; /&nbsp; Abc12345</p>
    </section>
  </main>
</template>
