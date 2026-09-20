import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { useAppStore } from './app'
import { apiDownload, apiUpload } from '../api/http'
import type {
  TicketDetail, TicketEventItem, TicketListResult, TicketMessage, TicketSettings,
} from '../utils/ticketLabels'

/** 功能关闭时后端返回的统一业务标识（计划 §7.3）。 */
export const TICKET_DISABLED_MESSAGE = 'TICKET_FEATURE_DISABLED'
const SETTINGS_THROTTLE_MS = 60_000

export const useTicketsStore = defineStore('tickets', () => {
  const appStore = useAppStore()

  const settings = ref<TicketSettings>({ enabled: false, version: 0, updatedAt: '' })
  const settingsLoaded = ref(false)
  const listResult = ref<TicketListResult | null>(null)
  const listLoading = ref(false)
  const listError = ref('')
  const detail = ref<TicketDetail | null>(null)
  const detailLoading = ref(false)
  const detailError = ref('')
  const messages = ref<TicketMessage[]>([])
  const events = ref<TicketEventItem[]>([])
  const submitting = ref(false)
  const attachmentUrls = ref(new Map<number, string>())

  const enabled = computed(() => settings.value.enabled)

  let lastSettingsFetch = 0
  let settingsSequence = 0

  /** 登录后、页面导航、窗口重新聚焦时刷新；在线期间最多 60 秒一次（计划 §7.3）。 */
  async function loadSettings(force = false) {
    if (!appStore.loggedIn) {
      settings.value = { enabled: false, version: 0, updatedAt: '' }
      settingsLoaded.value = false
      return settings.value
    }
    const now = Date.now()
    if (!force && settingsLoaded.value && now - lastSettingsFetch < SETTINGS_THROTTLE_MS) {
      return settings.value
    }
    const sequence = ++settingsSequence
    lastSettingsFetch = now
    try {
      const data = await appStore.request<TicketSettings>('/tickets/settings')
      if (sequence !== settingsSequence) return settings.value
      settings.value = data
      settingsLoaded.value = true
    } catch {
      // 配置加载失败按关闭处理（计划 §7.3），不误报“没有工单”。
      settings.value = { enabled: false, version: 0, updatedAt: '' }
      settingsLoaded.value = false
    }
    return settings.value
  }

  function requireEnabled() {
    if (!settings.value.enabled) {
      appStore.notify('问题反馈模块当前已关闭')
      return false
    }
    return true
  }

  async function list(params: Record<string, string | number>) {
    listLoading.value = true
    listError.value = ''
    try {
      listResult.value = await appStore.request<TicketListResult>(`/tickets${toQuery(params)}`)
      return listResult.value
    } catch (error: any) {
      if (isDisabledError(error)) {
        await loadSettings(true)
        listError.value = '问题反馈模块当前已关闭'
      } else {
        listError.value = error.message || '工单列表加载失败'
      }
      return null
    } finally {
      listLoading.value = false
    }
  }

  async function loadDetail(id: number, sequence?: { current: number }) {
    detailLoading.value = true
    detailError.value = ''
    if (sequence) sequence.current += 1
    const requestSequence = sequence?.current ?? 0
    try {
      const data = await appStore.request<TicketDetail>(`/tickets/${id}`)
      if (sequence && sequence.current !== requestSequence) return null
      detail.value = data
      return data
    } catch (error: any) {
      detail.value = null
      if (sequence && sequence.current !== requestSequence) return null
      detailError.value = isDisabledError(error) ? '问题反馈模块当前已关闭' : (error.message || '工单详情加载失败')
      return null
    } finally {
      detailLoading.value = false
    }
  }

  async function loadMessages(id: number, page = 1, size = 20) {
    const data = await appStore.request<{ items: TicketMessage[]; total: number }>(`/tickets/${id}/messages?page=${page}&size=${size}`)
    if (page === 1) messages.value = data.items
    return data
  }

  async function loadEvents(id: number) {
    const data = await appStore.request<{ items: TicketEventItem[] }>(`/tickets/${id}/events`)
    events.value = data.items
    return data
  }

  async function create(payload: Record<string, unknown>) {
    submitting.value = true
    try {
      const data = await appStore.request<TicketDetail>('/tickets', { method: 'POST', body: JSON.stringify(payload) })
      await loadSettings(true)
      return data
    } finally {
      submitting.value = false
    }
  }

  async function reply(id: number, payload: Record<string, unknown>) {
    submitting.value = true
    try {
      return await appStore.request<{ items: TicketMessage[] }>(`/tickets/${id}/messages`, { method: 'POST', body: JSON.stringify(payload) })
    } finally {
      submitting.value = false
    }
  }

  async function action(path: string, payload?: Record<string, unknown>) {
    submitting.value = true
    try {
      return await appStore.request<TicketDetail>(path, {
        method: 'POST',
        body: JSON.stringify(payload ?? {}),
      })
    } finally {
      submitting.value = false
    }
  }

  async function toggleFollow(id: number, follow: boolean) {
    detail.value = await appStore.request<TicketDetail>(`/tickets/${id}/follow`, { method: follow ? 'PUT' : 'DELETE' })
    return detail.value
  }

  async function toggleSameIssue(id: number, react: boolean) {
    detail.value = await appStore.request<TicketDetail>(`/tickets/${id}/same-issue`, { method: react ? 'PUT' : 'DELETE' })
    return detail.value
  }

  async function uploadAttachment(file: File): Promise<{ id: number } | null> {
    const form = new FormData()
    form.append('file', file)
    try {
      // multipart 不能走统一 request（它强制 JSON Content-Type）。
      return await apiUpload<{ id: number }>('/tickets/attachments', form, appStore.token)
    } catch (error: any) {
      appStore.notify(error.message || '截图上传失败')
      return null
    }
  }

  async function removeAttachment(id: number) {
    await appStore.request(`/tickets/attachments/${id}`, { method: 'DELETE' })
  }

  /** 图片读取需要 Authorization，先取 Blob 再转 object URL。 */
  async function attachmentUrl(id: number): Promise<string> {
    const cached = attachmentUrls.value.get(id)
    if (cached) return cached
    const blob = await apiDownload(`/tickets/attachments/${id}`, appStore.token)
    const url = URL.createObjectURL(blob)
    attachmentUrls.value.set(id, url)
    return url
  }

  function forgetAttachmentUrl(id: number) {
    const cached = attachmentUrls.value.get(id)
    if (cached) URL.revokeObjectURL(cached)
    attachmentUrls.value.delete(id)
  }

  function isDisabledError(error: any) {
    return error?.code === 503 || error?.message === TICKET_DISABLED_MESSAGE
  }

  function toQuery(params: Record<string, string | number>) {
    const query = Object.entries(params)
      .filter(([, value]) => value !== '' && value !== undefined && value !== null)
      .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(String(value))}`)
      .join('&')
    return query ? `?${query}` : ''
  }

  return {
    settings, settingsLoaded, enabled, listResult, listLoading, listError,
    detail, detailLoading, detailError, messages, events, submitting,
    loadSettings, requireEnabled, list, loadDetail, loadMessages, loadEvents,
    create, reply, action, toggleFollow, toggleSameIssue, uploadAttachment, removeAttachment,
    attachmentUrls, attachmentUrl, forgetAttachmentUrl,
  }
})
