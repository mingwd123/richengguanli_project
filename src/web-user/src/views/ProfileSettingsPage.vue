<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowLeft, BookOpen, BrainCircuit, CalendarDays, Clock3, Copy, KeyRound, Mail, RotateCcw, ShieldCheck, UserRound } from 'lucide-vue-next'
import EmailVerificationFields from '../components/EmailVerificationFields.vue'
import { useAppStore } from '../stores/app'
import { normalizeEmail } from '../utils/auth'

const router = useRouter()
const store = useAppStore()
const emailVerification = ref<InstanceType<typeof EmailVerificationFields> | null>(null)
const emailSubmitting = ref(false)
const currentPasswordError = ref('')
const emailForm = reactive({ email: '', code: '', currentPassword: '' })
const icalSubscribing = ref(false)

const hasEmail = computed(() => !!store.profile?.email)
const emailPurpose = computed(() => hasEmail.value ? 'change_email' as const : 'bind_email' as const)

onMounted(async () => {
  try {
    await store.loadSubscribeToken()
  } catch {
    // 订阅信息加载失败不阻断设置页
  }
})

function goBack() {
  router.push('/profile')
}

function fullIcalUrl(path?: string) {
  if (!path) return ''
  if (/^https?:\/\//i.test(path)) return path
  return `${location.origin}${path}`
}

async function copyIcalPath() {
  const text = fullIcalUrl(store.subscribeTokenInfo?.path)
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
    store.notify('订阅链接已复制')
  } catch {
    store.notify('复制失败，请手动复制')
  }
}

async function resetIcalToken() {
  if (icalSubscribing.value) return
  if (!confirm('重置订阅 Token？重置后旧链接将立即失效，需要重新订阅。')) return
  icalSubscribing.value = true
  try {
    await store.resetSubscribeToken()
    store.notify('订阅 Token 已重置')
  } catch (e: any) {
    store.notify(e.message || '重置失败')
  } finally {
    icalSubscribing.value = false
  }
}

async function sendProfileEmailCode(email: string) {
  if (!emailForm.currentPassword) {
    currentPasswordError.value = '请输入当前密码'
    throw new Error('请输入当前密码')
  }
  if (store.profile?.email && normalizeEmail(email) === normalizeEmail(store.profile.email)) {
    emailForm.currentPassword = ''
    throw new Error('新邮箱不能与当前邮箱相同')
  }
  currentPasswordError.value = ''
  try {
    return await store.sendEmailCode({
      email,
      purpose: emailPurpose.value,
      currentPassword: emailForm.currentPassword,
    })
  } catch (error) {
    emailForm.currentPassword = ''
    throw error
  }
}

async function handleEmailUpdate() {
  currentPasswordError.value = emailForm.currentPassword ? '' : '请输入当前密码'
  const verificationValid = emailVerification.value?.validate() ?? false
  if (currentPasswordError.value || !verificationValid) {
    emailForm.currentPassword = ''
    return
  }
  if (store.profile?.email && normalizeEmail(emailForm.email) === normalizeEmail(store.profile.email)) {
    store.notify('新邮箱不能与当前邮箱相同')
    emailForm.currentPassword = ''
    return
  }

  emailSubmitting.value = true
  try {
    const result = await store.updateEmail(emailForm)
    if (!result.success) return
    emailForm.email = ''
    emailForm.code = ''
    emailVerification.value?.reset()
    if (result.reauthenticate) await router.replace('/login')
  } finally {
    emailForm.currentPassword = ''
    emailSubmitting.value = false
  }
}

async function handlePasswordUpdate() {
  if (await store.changePassword()) await router.replace('/login')
}

onBeforeUnmount(() => {
  emailForm.currentPassword = ''
  emailForm.code = ''
  store.passwordForm.oldPassword = ''
  store.passwordForm.newPassword = ''
  store.passwordForm.confirmPassword = ''
})
</script>

