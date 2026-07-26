import { defineStore } from 'pinia'
import { computed, reactive, ref } from 'vue'
import { apiRequest } from '../api/http'

const TOKEN_KEY = 'dayliane_admin_token'

export const useAdminStore = defineStore('admin', () => {
  const token = ref(localStorage.getItem(TOKEN_KEY) || '')
  const activeResource = ref('users')
  const loading = ref(false)
  const detailLoading = ref(false)
  const toast = ref('')
  const profile = ref(null)
  const page = ref({ list: [], total: 0, page: 1, size: 20 })
  const currentDetail = ref(null)
  const loginForm = reactive({ username: 'admin', password: 'Admin12345' })
  const adminCreateForm = reactive({ username: '', password: '', role: 'admin' })

  // Search/filter state
  const searchKeyword = ref('')
  const filterStatus = ref('')
  const filterDateFrom = ref('')
  const filterDateTo = ref('')

  const resources = [
    { id: 'users', label: '用户管理', endpoint: '/admin/users', columns: ['id', 'phone', 'nickname', 'timezone', 'status', 'createdAt'] },
    { id: 'adminUsers', label: '管理员账号', endpoint: '/admin/admin-users', columns: ['id', 'username', 'role', 'status', 'lastLoginAt', 'lastLoginIp', 'createdAt'] },
    { id: 'teams', label: '团队管理', endpoint: '/admin/teams', columns: ['id', 'name', 'inviteCode', 'ownerId', 'status', 'createdAt'] },
    { id: 'schedules', label: '日程查看', endpoint: '/admin/schedules', columns: ['id', 'userId', 'title', 'groupName', 'timeType', 'status', 'createdAt'] },
    { id: 'teamTasks', label: '团队任务', endpoint: '/admin/team-tasks', columns: ['id', 'teamId', 'creatorId', 'title', 'status', 'deadlineTime', 'createdAt'] },
    { id: 'notifications', label: '通知记录', endpoint: '/admin/notifications', columns: ['id', 'userId', 'type', 'title', 'relatedType', 'relatedId', 'isRead', 'createdAt'] },
    { id: 'reminders', label: '提醒记录', endpoint: '/admin/reminders', columns: ['id', 'userId', 'targetType', 'targetId', 'remindAt', 'status', 'createdAt'] },
  ]

  const currentResource = computed(() => resources.find(item => item.id === activeResource.value) || resources[0])
  const stats = computed(() => ({
    total: page.value.total,
    active: page.value.list.filter(item => item.status === 'active').length,
    pending: page.value.list.filter(item => item.status === 'pending').length,
  }))

  async function request(path, options = {}) {
    return apiRequest(path, options, token.value)
  }

  async function login() {
    loading.value = true
    try {
      const data = await apiRequest('/admin/auth/login', { method: 'POST', body: JSON.stringify(loginForm) })
      token.value = data.accessToken
      localStorage.setItem(TOKEN_KEY, data.accessToken)
      await loadProfile()
      await fetchList()
      notify('登录成功')
    } catch (error) {
      notify(error.message)
    } finally {
      loading.value = false
    }
  }

  function logout() {
    token.value = ''
    localStorage.removeItem(TOKEN_KEY)
    profile.value = null
    page.value = { list: [], total: 0, page: 1, size: 20 }
    currentDetail.value = null
  }

  async function loadProfile() {
    if (!token.value) return
    profile.value = await request('/admin/profile')
  }

  async function fetchList(params = {}) {
    if (!token.value) return
    const resource = currentResource.value
    loading.value = true
    try {
      const queryParams = new URLSearchParams()
      queryParams.set('page', String(params.page || page.value.page))
      queryParams.set('size', String(params.size || page.value.size))
      if (params.keyword || searchKeyword.value) queryParams.set('keyword', params.keyword || searchKeyword.value)
      if (params.status || filterStatus.value) queryParams.set('status', params.status || filterStatus.value)
      if (params.dateFrom || filterDateFrom.value) queryParams.set('dateFrom', params.dateFrom || filterDateFrom.value)
      if (params.dateTo || filterDateTo.value) queryParams.set('dateTo', params.dateTo || filterDateTo.value)
      page.value = await request(`${resource.endpoint}?${queryParams.toString()}`)
    } catch (error) {
      notify(error.message)
    } finally {
      loading.value = false
    }
  }

  async function fetchDetail(id) {
    const resource = currentResource.value
    detailLoading.value = true
    try {
      currentDetail.value = await request(`${resource.endpoint}/${id}`)
    } catch (error) {
      notify(error.message)
      currentDetail.value = null
    } finally {
      detailLoading.value = false
    }
  }

  async function fetchUserDetail(id) {
    detailLoading.value = true
    try {
      currentDetail.value = await request(`/admin/users/${id}`)
    } catch (error) {
      notify(error.message)
      currentDetail.value = null
    } finally {
      detailLoading.value = false
    }
  }

  async function fetchTeamDetail(id) {
    detailLoading.value = true
    try {
      currentDetail.value = await request(`/admin/teams/${id}`)
    } catch (error) {
      notify(error.message)
      currentDetail.value = null
    } finally {
      detailLoading.value = false
    }
  }

  async function fetchScheduleDetail(id) {
    detailLoading.value = true
    try {
      currentDetail.value = await request(`/admin/schedules/${id}`)
    } catch (error) {
      notify(error.message)
      currentDetail.value = null
    } finally {
      detailLoading.value = false
    }
  }

  async function fetchTeamTaskDetail(id) {
    detailLoading.value = true
    try {
      currentDetail.value = await request(`/admin/team-tasks/${id}`)
    } catch (error) {
      notify(error.message)
      currentDetail.value = null
    } finally {
      detailLoading.value = false
    }
  }

  async function fetchNotificationDetail(id) {
    detailLoading.value = true
    try {
      currentDetail.value = await request(`/admin/notifications/${id}`)
    } catch (error) {
      notify(error.message)
      currentDetail.value = null
    } finally {
      detailLoading.value = false
    }
  }

  async function fetchReminderDetail(id) {
    detailLoading.value = true
    try {
      currentDetail.value = await request(`/admin/reminders/${id}`)
    } catch (error) {
      notify(error.message)
      currentDetail.value = null
    } finally {
      detailLoading.value = false
    }
  }

  async function fetchOperationLogs(pageNum = 1, pageSize = 20, adminId = '', action = '', targetType = '', dateFrom = '', dateTo = '') {
    loading.value = true
    try {
      const params = new URLSearchParams()
      params.set('page', String(pageNum))
      params.set('size', String(pageSize))
      if (adminId) params.set('adminId', adminId)
      if (action) params.set('action', action)
      if (targetType) params.set('targetType', targetType)
      if (dateFrom) params.set('dateFrom', dateFrom)
      if (dateTo) params.set('dateTo', dateTo)
      page.value = await request(`/admin/operation-logs?${params.toString()}`)
    } catch (error) {
      notify(error.message)
    } finally {
      loading.value = false
    }
  }

  async function setUserStatus(user, status) {
    try {
      await request(`/admin/users/${user.id}/status`, { method: 'PUT', body: JSON.stringify({ status }) })
      await fetchList()
      notify(status === 'active' ? '用户已启用' : '用户已禁用')
    } catch (error) {
      notify(error.message)
    }
  }

  async function setAdminUserStatus(adminUser, status) {
    try {
      await request(`/admin/admin-users/${adminUser.id}/status`, { method: 'PUT', body: JSON.stringify({ status }) })
      await fetchList()
      notify(status === 'active' ? '管理员已启用' : '管理员已禁用')
    } catch (error) {
      notify(error.message)
    }
  }

  async function createAdminUser() {
    loading.value = true
    try {
      await request('/admin/admin-users', { method: 'POST', body: JSON.stringify(adminCreateForm) })
      adminCreateForm.username = ''
      adminCreateForm.password = ''
      adminCreateForm.role = 'admin'
      await fetchList()
      notify('管理员已创建')
    } catch (error) {
      notify(error.message)
    } finally {
      loading.value = false
    }
  }

  function resetFilters() {
    searchKeyword.value = ''
    filterStatus.value = ''
    filterDateFrom.value = ''
    filterDateTo.value = ''
  }

  function notify(message) {
    toast.value = message
    clearTimeout(notify.timer)
    notify.timer = setTimeout(() => { toast.value = '' }, 2600)
  }

  function formatValue(value) {
    if (value === true) return '是'
    if (value === false) return '否'
    return value ?? '-'
  }

  return {
    token, activeResource, loading, detailLoading, toast, profile, page, currentDetail,
    loginForm, adminCreateForm, resources, currentResource, stats,
    searchKeyword, filterStatus, filterDateFrom, filterDateTo,
    login, logout, loadProfile, fetchList, fetchDetail,
    fetchUserDetail, fetchTeamDetail, fetchScheduleDetail,
    fetchTeamTaskDetail, fetchNotificationDetail, fetchReminderDetail,
    fetchOperationLogs, setUserStatus, setAdminUserStatus,
    createAdminUser, resetFilters, notify, formatValue,
  }
})
