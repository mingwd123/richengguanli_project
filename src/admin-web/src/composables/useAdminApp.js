import { computed, reactive, ref } from 'vue'
import { apiRequest } from '../api/http'

const TOKEN_KEY = 'dayliane_admin_token'

export function useAdminApp() {
  const token = ref(localStorage.getItem(TOKEN_KEY) || '')
  const activeResource = ref('users')
  const loading = ref(false)
  const toast = ref('')
  const profile = ref(null)
  const page = ref({ list: [], total: 0, page: 1, size: 20 })
  const loginForm = reactive({ phone: '13800138000', password: 'Abc12345' })

  const resources = [
    { id: 'users', label: '用户管理', endpoint: '/admin/users', columns: ['id', 'phone', 'nickname', 'timezone', 'status', 'createdAt'] },
    { id: 'teams', label: '团队管理', endpoint: '/admin/teams', columns: ['id', 'name', 'inviteCode', 'ownerId', 'status', 'createdAt'] },
    { id: 'schedules', label: '日程查看', endpoint: '/admin/schedules', columns: ['id', 'userId', 'title', 'groupName', 'timeType', 'status', 'createdAt'] },
    { id: 'teamTasks', label: '团队任务', endpoint: '/admin/team-tasks', columns: ['id', 'teamId', 'creatorId', 'title', 'status', 'deadlineTime', 'createdAt'] },
    { id: 'notifications', label: '通知记录', endpoint: '/admin/notifications', columns: ['id', 'userId', 'type', 'title', 'relatedType', 'relatedId', 'isRead', 'createdAt'] },
    { id: 'reminders', label: '提醒记录', endpoint: '/admin/reminders', columns: ['id', 'userId', 'targetType', 'targetId', 'remindAt', 'status', 'createdAt'] },
  ]

  const currentResource = computed(() => resources.find(item => item.id === activeResource.value) || resources[0])
  const stats = computed(() => ({ total: page.value.total, active: page.value.list.filter(item => item.status === 'active').length, pending: page.value.list.filter(item => item.status === 'pending').length }))

  async function request(path, options = {}) { return apiRequest(path, options, token.value) }
  async function login() {
    loading.value = true
    try {
      const data = await apiRequest('/auth/login', { method: 'POST', body: JSON.stringify(loginForm) })
      token.value = data.accessToken
      localStorage.setItem(TOKEN_KEY, data.accessToken)
      await loadProfile()
      await loadResource(activeResource.value)
      notify('登录成功')
    } catch (error) { notify(error.message) } finally { loading.value = false }
  }
  function logout() { token.value = ''; localStorage.removeItem(TOKEN_KEY); profile.value = null; page.value = { list: [], total: 0, page: 1, size: 20 } }
  async function loadProfile() { if (!token.value) return; profile.value = await request('/admin/profile') }
  async function loadResource(resourceId = activeResource.value) {
    if (!token.value) return
    const resource = resources.find(item => item.id === resourceId) || resources[0]
    activeResource.value = resource.id
    loading.value = true
    try { page.value = await request(`${resource.endpoint}?page=1&size=50`) }
    catch (error) { notify(error.message) }
    finally { loading.value = false }
  }
  async function setUserStatus(user, status) {
    try {
      await request(`/admin/users/${user.id}/status`, { method: 'PUT', body: JSON.stringify({ status }) })
      await loadResource('users')
      notify(status === 'active' ? '用户已启用' : '用户已禁用')
    } catch (error) { notify(error.message) }
  }
  function notify(message) { toast.value = message; clearTimeout(notify.timer); notify.timer = setTimeout(() => { toast.value = '' }, 2600) }
  function formatValue(value) { if (value === true) return '是'; if (value === false) return '否'; return value ?? '-' }

  return { token, activeResource, loading, toast, profile, page, loginForm, resources, currentResource, stats, login, logout, loadProfile, loadResource, setUserStatus, formatValue }
}
