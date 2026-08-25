<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowRight, CalendarCheck2, Eye, EyeOff, LoaderCircle, RefreshCw, Sparkles } from 'lucide-vue-next'
import EmailVerificationFields from '../components/EmailVerificationFields.vue'
import { useAppStore } from '../stores/app'
import { isValidEmailCode, isValidOptionalPhone, isValidPassword } from '../utils/auth'

const router = useRouter()
const store = useAppStore()

const activeTab = ref<'login' | 'register'>('login')
const showLoginPassword = ref(false)
const showRegisterPassword = ref(false)
const registerVerification = ref<InstanceType<typeof EmailVerificationFields> | null>(null)
const loginErrors = reactive({ account: '', password: '' })
const registerErrors = reactive({ phone: '', password: '', confirmPassword: '' })
const registrationNotice = computed(() => {
  if (store.registrationStatusLoading) return '正在确认注册状态'
  if (store.registrationStatusError) return '暂时无法确认注册状态，新用户注册已暂时关闭'
  if (store.registrationStatusChecked && !store.registrationEnabled) return '当前暂不开放新用户注册'
  return ''
})

function switchTab(tab: 'login' | 'register') {
  if (tab === 'register' && !store.registrationEnabled) return
  activeTab.value = tab
  loginErrors.account = ''
  loginErrors.password = ''
  registerErrors.phone = ''
  registerErrors.password = ''
  registerErrors.confirmPassword = ''
}

async function handleLogin() {
  loginErrors.account = store.loginForm.account.trim() ? '' : '请输入邮箱或手机号'
  loginErrors.password = store.loginForm.password ? '' : '请输入密码'
  if (loginErrors.account || loginErrors.password) return

  const success = await store.login()
  if (success) router.push('/')
}

async function handleRegister() {
  if (!store.registrationEnabled) return
  const verificationValid = registerVerification.value?.validate() ?? false
  registerErrors.phone = isValidOptionalPhone(store.registerForm.phone) ? '' : '请输入正确的手机号'
  registerErrors.password = isValidPassword(store.registerForm.password) ? '' : '密码至少 8 位，且包含字母和数字'
  registerErrors.confirmPassword = store.registerForm.password === store.registerForm.confirmPassword ? '' : '两次密码不一致'
  if (!verificationValid || !isValidEmailCode(store.registerForm.code) || Object.values(registerErrors).some(Boolean)) return

  const success = await store.register()
  if (success) router.push('/')
}

function sendRegisterCode(email: string) {
  return store.sendEmailCode({ email, purpose: 'register' })
}

onMounted(() => {
  store.loadRegistrationStatus()
})