<template>
  <section class="form-card profile-settings-card">
    <header class="settings-page-head">
      <div>
        <p class="eyebrow">个人设置</p>
        <h1>账号与偏好</h1>
      </div>
      <button type="button" title="返回个人中心" aria-label="返回个人中心" @click="goBack">
        <ArrowLeft :size="17" />
      </button>
    </header>

    <section class="settings-section">
      <div class="settings-section-head">
        <span><UserRound :size="18" /></span>
        <div>
          <h2>个人资料</h2>
          <p>更新昵称和头像</p>
        </div>
      </div>
      <form @submit.prevent="store.updateProfile">
        <label>
          昵称
          <input v-model="store.profileForm.nickname" autocomplete="nickname" />
        </label>
        <label>
          头像 URL
          <input v-model="store.profileForm.avatarUrl" type="url" placeholder="https://example.com/avatar.jpg" />
        </label>
        <div v-if="store.profileForm.avatarUrl" class="avatar-preview">
          <img :src="store.profileForm.avatarUrl" alt="头像预览" />
        </div>
        <div class="form-actions">
          <button class="primary">保存资料</button>
        </div>
      </form>
    </section>

    <section class="settings-section">
      <div class="settings-section-head">
        <span><Mail :size="18" /></span>
        <div>
          <h2>{{ hasEmail ? '修改邮箱' : '绑定邮箱' }}</h2>
          <p>{{ hasEmail ? '当前邮箱已验证' : '绑定后可使用邮箱登录和找回密码' }}</p>
        </div>
      </div>
      <div class="current-email-row">
        <div>
          <small>当前邮箱</small>
          <strong>{{ store.profile?.email || '尚未绑定' }}</strong>
        </div>
        <span :class="['verification-status', { verified: store.profile?.emailVerifiedAt }]">
          <ShieldCheck :size="14" />
          {{ store.profile?.emailVerifiedAt ? '已验证' : '未验证' }}
        </span>
      </div>
      <form autocomplete="off" @submit.prevent="handleEmailUpdate">
        <label>
          当前密码
          <input
            v-model="emailForm.currentPassword"
            type="password"
            autocomplete="current-password"
            placeholder="用于确认是你本人"
            :disabled="emailSubmitting"
            :aria-invalid="!!currentPasswordError"
            @input="currentPasswordError = ''"
          />
          <small v-if="currentPasswordError" class="field-error">{{ currentPasswordError }}</small>
        </label>
        <EmailVerificationFields
          ref="emailVerification"
          v-model:email="emailForm.email"
          v-model:code="emailForm.code"
          :send-code="sendProfileEmailCode"
          :disabled="emailSubmitting"
          :email-label="hasEmail ? '新邮箱' : '邮箱'"
          email-autocomplete="off"
          @validation-error="emailForm.currentPassword = ''"
        />
        <div class="form-actions">
          <button class="primary" :disabled="emailSubmitting">
            {{ emailSubmitting ? '提交中...' : hasEmail ? '确认修改' : '确认绑定' }}
          </button>
        </div>
      </form>
    </section>

    <section class="settings-section">
      <div class="settings-section-head">
        <span><KeyRound :size="18" /></span>
        <div>
          <h2>修改密码</h2>
          <p>使用当前密码设置新密码</p>
        </div>
      </div>
      <form @submit.prevent="handlePasswordUpdate">
        <label>
          旧密码
          <input v-model="store.passwordForm.oldPassword" type="password" autocomplete="current-password" />
        </label>
        <label>
          新密码
          <input v-model="store.passwordForm.newPassword" type="password" autocomplete="new-password" />
        </label>
        <label>
          确认新密码
          <input v-model="store.passwordForm.confirmPassword" type="password" autocomplete="new-password" />
        </label>
        <div class="form-actions">
          <button class="primary">保存密码</button>
        </div>
      </form>
    </section>

    <section class="settings-section">
      <div class="settings-section-head">
        <span><Clock3 :size="18" /></span>
        <div>
          <h2>时区设置</h2>
          <p>用于日程和提醒时间</p>
        </div>
      </div>
      <form @submit.prevent="store.updateTimezone">
        <label>
          时区
          <input v-model="store.timezoneForm.timezone" placeholder="如 Asia/Shanghai" />
        </label>
        <div class="form-actions">
          <button class="primary">保存时区</button>
        </div>
      </form>
    </section>

    <section class="settings-section">
      <div class="settings-section-head">
        <span><CalendarDays :size="18" /></span>
        <div>
          <h2>日历订阅</h2>
          <p>将个人日程和团队任务同步到系统日历</p>
        </div>
      </div>
      <div class="ical-subscription">
        <div class="subscription-path">
          <input type="text" :value="fullIcalUrl(store.subscribeTokenInfo?.path)" readonly placeholder="加载订阅链接..." />
          <button type="button" class="plain-button" :disabled="!store.subscribeTokenInfo?.path" @click="copyIcalPath">
            <Copy :size="14" /> 复制
          </button>
        </div>
        <div class="subscription-actions">
          <button type="button" class="plain-button" :disabled="icalSubscribing" @click="resetIcalToken">
            <RotateCcw :size="14" /> {{ icalSubscribing ? '重置中...' : '重置订阅 Token' }}
          </button>
          <p class="hint">重置后旧链接立即失效，请重新订阅。</p>
        </div>
        <RouterLink class="plain-button subscription-help-link" to="/profile/settings/calendar-subscription">
          <BookOpen :size="14" /> 查看使用文档：各日历客户端订阅方法
        </RouterLink>
      </div>
    </section>

    <section class="settings-section">
      <div class="settings-section-head">
        <span><BrainCircuit :size="18" /></span>
        <div>
          <h2>AI 数据记录</h2>
          <p>管理 AI 调用内容的保存方式</p>
        </div>
      </div>
      <label class="toggle-row">
        <span>允许记录 AI 调用数据</span>
        <input type="checkbox" :checked="store.aiRecordEnabled" @change="store.toggleAiRecord()" />
      </label>
      <p class="hint">关闭后，AI 调用不再保存输入和输出内容。</p>
    </section>
  </section>
</template>
