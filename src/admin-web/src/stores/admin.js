import { defineStore } from 'pinia'
import { computed, reactive, ref } from 'vue'
import { apiRequest } from '../api/http'
import {
  accountCreationErrorMessage,
  accountUpdateErrorMessage,
  buildAdminCreatePayload,
  buildUserCreatePayload,
  buildUserEditPayload,
  canEditUserLoginIdentifiers,
  userLoginIdentifiersChanged,
  validateAdminCreateForm,
  validateUserCreateForm,
  validateUserEditForm,
} from '../utils/accountCreation'
import {
  buildRegistrationSettingsPayload,
  normalizeRegistrationSettings,
} from '../utils/registrationSettings'
import { clearRememberedLogin, readRememberedLogin, saveRememberedLogin } from '../utils/rememberedLogin'

const TOKEN_KEY = 'dayliane_admin_token'
const ROLE_KEY = 'dayliane_admin_role'
const THEME_KEY = 'dayliane_admin_theme'

export const useAdminStore = defineStore('admin', () => {
  const token = ref(localStorage.getItem(TOKEN_KEY) || '')
  const theme = ref(localStorage.getItem(THEME_KEY) || 'light')
  const activeResource = ref('users')
  const loading = ref(false)
  const createLoading = ref(false)
  const detailLoading = ref(false)
  const toast = ref('')
  const toastType = ref('success')
  const profile = ref(null)
  const isSuperAdmin = computed(() => profile.value?.role === 'super_admin')
  const page = ref({ list: [], total: 0, page: 1, size: 20 })
  const currentDetail = ref(null)
  // 「记住密码」：只有用户勾选并成功登录过，才会在下次打开时自动回填账号与密码。
  const rememberedLogin = readRememberedLogin()
  const loginForm = reactive({ username: rememberedLogin?.username || '', password: rememberedLogin?.password || '' })
  const rememberPassword = ref(Boolean(rememberedLogin))
  const userCreateForm = reactive({ email: '', password: '', phone: '', nickname: '', timezone: 'Asia/Shanghai' })
  const userEditForm = reactive({ id: null, email: '', phone: '', nickname: '', timezone: 'Asia/Shanghai' })
  const userEditOriginal = reactive({ email: '', phone: '', profileVersion: null })
  const userEditLoading = ref(false)
  const adminCreateForm = reactive({ username: '', password: '' })
  const registrationSettings = ref(null)
  const registrationSettingsLoading = ref(false)
  const registrationSettingsUpdating = ref(false)
  const aiConfig = ref(null)
  const aiConfigLoading = ref(false)
  const aiKeys = ref([])
  const aiEnvironmentFallback = ref({ configured: false, apiKeyMasked: '' })
  const aiKeyPoolRevision = ref(0)
  const aiKeyActionId = ref(null)
  const aiKeyTestResults = ref({})
  const aiTestLoading = ref(false)
  const aiTestResult = ref(null)
  const dashboardStats = ref({ users: 0, activeUsers: 0, teams: 0, pendingSchedules: 0, activeTeamTasks: 0, pendingReminders: 0, unreadNotifications: 0, aiCallsToday: 0 })

  // Search/filter state
  const searchKeyword = ref('')
  const filterStatus = ref('')
  const filterDateFrom = ref('')
  const filterDateTo = ref('')
  const sortKey = ref('createdAt')
  const sortOrder = ref('desc')

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
    pending: page.value.list.filter(item => ['pending', 'pending_approval', 'unassigned', 'all_rejected'].includes(item.status)).length,
  }))

  async function request(path, options = {}) {
    try {
      return await apiRequest(path, options, token.value)
    } catch (error) {
      if (error.code === 401) logout()
      throw error
    }
  }

  async function login() {
    loading.value = true
    try {
      const submittedUsername = loginForm.username.trim()
      const submittedPassword = loginForm.password
      const data = await apiRequest('/admin/auth/login', { method: 'POST', body: JSON.stringify({ username: submittedUsername, password: submittedPassword }) })
      token.value = data.accessToken
      localStorage.setItem(TOKEN_KEY, data.accessToken)
      // 只有勾选「记住密码」才落盘，未勾选时同时清掉可能存在的旧记录。
      if (rememberPassword.value) saveRememberedLogin(submittedUsername, submittedPassword)
      else clearRememberedLogin()
      await loadProfile()
      await fetchList()
      notify('登录成功')
    } catch (error) {
      notify(error.message)
    } finally {
      loading.value = false
    }
  }

  /** 勾选框变化：取消勾选立即清除本地已记住的账号密码（勾选时等登录成功再写入）。 */
  function syncRememberedLogin() {
    if (!rememberPassword.value) clearRememberedLogin()
  }

  function logout() {
    token.value = ''
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(ROLE_KEY)
    profile.value = null
    page.value = { list: [], total: 0, page: 1, size: 20 }
    currentDetail.value = null
    userCreateForm.email = ''
    userCreateForm.password = ''
    userCreateForm.phone = ''
    userCreateForm.nickname = ''
    userCreateForm.timezone = 'Asia/Shanghai'
    resetUserEditForm()
    adminCreateForm.username = ''
    adminCreateForm.password = ''
    createLoading.value = false
    registrationSettings.value = null
    registrationSettingsLoading.value = false
    registrationSettingsUpdating.value = false
  }

  function resetUserEditForm() {
    userEditForm.id = null
    userEditForm.email = ''
    userEditForm.phone = ''
    userEditForm.nickname = ''
    userEditForm.timezone = 'Asia/Shanghai'
    userEditOriginal.email = ''
    userEditOriginal.phone = ''
    userEditOriginal.profileVersion = null
    userEditLoading.value = false
  }

  function beginUserEdit(user) {
    userEditForm.id = Number(user?.id) || null
    userEditForm.email = String(user?.email || '')
    userEditForm.phone = String(user?.phone || '')
    userEditForm.nickname = String(user?.nickname || '')
    userEditForm.timezone = String(user?.timezone || 'Asia/Shanghai')
    userEditOriginal.email = userEditForm.email
    userEditOriginal.phone = userEditForm.phone
    const rawProfileVersion = user?.profileVersion
    const profileVersion = rawProfileVersion === null || rawProfileVersion === undefined || rawProfileVersion === ''
      ? Number.NaN
      : Number(rawProfileVersion)
    userEditOriginal.profileVersion = Number.isInteger(profileVersion) && profileVersion >= 0 ? profileVersion : null
  }

  function applyTheme() {
    document.documentElement.dataset.theme = theme.value
  }

  function toggleTheme() {
    theme.value = theme.value === 'dark' ? 'light' : 'dark'
    localStorage.setItem(THEME_KEY, theme.value)
    applyTheme()
  }

  applyTheme()

  async function loadProfile() {
    if (!token.value) return
    profile.value = await request('/admin/profile')
    localStorage.setItem(ROLE_KEY, profile.value?.role || '')
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
      queryParams.set('sort', `${params.sortKey || sortKey.value},${params.sortOrder || sortOrder.value}`)
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

  async function fetchOperationLogs(pageNum = 1, pageSize = 20, adminId = '', action = '', targetType = '', dateFrom = '', dateTo = '', keyword = '') {
    loading.value = true
    try {
      const params = new URLSearchParams()
      params.set('page', String(pageNum))
      params.set('size', String(pageSize))
      if (adminId) params.set('adminId', adminId)
      if (action) params.set('action', action)
      if (targetType) params.set('targetType', targetType)
      if (keyword) params.set('keyword', keyword)
      if (dateFrom) params.set('dateFrom', dateFrom)
      if (dateTo) params.set('dateTo', dateTo)
      page.value = await request(`/admin/operation-logs?${params.toString()}`)
    } catch (error) {
      notify(error.message)
    } finally {
      loading.value = false
    }
  }

  async function fetchDashboardStats() {
    try { dashboardStats.value = await request('/admin/dashboard/stats') }
    catch (error) { notify(error.message) }
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

  async function createUser() {
    const validationError = validateUserCreateForm(userCreateForm)
    if (validationError) {
      notify(validationError, 'error')
      return false
    }
    createLoading.value = true
    try {
      await request('/admin/users', { method: 'POST', body: JSON.stringify(buildUserCreatePayload(userCreateForm)) })
      userCreateForm.email = ''
      userCreateForm.password = ''
      userCreateForm.phone = ''
      userCreateForm.nickname = ''
      userCreateForm.timezone = 'Asia/Shanghai'
      await fetchList({ page: 1 })
      notify('用户已创建')
      return true
    } catch (error) {
      notify(accountCreationErrorMessage(error), 'error')
      return false
    } finally {
      createLoading.value = false
    }
  }

  async function createAdminUser() {
    if (!isSuperAdmin.value) {
      notify('只有超级管理员可以创建普通管理员', 'error')
      return false
    }
    const validationError = validateAdminCreateForm(adminCreateForm)
    if (validationError) {
      notify(validationError, 'error')
      return false
    }
    createLoading.value = true
    try {
      await request('/admin/admin-users', { method: 'POST', body: JSON.stringify(buildAdminCreatePayload(adminCreateForm)) })
      adminCreateForm.username = ''
      adminCreateForm.password = ''
      await fetchList({ page: 1 })
      notify('普通管理员已创建')
      return true
    } catch (error) {
      notify(accountCreationErrorMessage(error), 'error')
      return false
    } finally {
      createLoading.value = false
    }
  }

  async function updateUser({ confirmLoginIdentifierChange } = {}) {
    if (userEditLoading.value) return false
    const mayEditLoginIdentifiers = canEditUserLoginIdentifiers(profile.value?.role)
    const payload = buildUserEditPayload(userEditForm, {
      original: userEditOriginal,
      canEditLoginIdentifiers: mayEditLoginIdentifiers,
    })
    const validationError = validateUserEditForm({ ...userEditForm, ...payload })
    if (validationError) {
      notify(validationError, 'error')
      return false
    }

    const userId = userEditForm.id
    userEditLoading.value = true
    try {
      if (mayEditLoginIdentifiers && userLoginIdentifiersChanged(payload, userEditOriginal)) {
        if (typeof confirmLoginIdentifierChange !== 'function' || !await confirmLoginIdentifierChange()) return false
      }
      await request(`/admin/users/${userId}`, {
        method: 'PUT',
        body: JSON.stringify(payload),
      })
      await Promise.all([
        fetchList({ page: page.value.page, size: page.value.size }),
        fetchUserDetail(userId),
      ])
      notify('用户资料已更新')
      return true
    } catch (error) {
      notify(accountUpdateErrorMessage(error), 'error')
      return false
    } finally {
      userEditLoading.value = false
    }
  }

  async function fetchRegistrationSettings() {
    if (!token.value) return null
    registrationSettingsLoading.value = true
    try {
      const settings = normalizeRegistrationSettings(await request('/admin/registration-settings'))
      if (!settings) throw new Error('注册设置响应格式不正确')
      registrationSettings.value = settings
      return settings
    } catch (error) {
      notify(error.message || '获取注册设置失败', 'error')
      return null
    } finally {
      registrationSettingsLoading.value = false
    }
  }

  async function updateRegistrationEnabled(enabled) {
    if (!isSuperAdmin.value) {
      notify('只有超级管理员可以修改新用户注册设置', 'error')
      return null
    }
    if (registrationSettingsUpdating.value) return null

    registrationSettingsUpdating.value = true
    try {
      const data = await request('/admin/registration-settings', {
        method: 'PUT',
        body: JSON.stringify(buildRegistrationSettingsPayload(enabled)),
      })
      const settings = normalizeRegistrationSettings(data)
      if (!settings) throw new Error('注册设置响应格式不正确')
      registrationSettings.value = settings
      notify(settings.registrationEnabled ? '已允许新用户注册' : '已暂停新用户注册')
      return settings
    } catch (error) {
      notify(error.message || '更新注册设置失败', 'error')
      return null
    } finally {
      registrationSettingsUpdating.value = false
    }
  }

  async function adminSetScheduleStatus(scheduleId, status) {
    try {
      await request(`/admin/schedules/${scheduleId}/status`, { method: 'PUT', body: JSON.stringify({ status }) })
      await fetchList()
      notify('日程状态已更新')
    } catch (error) {
      notify(error.message)
    }
  }

  async function adminTeamTaskAction(taskId, action) {
    try {
      await request(`/admin/team-tasks/${taskId}/${action}`, { method: 'PUT' })
      await fetchList()
      notify('操作成功')
    } catch (error) {
      notify(error.message)
    }
  }

  function applyAiKeyPool(data) {
    if (!data) return
    aiKeys.value = Array.isArray(data.keys)
      ? [...data.keys].sort((left, right) => {
          const priorityDiff = Number(left.priority || 0) - Number(right.priority || 0)
          return priorityDiff || Number(left.id || 0) - Number(right.id || 0)
        })
      : []
    aiEnvironmentFallback.value = data.environmentFallback || { configured: false, apiKeyMasked: '' }
    aiKeyPoolRevision.value = Number(data.keyPoolRevision || 0)
  }

  function applyAiConfigResponse(data) {
    if (!data) return null
    const { keys, environmentFallback, keyPoolRevision, ...config } = data
    aiConfig.value = config
    applyAiKeyPool({ keys, environmentFallback, keyPoolRevision })
    return data
  }

  async function fetchAiConfig() {
    aiConfigLoading.value = true
    try {
      const data = await request('/admin/ai/config')
      return applyAiConfigResponse(data)
    } catch (error) {
      notify(error.message, 'error')
      return null
    } finally {
      aiConfigLoading.value = false
    }
  }

  async function updateAiConfig(payload) {
    aiConfigLoading.value = true
    try {
      const data = await request('/admin/ai/config', { method: 'PUT', body: JSON.stringify(payload) })
      const routingChanged = aiConfig.value?.modelName !== data?.modelName
        || aiConfig.value?.apiBaseUrl !== data?.apiBaseUrl
      applyAiConfigResponse(data)
      if (routingChanged) {
        aiKeyTestResults.value = {}
        aiTestResult.value = null
      }
      notify('AI 配置已更新')
      return data
    } catch (error) {
      notify(error.message, 'error')
      return null
    } finally {
      aiConfigLoading.value = false
    }
  }

  async function updateAiEnabled(enabled) {
    aiConfigLoading.value = true
    try {
      const data = await request('/admin/ai/enabled', { method: 'PUT', body: JSON.stringify({ enabled }) })
      applyAiConfigResponse(data)
      notify(enabled ? 'AI 已启用' : 'AI 已关闭')
      return data
    } catch (error) {
      notify(error.message, 'error')
      return null
    } finally {
      aiConfigLoading.value = false
    }
  }

  async function mutateAiKey(actionId, path, options, successMessage) {
    aiKeyActionId.value = actionId
    try {
      const data = await request(path, options)
      applyAiKeyPool(data)
      notify(successMessage)
      return data
    } catch (error) {
      notify(error.message, 'error')
      return null
    } finally {
      aiKeyActionId.value = null
    }
  }

  function createAiKey(payload) {
    return mutateAiKey('create', '/admin/ai/keys', {
      method: 'POST',
      body: JSON.stringify(payload),
    }, 'API Key 已添加')
  }

  async function updateAiKey(keyId, payload) {
    const data = await mutateAiKey(`update:${keyId}`, `/admin/ai/keys/${keyId}`, {
      method: 'PUT',
      body: JSON.stringify(payload),
    }, 'API Key 已更新')
    if (data) {
      const nextResults = { ...aiKeyTestResults.value }
      delete nextResults[keyId]
      aiKeyTestResults.value = nextResults
    }
    return data
  }

  async function deleteAiKey(keyId) {
    const data = await mutateAiKey(`delete:${keyId}`, `/admin/ai/keys/${keyId}`, {
      method: 'DELETE',
    }, 'API Key 已删除')
    if (data) {
      const nextResults = { ...aiKeyTestResults.value }
      delete nextResults[keyId]
      aiKeyTestResults.value = nextResults
    }
    return data
  }

  function setAiKeyEnabled(keyId, enabled) {
    return mutateAiKey(`enabled:${keyId}`, `/admin/ai/keys/${keyId}/enabled`, {
      method: 'PUT',
      body: JSON.stringify({ enabled }),
    }, enabled ? 'API Key 已启用' : 'API Key 已停用')
  }

  async function updateAiKeyOrder(orderedIds, revision) {
    aiKeyActionId.value = 'order'
    try {
      const data = await request('/admin/ai/keys/order', {
        method: 'PUT',
        body: JSON.stringify({ orderedIds, revision }),
      })
      applyAiKeyPool(data)
      notify('Key 优先级已更新')
      return data
    } catch (error) {
      if (Number(error.code) === 409) {
        notify('Key 顺序已被其他管理员更新，正在刷新最新配置', 'warning')
        await fetchAiConfig()
      } else {
        notify(error.message, 'error')
      }
      return null
    } finally {
      aiKeyActionId.value = null
    }
  }

  async function refreshAiKeyPoolSilently() {
    try {
      applyAiKeyPool(await request('/admin/ai/keys'))
    } catch {
      // The test result remains useful even if the follow-up status refresh fails.
    }
  }

  async function testAiKey(keyId) {
    aiKeyActionId.value = `test:${keyId}`
    aiKeyTestResults.value = { ...aiKeyTestResults.value, [keyId]: null }
    try {
      const data = await request(`/admin/ai/keys/${keyId}/test`, { method: 'POST' })
      aiKeyTestResults.value = { ...aiKeyTestResults.value, [keyId]: data }
      notify(data?.ok ? 'API Key 连接正常' : 'API Key 测试失败', data?.ok ? 'success' : 'error')
      return data
    } catch (error) {
      aiKeyTestResults.value = { ...aiKeyTestResults.value, [keyId]: { ok: false, error: error.message } }
      notify(error.message, 'error')
      return null
    } finally {
      await refreshAiKeyPoolSilently()
      aiKeyActionId.value = null
    }
  }

  async function fetchAiUsageLogs(params = {}) {
    loading.value = true
    try {
      const queryParams = new URLSearchParams()
      queryParams.set('page', String(params.page || 1))
      queryParams.set('size', String(params.size || 20))
      if (params.userId) queryParams.set('userId', params.userId)
      if (params.featureType) queryParams.set('featureType', params.featureType)
      if (params.status) queryParams.set('status', params.status)
      if (params.dateFrom) queryParams.set('dateFrom', params.dateFrom)
      if (params.dateTo) queryParams.set('dateTo', params.dateTo)
      page.value = await request(`/admin/ai/usage-logs?${queryParams.toString()}`)
    } catch (error) {
      notify(error.message)
    } finally {
      loading.value = false
    }
  }

  async function testAi() {
    aiTestLoading.value = true
    aiTestResult.value = null
    try {
      const data = await request('/admin/ai/test', { method: 'POST' })
      aiTestResult.value = data
      notify(data?.ok ? 'AI 容错链路测试通过' : 'AI 容错链路测试失败', data?.ok ? 'success' : 'error')
      return data
    } catch (error) {
      aiTestResult.value = { ok: false, error: error.message }
      notify(error.message, 'error')
      return null
    } finally {
      await refreshAiKeyPoolSilently()
      aiTestLoading.value = false
    }
  }

  function resetFilters() {
    searchKeyword.value = ''
    filterStatus.value = ''
    filterDateFrom.value = ''
    filterDateTo.value = ''
    sortKey.value = 'createdAt'
    sortOrder.value = 'desc'
  }

  function notify(message, type = 'success') {
    toast.value = message
    toastType.value = type
    clearTimeout(notify.timer)
    notify.timer = setTimeout(() => {
      toast.value = ''
      toastType.value = 'success'
    }, 2600)
  }

  function formatValue(value) {
    if (value === true) return '是'
    if (value === false) return '否'
    const labels = {
      pending_approval: '待团队审批',
      unassigned: '待重新分配',
      all_rejected: '全部拒绝',
      approval_rejected: '审批未通过',
    }
    if (typeof value === 'string' && labels[value]) return labels[value]
    return value ?? '-'
  }

  return {
    token, theme, activeResource, loading, createLoading, detailLoading, toast, toastType, profile, isSuperAdmin, page, currentDetail,
    loginForm, userCreateForm, userEditForm, userEditOriginal, userEditLoading, adminCreateForm, registrationSettings, registrationSettingsLoading,
    rememberPassword, syncRememberedLogin,
    registrationSettingsUpdating, resources, currentResource, stats,
    searchKeyword, filterStatus, filterDateFrom, filterDateTo,
    aiConfig, aiConfigLoading, aiKeys, aiEnvironmentFallback, aiKeyPoolRevision,
    aiKeyActionId, aiKeyTestResults, aiTestLoading, aiTestResult, dashboardStats, sortKey, sortOrder,
    login, logout, loadProfile, fetchList, fetchDetail,
    fetchUserDetail, fetchTeamDetail, fetchScheduleDetail,
    fetchTeamTaskDetail, fetchNotificationDetail, fetchReminderDetail,
    fetchOperationLogs, setUserStatus, setAdminUserStatus,
    createUser, createAdminUser, beginUserEdit, resetUserEditForm, updateUser,
    fetchRegistrationSettings, updateRegistrationEnabled,
    adminSetScheduleStatus, adminTeamTaskAction,
    resetFilters, notify, formatValue, toggleTheme,
    fetchAiConfig, updateAiConfig, updateAiEnabled,
    createAiKey, updateAiKey, deleteAiKey, setAiKeyEnabled, updateAiKeyOrder, testAiKey,
    fetchAiUsageLogs, testAi, fetchDashboardStats,
  }
})