onBeforeUnmount(() => {
  store.loginForm.password = ''
  store.registerForm.code = ''
  store.registerForm.password = ''
  store.registerForm.confirmPassword = ''
})
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
        <p class="eyebrow">{{ activeTab === 'login' ? '欢迎回来' : '验证邮箱后开始' }}</p>
        <h2>{{ activeTab === 'login' ? '进入你的工作台' : '创建 Dayliane 账号' }}</h2>
      </div>

      <div class="register-line" role="tablist" aria-label="账号操作">
        <button :class="{ active: activeTab === 'login' }" role="tab" :aria-selected="activeTab === 'login'" @click="switchTab('login')">登录</button>
        <button
          :class="{ active: activeTab === 'register' }"
          role="tab"
          :aria-selected="activeTab === 'register'"
          :disabled="!store.registrationEnabled"
          @click="switchTab('register')"
        >注册</button>
      </div>

      <div v-if="registrationNotice" class="registration-notice" role="status" aria-live="polite">
        <LoaderCircle v-if="store.registrationStatusLoading" class="spinning" :size="15" />
        <span>{{ registrationNotice }}</span>
        <button
          v-if="!store.registrationStatusLoading"
          type="button"
          title="重新检查注册状态"
          aria-label="重新检查注册状态"
          @click="store.loadRegistrationStatus()"
        >
          <RefreshCw :size="15" />
        </button>
      </div>

      <form v-if="activeTab === 'login'" class="login-form" @submit.prevent="handleLogin">
        <label>
          邮箱或手机号
          <input
            v-model="store.loginForm.account"
            autocomplete="username"
            placeholder="请输入邮箱或手机号"
            :aria-invalid="!!loginErrors.account"
            @input="loginErrors.account = ''"
          />
          <small v-if="loginErrors.account" class="field-error">{{ loginErrors.account }}</small>
        </label>
        <label>
          密码
          <span class="password-input">
            <input
              v-model="store.loginForm.password"
              :type="showLoginPassword ? 'text' : 'password'"
              autocomplete="current-password"
              placeholder="请输入密码"
              :aria-invalid="!!loginErrors.password"
              @input="loginErrors.password = ''"
            />
            <button type="button" :title="showLoginPassword ? '隐藏密码' : '显示密码'" @click="showLoginPassword = !showLoginPassword">
              <EyeOff v-if="showLoginPassword" :size="17" />
              <Eye v-else :size="17" />
            </button>
          </span>
          <small v-if="loginErrors.password" class="field-error">{{ loginErrors.password }}</small>
        </label>
        <div class="auth-form-meta">
          <router-link to="/forgot-password">忘记密码？</router-link>
        </div>
        <button class="primary block login-submit" :disabled="store.loading">
          <span>{{ store.loading ? '登录中...' : '进入工作台' }}</span>
          <ArrowRight :size="17" />
        </button>
      </form>

      <form v-else class="login-form" @submit.prevent="handleRegister">
        <EmailVerificationFields
          ref="registerVerification"
          v-model:email="store.registerForm.email"
          v-model:code="store.registerForm.code"
          :send-code="sendRegisterCode"
          :disabled="store.loading || !store.registrationEnabled"
        />
        <label>
          密码
          <span class="password-input">
            <input
              v-model="store.registerForm.password"
              :type="showRegisterPassword ? 'text' : 'password'"
              autocomplete="new-password"
              placeholder="至少 8 位，包含字母和数字"
              :aria-invalid="!!registerErrors.password"
              :disabled="store.loading || !store.registrationEnabled"
              @input="registerErrors.password = ''"
            />
            <button
              type="button"
              :title="showRegisterPassword ? '隐藏密码' : '显示密码'"
              :disabled="store.loading || !store.registrationEnabled"
              @click="showRegisterPassword = !showRegisterPassword"
            >
              <EyeOff v-if="showRegisterPassword" :size="17" />
              <Eye v-else :size="17" />
            </button>
          </span>
          <small v-if="registerErrors.password" class="field-error">{{ registerErrors.password }}</small>
        </label>
        <label>
          确认密码
          <input
            v-model="store.registerForm.confirmPassword"
            :type="showRegisterPassword ? 'text' : 'password'"
            autocomplete="new-password"
            placeholder="再次输入密码"
            :aria-invalid="!!registerErrors.confirmPassword"
            :disabled="store.loading || !store.registrationEnabled"
            @input="registerErrors.confirmPassword = ''"
          />
          <small v-if="registerErrors.confirmPassword" class="field-error">{{ registerErrors.confirmPassword }}</small>
        </label>
        <label>
          <span class="field-label">手机号 <small class="optional-label">可选</small></span>
          <input
            v-model="store.registerForm.phone"
            type="tel"
            inputmode="tel"
            autocomplete="tel"
            placeholder="用于手机号登录"
            :aria-invalid="!!registerErrors.phone"
            :disabled="store.loading || !store.registrationEnabled"
            @input="registerErrors.phone = ''"
          />
          <small v-if="registerErrors.phone" class="field-error">{{ registerErrors.phone }}</small>
        </label>
        <label>
          <span class="field-label">昵称 <small class="optional-label">可选</small></span>
          <input v-model="store.registerForm.nickname" autocomplete="nickname" placeholder="怎么称呼你" :disabled="store.loading || !store.registrationEnabled" />
        </label>
        <button class="primary block login-submit" :disabled="store.loading || !store.registrationEnabled">
          <span>{{ store.loading ? '创建中...' : '创建账号' }}</span>
          <ArrowRight :size="17" />
        </button>
      </form>

      <p class="hint demo-account">演示账号&nbsp; 13800138000&nbsp; /&nbsp; Abc12345</p>
    </section>

    <div v-if="store.toast" class="toast login-toast" role="alert">{{ store.toast }}</div>
  </main>
</template>
