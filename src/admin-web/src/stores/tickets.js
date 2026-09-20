import { defineStore } from 'pinia'
import { ref } from 'vue'
import { apiRequest, apiUpload, API_BASE } from '../api/http'

function token() {
  return localStorage.getItem('dayliane_admin_token') || ''
}

async function request(path, options = {}) {
  return apiRequest(path, { ...options, headers: options.headers }, token())
}

/** 管理端工单处理（计划 §10.2）。管理端不受用户端功能开关限制。 */
export const useTicketAdminStore = defineStore('ticketAdmin', () => {
  const settings = ref({ enabled: false, version: 0, updatedAt: '' })
  const list = ref([])
  const total = ref(0)
  const page = ref(1)
  const size = ref(20)
  const counts = ref({ openCount: 0, inProgressCount: 0, waitingCount: 0 })
  const loading = ref(false)
  const detail = ref(null)
  const messages = ref([])
  const events = ref([])
  const attachmentUrls = ref(new Map())

  /** el-image 无法带 Authorization 头，先把图片取成 blob 再展示。 */
  async function loadAttachmentUrl(id) {
    if (attachmentUrls.value.get(id)) return
    try {
      const response = await fetch(`${API_BASE}/admin/tickets/attachments/${id}`, {
        headers: { Authorization: `Bearer ${token()}` },
      })
      if (!response.ok) return
      attachmentUrls.value.set(id, URL.createObjectURL(await response.blob()))
    } catch { /* 展示占位即可 */ }
  }

  const filters = ref({ keyword: '', status: '', category: '', module: '', priority: '', author: '', assignee: '', dateFrom: '', dateTo: '' })

  async function loadSettings() {
    settings.value = await request('/admin/tickets/settings')
    return settings.value
  }

  async function updateSettings(enabled) {
    settings.value = await request('/admin/tickets/settings', { method: 'PUT', body: JSON.stringify({ enabled }) })
    return settings.value
  }

  async function fetchList(pageNumber = 1) {
    loading.value = true
    try {
      page.value = pageNumber
      const params = new URLSearchParams({ page: String(pageNumber), size: String(size.value) })
      for (const [key, value] of Object.entries(filters.value)) {
        if (value !== '' && value !== null && value !== undefined) params.set(key, String(value))
      }
      const data = await request(`/admin/tickets?${params.toString()}`)
      list.value = data.items
      total.value = data.total
      counts.value = data.counts
    } finally {
      loading.value = false
    }
  }

  async function fetchDetail(id) {
    detail.value = await request(`/admin/tickets/${id}`)
    return detail.value
  }

  async function fetchMessages(id, page = 1) {
    const data = await request(`/admin/tickets/${id}/messages?page=${page}&size=50`)
    messages.value = data.items
    return data
  }

  async function fetchEvents(id) {
    const data = await request(`/admin/tickets/${id}/events`)
    events.value = data.items
    return data
  }

  async function reply(id, payload) {
    await request(`/admin/tickets/${id}/reply`, { method: 'POST', body: JSON.stringify(payload) })
    await Promise.all([fetchMessages(id), fetchDetail(id), fetchEvents(id)])
  }

  async function changeStatus(id, payload) {
    detail.value = await request(`/admin/tickets/${id}/status`, { method: 'PUT', body: JSON.stringify(payload) })
    await Promise.all([fetchMessages(id), fetchEvents(id), fetchList(page.value)])
  }

  async function updateField(id, action, payload) {
    detail.value = await request(`/admin/tickets/${id}/${action}`, { method: 'PUT', body: JSON.stringify(payload) })
    await fetchList(page.value)
  }

  async function hide(id, hidden, payload) {
    detail.value = await request(`/admin/tickets/${id}/${hidden ? 'hide' : 'unhide'}`, { method: 'POST', body: JSON.stringify(payload) })
    await fetchList(page.value)
  }

  async function merge(id, payload) {
    detail.value = await request(`/admin/tickets/${id}/merge`, { method: 'POST', body: JSON.stringify(payload) })
    await fetchList(page.value)
  }

  async function unmerge(id, payload) {
    detail.value = await request(`/admin/tickets/${id}/unmerge`, { method: 'POST', body: JSON.stringify(payload) })
    await fetchList(page.value)
  }

  async function uploadAttachment(file) {
    const form = new FormData()
    form.append('file', file)
    return apiUpload('/admin/tickets/attachments', form, token())
  }

  return {
    settings, list, total, page, size, counts, loading, detail, messages, events, filters, attachmentUrls, loadAttachmentUrl,
    loadSettings, updateSettings, fetchList, fetchDetail, fetchMessages, fetchEvents,
    reply, changeStatus, updateField, hide, merge, unmerge, uploadAttachment,
  }
})
