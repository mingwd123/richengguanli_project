<script setup lang="ts">
import { computed, onBeforeUnmount, ref, useId, watch } from 'vue'
import { LoaderCircle, RotateCw, Send } from 'lucide-vue-next'
import type { EmailCodeResponse } from '../types'
import { isValidEmail, isValidEmailCode, normalizeEmail, sanitizeEmailCode } from '../utils/auth'

const props = withDefaults(defineProps<{
  email: string
  code: string
  sendCode: (email: string) => Promise<EmailCodeResponse | void>
  emailLabel?: string
  emailPlaceholder?: string
  emailAutocomplete?: string
  disabled?: boolean
}>(), {
  emailLabel: '邮箱',
  emailPlaceholder: 'name@example.com',
  emailAutocomplete: 'email',
  disabled: false,
})

const emit = defineEmits<{
  'update:email': [value: string]
  'update:code': [value: string]
  sent: [email: string]
  'validation-error': []
}>()

const fieldId = useId()
const sending = ref(false)
const countdown = ref(0)
const emailTouched = ref(false)
const codeTouched = ref(false)
const sendError = ref('')
const sentEmail = ref('')
let timer: ReturnType<typeof setInterval> | null = null

const emailError = computed(() => {
  if (!emailTouched.value || isValidEmail(props.email)) return ''
  return '请输入正确的邮箱地址'
})

const codeError = computed(() => {
  if (!codeTouched.value || isValidEmailCode(props.code)) return ''
  return '请输入 6 位数字验证码'
})

const sendText = computed(() => {
  if (sending.value) return '发送中'
  if (countdown.value > 0) return `${countdown.value} 秒后重发`
  return sentEmail.value ? '重新发送' : '获取验证码'
})

function stopCountdown() {
  if (timer) clearInterval(timer)
  timer = null
  countdown.value = 0
}

function startCountdown(seconds: number) {
  stopCountdown()
  countdown.value = Math.max(1, Math.floor(seconds || 60))
  timer = setInterval(() => {
    countdown.value -= 1
    if (countdown.value <= 0) stopCountdown()
  }, 1000)
}

function updateEmail(event: Event) {
  sendError.value = ''
  emit('update:email', (event.target as HTMLInputElement).value)
}

function updateCode(event: Event) {
  sendError.value = ''
  emit('update:code', sanitizeEmailCode((event.target as HTMLInputElement).value))
}

async function handleSend() {
  emailTouched.value = true
  sendError.value = ''
  if (!isValidEmail(props.email)) {
    emit('validation-error')
    return
  }

  sending.value = true
  const email = normalizeEmail(props.email)
  try {
    const result = await props.sendCode(email)
    emit('update:email', email)
    // A resend invalidates the previous challenge, including when the address is unchanged.
    emit('update:code', '')
    sentEmail.value = email
    startCountdown(result?.countdown || 60)
    emit('sent', email)
  } catch (error: any) {
    sendError.value = error?.message || '验证码发送失败，请稍后重试'
  } finally {
    sending.value = false
  }
}

function reset() {
  stopCountdown()
  sending.value = false
  emailTouched.value = false
  codeTouched.value = false
  sendError.value = ''
  sentEmail.value = ''
}

function validate() {
  emailTouched.value = true
  codeTouched.value = true
  return isValidEmail(props.email) && isValidEmailCode(props.code)
}

watch(() => props.email, (value, previousValue) => {
  if (props.code && normalizeEmail(value) !== normalizeEmail(previousValue)) emit('update:code', '')
  if (sentEmail.value && normalizeEmail(value) !== sentEmail.value) {
    stopCountdown()
    sentEmail.value = ''
  }
  if (emailTouched.value && isValidEmail(value)) sendError.value = ''
})

onBeforeUnmount(stopCountdown)
defineExpose({ reset, validate })
</script>

<template>
  <label :for="`${fieldId}-email`">
    {{ emailLabel }}
    <input
      :id="`${fieldId}-email`"
      :value="email"
      type="email"
      inputmode="email"
      :autocomplete="emailAutocomplete"
      :placeholder="emailPlaceholder"
      :disabled="disabled || sending"
      :aria-invalid="!!emailError"
      :aria-describedby="emailError ? `${fieldId}-email-error` : undefined"
      @input="updateEmail"
      @blur="emailTouched = true"
    />
    <small v-if="emailError" :id="`${fieldId}-email-error`" class="field-error">{{ emailError }}</small>
  </label>

  <label :for="`${fieldId}-code`">
    邮箱验证码
    <span class="email-code-row">
      <input
        :id="`${fieldId}-code`"
        :value="code"
        inputmode="numeric"
        autocomplete="one-time-code"
        maxlength="6"
        placeholder="6 位数字"
        :disabled="disabled"
        :aria-invalid="!!codeError"
        :aria-describedby="codeError ? `${fieldId}-code-error` : undefined"
        @input="updateCode"
        @blur="codeTouched = true"
      />
      <button
        type="button"
        class="email-code-button"
        :disabled="disabled || sending || countdown > 0"
        @click="handleSend"
      >
        <LoaderCircle v-if="sending" class="spinning" :size="15" />
        <RotateCw v-else-if="sentEmail && countdown === 0" :size="15" />
        <Send v-else :size="15" />
        <span>{{ sendText }}</span>
      </button>
    </span>
    <small v-if="codeError" :id="`${fieldId}-code-error`" class="field-error">{{ codeError }}</small>
    <small v-if="sendError" class="field-error" role="alert">{{ sendError }}</small>
    <small v-else-if="sentEmail" class="field-success" aria-live="polite">验证码已发送至 {{ sentEmail }}</small>
  </label>
</template>
