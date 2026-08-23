<script setup lang="ts">
import { onBeforeUnmount, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowLeft, ArrowRight, CalendarCheck2, CircleCheck, Eye, EyeOff, Sparkles } from 'lucide-vue-next'
import EmailVerificationFields from '../components/EmailVerificationFields.vue'
import { useAppStore } from '../stores/app'
import { isValidPassword } from '../utils/auth'

const router = useRouter()
const store = useAppStore()
const verification = ref<InstanceType<typeof EmailVerificationFields> | null>(null)
const showPassword = ref(false)
const succeeded = ref(false)
const form = reactive({ email: '', code: '', newPassword: '', confirmPassword: '' })
const errors = reactive({ newPassword: '', confirmPassword: '' })

function sendResetCode(email: string) {
  return store.sendEmailCode({ email, purpose: 'reset_password' })
}

async function handleReset() {
  const verificationValid = verification.value?.validate() ?? false
  errors.newPassword = isValidPassword(form.newPassword) ? '' : '密码至少 8 位，且包含字母和数字'
  errors.confirmPassword = form.newPassword === form.confirmPassword ? '' : '两次密码不一致'
  if (!verificationValid || errors.newPassword || errors.confirmPassword) return

  const success = await store.resetPassword(form)
  if (!success) return
  form.code = ''
  form.newPassword = ''
  form.confirmPassword = ''
  succeeded.value = true
}

onBeforeUnmount(() => {
  form.code = ''
  form.newPassword = ''
  form.confirmPassword = ''
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
        <span class="login-kicker"><Sparkles :size="15" /> Keep your day moving</span>
        <h1>找回密码，<br />继续今天的节奏。</h1>
        <p>验证绑定邮箱后即可设置新密码，其他设备上的旧会话将失效。</p>
      </div>
      <div class="login-signal">
        <span class="signal-pulse"></span>
        <span>邮箱验证保护账号安全</span>
      </div>
    </section>

    <section class="login-card forgot-password-card">
      <template v-if="succeeded">
        <div class="auth-success-state">
          <span class="auth-success-icon"><CircleCheck :size="28" /></span>
          <p class="eyebrow">重置成功</p>
          <h2>新密码已经生效</h2>
          <p>请使用新密码重新登录 Dayliane。</p>
          <button class="primary block login-submit" @click="router.replace('/login')">
            <span>返回登录</span>
            <ArrowRight :size="17" />
          </button>
        </div>
      </template>

      <template v-else>
        <button class="auth-back-link" type="button" @click="router.push('/login')">
          <ArrowLeft :size="15" />
          <span>返回登录</span>
        </button>
        <div class="login-card-head">
          <p class="eyebrow">找回密码</p>
          <h2>验证你的绑定邮箱</h2>
        </div>

        <form class="login-form" @submit.prevent="handleReset">
          <EmailVerificationFields
            ref="verification"
            v-model:email="form.email"
            v-model:code="form.code"
            :send-code="sendResetCode"
            :disabled="store.loading"
            email-label="绑定邮箱"
          />
          <label>
            新密码
            <span class="password-input">
              <input
                v-model="form.newPassword"
                :type="showPassword ? 'text' : 'password'"
                autocomplete="new-password"
                placeholder="至少 8 位，包含字母和数字"
                :aria-invalid="!!errors.newPassword"
                @input="errors.newPassword = ''"
              />
              <button type="button" :title="showPassword ? '隐藏密码' : '显示密码'" @click="showPassword = !showPassword">
                <EyeOff v-if="showPassword" :size="17" />
                <Eye v-else :size="17" />
              </button>
            </span>
            <small v-if="errors.newPassword" class="field-error">{{ errors.newPassword }}</small>
          </label>
          <label>
            确认新密码
            <input
              v-model="form.confirmPassword"
              :type="showPassword ? 'text' : 'password'"
              autocomplete="new-password"
              placeholder="再次输入新密码"
              :aria-invalid="!!errors.confirmPassword"
              @input="errors.confirmPassword = ''"
            />
            <small v-if="errors.confirmPassword" class="field-error">{{ errors.confirmPassword }}</small>
          </label>
          <button class="primary block login-submit" :disabled="store.loading">
            <span>{{ store.loading ? '重置中...' : '重置密码' }}</span>
            <ArrowRight :size="17" />
          </button>
        </form>
      </template>
    </section>

    <div v-if="store.toast" class="toast login-toast" role="alert">{{ store.toast }}</div>
  </main>
</template>
