import { defineStore } from 'pinia'
import { ref, computed, reactive } from 'vue'
import { apiDownload, apiRequest } from '../api/http'
import type {
  UserProfile, Schedule, TaskGroup, Team, MyTask, Notification,
  TodayOverview, TimelineItem, CalendarDay, ScheduleForm, TaskForm,
  LoginForm, RegisterForm, PageResult, UpcomingOverview, NotificationPreference,
  ScheduleListResult, ScheduleViewMode, SectionSummary, FatigueProfile,
  FatigueDailySummary, FatigueHistory, FatiguePreview, FatigueSurveyComparison,
  FatigueSurveyToday, EmailCodeRequest, EmailCodeResponse, ResetPasswordForm,
  UpdateEmailForm
} from '../types'
import {
  fetchRegistrationStatus,
  loginAccount,
  registerAccount,
  resetAccountPassword,
  sendEmailVerificationCode,
  updateAccountEmail,
} from '../api/auth'
import {
  isValidEmail,
  isValidEmailCode,
  isValidOptionalNickname,
  isValidOptionalPhone,
  isValidPassword,
  normalizeEmail,
} from '../utils/auth'
import { toSchedulePayload, toApiTimePayload, normalizeTimelineItem, buildMonthDays, primaryTime, setDisplayTimezone } from '../utils/helpers'
import { buildTimelineStats, isTimelineItemOpen } from '../utils/timeline'

const TOKEN_KEY = 'dayliane_token'
const REFRESH_TOKEN_KEY = 'dayliane_refresh_token'
const AI_RECORD_KEY = 'dayliane_ai_record_enabled'
const THEME_KEY = 'dayliane_theme'
const BROWSER_NOTICE_IDS_KEY = 'dayliane_browser_notice_ids'
const SCHEDULE_VIEW_KEY_PREFIX = 'dayliane_schedule_view_'
const REGISTRATION_UNAVAILABLE_MESSAGE = '当前暂不开放新用户注册'

type ListState = {
  page: number
  size: number
  total: number
  sort: string
  loading: boolean
  keyword?: string
  status?: string
  dateFrom?: string
  dateTo?: string
  isRead?: string
  teamId?: string
}

function listState(size: number, sort: string, filters: Partial<ListState> = {}) {
  return reactive<ListState>({ page: 1, size, total: 0, sort, loading: false, ...filters })
}

function queryPath(path: string, params: Record<string, unknown>) {
  const query = new URLSearchParams()
  Object.entries(params).forEach(([key, value]) => {
    if (value !== '' && value !== undefined && value !== null) query.set(key, String(value))
  })
  return `${path}?${query.toString()}`
}

export const useAppStore = defineStore('app', () => {
  const token = ref(localStorage.getItem(TOKEN_KEY) || '')
  const refreshToken = ref(localStorage.getItem(REFRESH_TOKEN_KEY) || '')
  const theme = ref(localStorage.getItem(THEME_KEY) || 'light')
  const browserNoticePermission = ref(typeof Notification === 'undefined' ? 'unsupported' : Notification.permission)
  const toast = ref('')
  const loading = ref(false)
  const registrationEnabled = ref(false)
  const registrationStatusLoading = ref(false)
  const registrationStatusChecked = ref(false)
  const registrationStatusError = ref(false)
  const scheduleModalOpen = ref(false)
  const profile = ref<UserProfile | null>(null)
  const schedules = ref<Schedule[]>([])
  const taskGroups = ref<TaskGroup[]>([])
  const teamTaskGroups = ref<Record<number, TaskGroup[]>>({})
  const teams = ref<Team[]>([])
  const myTasks = ref<MyTask[]>([])
  const createdTasks = ref<MyTask[]>([])
  const teamTasks = ref<MyTask[]>([])
  const notifications = ref<Notification[]>([])
  const calendarSchedules = ref<Schedule[]>([])
  const calendarTasks = ref<MyTask[]>([])
  const schedulePage = listState(12, 'manual', { keyword: '', status: '', dateFrom: '', dateTo: '' })
  const teamPage = listState(10, 'created_desc')
  const assignedTaskPage = listState(12, 'manual', { keyword: '', status: '', dateFrom: '', dateTo: '' })
  const createdTaskPage = listState(12, 'manual', { keyword: '', status: '', dateFrom: '', dateTo: '' })
  const teamTaskPage = listState(12, 'manual', { keyword: '', status: '', dateFrom: '', dateTo: '', teamId: '' })
  const notificationPage = listState(12, 'created_desc', { isRead: '' })
  const today = ref<TodayOverview>({ personalSchedules: [], teamTasks: [], unreadNotificationCount: 0, groups: [] })
  const upcomingSeven = ref<UpcomingOverview>({ timezone: '', dateFrom: '', dateTo: '', personalSchedules: [], teamTasks: [], list: [] })
  const viewMode = ref<ScheduleViewMode>('time')
  const urgencyLevelFilter = ref<number | null>(null)
  const fatigueLevelFilter = ref<number | null>(null)
  const sectionSummaries = ref<SectionSummary[]>([])
  const scheduleStatusCounts = ref<Partial<Record<'pending' | 'completed' | 'cancelled', number>>>({})
  const fatigueProfile = ref<FatigueProfile | null>(null)
  const fatigueDaily = ref<FatigueDailySummary | null>(null)
  const fatigueSurveyToday = ref<FatigueSurveyToday | null>(null)
  const fatigueSurveyComparison = ref<FatigueSurveyComparison | null>(null)
  const fatigueHistory = ref<FatigueHistory | null>(null)
  const notificationDetail = ref<Notification | null>(null)
  const notificationPreferences = ref<NotificationPreference>({ browserEnabled: false, taskAssignedEnabled: true, taskStatusEnabled: true, reminderEnabled: true, fatigueAlertEnabled: true, fatigueSurveyEnabled: true, quietStartTime: '', quietEndTime: '', reminderPresetMinutes: [15, 30, 60, 1440] })
  const selectedDate = ref('')

  const loginForm = reactive<LoginForm>({ account: '13800138000', password: 'Abc12345' })
  const registerForm = reactive<RegisterForm>({ email: '', code: '', phone: '', password: '', confirmPassword: '', nickname: '' })
  const scheduleForm = reactive<ScheduleForm>({ title: '', description: '', groupId: '', groupName: '', timeType: 'point_event', startTime: '', endTime: '', deadlineTime: '', remindAt: '', urgencyLevel: 3, fatigueLevel: 3 })
  const groupForm = reactive({ name: '' })
  const teamForm = reactive({ name: '' })
  const taskForm = reactive<TaskForm>({ teamId: '', groupId: '', title: '', description: '', deadlineTime: '', startTime: '', remindAt: '', assigneeUserIds: [] })
  const joinForm = reactive({ inviteCode: '' })
  const profileForm = reactive({ nickname: '', avatarUrl: '', timezone: '' })
  const passwordForm = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })
  const timezoneForm = reactive({ timezone: '' })

  const pendingScheduleCount = computed(() => schedules.value.filter(s => s.status === 'pending').length)
  const activeTaskCount = computed(() => myTasks.value.filter(t =>
    ['active', 'unassigned'].includes(t.status) && ['pending', 'accepted'].includes(t.assignStatus || '')
  ).length)
  const activeTeam = computed(() => teams.value[0] || null)
  const timelineItems = computed<TimelineItem[]>(() =>
    [...schedules.value.map(i => normalizeTimelineItem(i, 'schedule')), ...myTasks.value.map(i => normalizeTimelineItem(i, 'team_task'))]
      .filter(isTimelineItemOpen)
      .filter(i => i.sortAt)
      .sort((a, b) => new Date(a.sortAt).getTime() - new Date(b.sortAt).getTime()))
  const upcoming = computed(() => timelineItems.value.slice(0, 6))
  const timelineStats = computed(() => {
    return buildTimelineStats(timelineItems.value, { timezone: profile.value?.timezone || Intl.DateTimeFormat().resolvedOptions().timeZone || 'Asia/Shanghai' })
  })
  const calendarItems = computed(() => [
    ...calendarSchedules.value.map(item => ({ ...item, sourceType: 'schedule' })),
    ...calendarTasks.value.filter(item => !['pending_approval', 'approval_rejected'].includes(item.status)).map(item => ({ ...item, sourceType: 'team_task' }))
  ])
  const monthDays = computed<CalendarDay[]>(() => buildMonthDays(calendarItems.value, profile.value?.timezone))
  const loggedIn = computed(() => !!token.value)
  const aiRecordEnabled = ref(localStorage.getItem(AI_RECORD_KEY) !== 'false')

  let refreshPromise: Promise<boolean> | null = null
  let sessionRevision = 0
  let registrationStatusRequestVersion = 0
  let loadAllRequestVersion = 0
  let calendarRequestVersion = 0
  let unreadCountRequestVersion = 0
  let notificationPollVersion = 0
  const listRequestVersions = new WeakMap<object, number>()

  function persistTokens(access: string, refresh: string) {
    token.value = access
    refreshToken.value = refresh
    localStorage.setItem(TOKEN_KEY, access)
    localStorage.setItem(REFRESH_TOKEN_KEY, refresh)
  }

  function establishSession(access: string, refresh: string) {
    sessionRevision += 1
    persistTokens(access, refresh)
  }

  function clearAuthSecrets() {
    clearLoginSecret()
    clearRegisterSecrets()
    clearPasswordSecrets()
  }

  function clearLoginSecret() {
    loginForm.password = ''
  }

  function clearRegisterSecrets() {
    registerForm.code = ''
    registerForm.password = ''
    registerForm.confirmPassword = ''
  }

  function clearPasswordSecrets() {
    passwordForm.oldPassword = ''
    passwordForm.newPassword = ''
    passwordForm.confirmPassword = ''
  }

  function clearSession() {
    sessionRevision += 1
    refreshPromise = null
    loading.value = false
    clearAuthSecrets()
    token.value = ''
    refreshToken.value = ''
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(REFRESH_TOKEN_KEY)
    profile.value = null
    schedules.value = []; taskGroups.value = []; teamTaskGroups.value = {}; teams.value = []; myTasks.value = []; createdTasks.value = []; teamTasks.value = []; notifications.value = []
    notificationPreferences.value = { browserEnabled: false, taskAssignedEnabled: true, taskStatusEnabled: true, reminderEnabled: true, fatigueAlertEnabled: true, fatigueSurveyEnabled: true, quietStartTime: '', quietEndTime: '', reminderPresetMinutes: [15, 30, 60, 1440] }
    calendarSchedules.value = []; calendarTasks.value = []
    ;[schedulePage, teamPage, assignedTaskPage, createdTaskPage, teamTaskPage, notificationPage].forEach(state => {
      state.page = 1
      state.total = 0
      state.loading = false
    })
    today.value = { personalSchedules: [], teamTasks: [], unreadNotificationCount: 0, groups: [] }
    upcomingSeven.value = { timezone: '', dateFrom: '', dateTo: '', personalSchedules: [], teamTasks: [], list: [] }
    viewMode.value = 'time'
    urgencyLevelFilter.value = null
    fatigueLevelFilter.value = null
    sectionSummaries.value = []
    scheduleStatusCounts.value = {}
    fatigueProfile.value = null
    fatigueDaily.value = null
    fatigueSurveyToday.value = null
    fatigueSurveyComparison.value = null
    fatigueHistory.value = null
  }

  async function refreshSession() {
    if (!refreshToken.value) return false
    if (!refreshPromise) {
      const requestedRefreshToken = refreshToken.value
      const requestedRevision = sessionRevision
      let pending: Promise<boolean>
      pending = apiRequest<{ accessToken: string; refreshToken: string }>('/auth/refresh-token', {
        method: 'POST', body: JSON.stringify({ refreshToken: requestedRefreshToken })
      }).then(data => {
        if (sessionRevision !== requestedRevision || refreshToken.value !== requestedRefreshToken) return false
        persistTokens(data.accessToken, data.refreshToken)
        return true
      }).catch(() => {
        if (sessionRevision === requestedRevision && refreshToken.value === requestedRefreshToken) clearSession()
        return false
      }).finally(() => {
        if (refreshPromise === pending) refreshPromise = null
      })
      refreshPromise = pending
    }
    return refreshPromise
  }

  async function request<T = any>(path: string, options: RequestInit = {}) {
    const attemptedToken = token.value
    const requestSession = sessionRevision
    const ensureCurrentSession = (data: T) => {
      if (!path.startsWith('/auth/') && requestSession !== sessionRevision) {
        const error: any = new Error('登录状态已变化')
        error.code = 'SESSION_CHANGED'
        throw error
      }
      return data
    }
    try {
      return ensureCurrentSession(await apiRequest<T>(path, options, attemptedToken))
    } catch (error: any) {
      const canRefresh = error.code === 401 && !path.startsWith('/auth/') && !!refreshToken.value
      if (!canRefresh) throw error
      if (requestSession !== sessionRevision) throw error
      if (attemptedToken !== token.value) return ensureCurrentSession(await apiRequest<T>(path, options, token.value))
      if (!(await refreshSession())) throw error
      if (requestSession !== sessionRevision) throw error
      return ensureCurrentSession(await apiRequest<T>(path, options, token.value))
    }
  }

  async function aiRequest(path: string, payload: Record<string, any> = {}) {
    return request<{ rawText: string; [key: string]: any }>('/ai' + path, {
      method: 'POST', body: JSON.stringify({ ...payload, recordUsage: aiRecordEnabled.value })
    })
  }

  function openScheduleModal() { scheduleModalOpen.value = true }
  function closeScheduleModal() { scheduleModalOpen.value = false }

  async function login() {
    const account = loginForm.account.trim()
    if (!account) { clearLoginSecret(); notify('请输入邮箱或手机号'); return false }
    if (!loginForm.password) { clearLoginSecret(); notify('请输入密码'); return false }
    loading.value = true
    try {
      const data = await loginAccount({ account: isValidEmail(account) ? normalizeEmail(account) : account, password: loginForm.password })
      establishSession(data.accessToken, data.refreshToken)
      await loadAll()
      notify('登录成功')
      return true
    } catch (e: any) { notify(e.message); return false } finally {
      loginForm.password = ''
      loading.value = false
    }
  }

  async function loadRegistrationStatus() {
    const version = ++registrationStatusRequestVersion
    registrationEnabled.value = false
    registrationStatusLoading.value = true
    try {
      const data = await fetchRegistrationStatus()
      if (version !== registrationStatusRequestVersion) return registrationEnabled.value
      registrationEnabled.value = data?.registrationEnabled === true
      registrationStatusChecked.value = true
      registrationStatusError.value = false
      return registrationEnabled.value
    } catch {
      if (version !== registrationStatusRequestVersion) return false
      registrationEnabled.value = false
      registrationStatusChecked.value = true
      registrationStatusError.value = true
      return false
    } finally {
      if (version === registrationStatusRequestVersion) registrationStatusLoading.value = false
    }
  }

  function markRegistrationUnavailable() {
    registrationEnabled.value = false
    registrationStatusChecked.value = true
    registrationStatusError.value = false
  }

  function isRegistrationUnavailable(error: any) {
    return Number(error?.code) === 503
  }

  async function register() {
    if (!registrationEnabled.value) {
      clearRegisterSecrets()
      notify(REGISTRATION_UNAVAILABLE_MESSAGE)
      return false
    }
    if (!isValidEmail(registerForm.email)) { clearRegisterSecrets(); notify('请输入正确的邮箱'); return false }
    if (!isValidEmailCode(registerForm.code)) { clearRegisterSecrets(); notify('请输入 6 位邮箱验证码'); return false }
    if (!isValidOptionalPhone(registerForm.phone)) { clearRegisterSecrets(); notify('请输入正确的手机号'); return false }
    if (!isValidPassword(registerForm.password)) { clearRegisterSecrets(); notify('密码至少 8 位，且包含字母和数字'); return false }
    if (registerForm.password !== registerForm.confirmPassword) { clearRegisterSecrets(); notify('两次密码不一致'); return false }
    if (!isValidOptionalNickname(registerForm.nickname)) { clearRegisterSecrets(); notify('昵称不能超过 50 个字符'); return false }
    loading.value = true
    try {
      const phone = registerForm.phone.trim()
      const data = await registerAccount({
        email: normalizeEmail(registerForm.email),
        code: registerForm.code,
        password: registerForm.password,
        phone: phone || undefined,
        nickname: registerForm.nickname.trim() || undefined,
        timezone: Intl.DateTimeFormat().resolvedOptions().timeZone || 'Asia/Shanghai',
      })
      establishSession(data.accessToken, data.refreshToken)
      await loadAll()
      notify('注册成功')
      return true
    } catch (e: any) {
      if (isRegistrationUnavailable(e)) {
        markRegistrationUnavailable()
        notify(REGISTRATION_UNAVAILABLE_MESSAGE)
      } else {
        notify(e.message)
      }
      return false
    } finally {
      registerForm.code = ''
      registerForm.password = ''
      registerForm.confirmPassword = ''
      loading.value = false
    }
  }

  async function sendEmailCode(payload: EmailCodeRequest): Promise<EmailCodeResponse> {
    if (!isValidEmail(payload.email)) throw new Error('请输入正确的邮箱')
    if (['bind_email', 'change_email'].includes(payload.purpose) && !payload.currentPassword) {
      throw new Error('请输入当前密码')
    }
    try {
      return await sendEmailVerificationCode({
        ...payload,
        email: normalizeEmail(payload.email),
      }, token.value)
    } catch (error: any) {
      if (payload.purpose === 'register' && isRegistrationUnavailable(error)) {
        markRegistrationUnavailable()
        const unavailable: any = new Error(REGISTRATION_UNAVAILABLE_MESSAGE)
        unavailable.code = error.code
        throw unavailable
      }
      throw error
    }
  }

  async function resetPassword(form: ResetPasswordForm) {
    if (!isValidEmail(form.email)) { form.code = ''; form.newPassword = ''; form.confirmPassword = ''; notify('请输入正确的邮箱'); return false }
    if (!isValidEmailCode(form.code)) { form.code = ''; form.newPassword = ''; form.confirmPassword = ''; notify('请输入 6 位邮箱验证码'); return false }
    if (!isValidPassword(form.newPassword)) { form.code = ''; form.newPassword = ''; form.confirmPassword = ''; notify('密码至少 8 位，且包含字母和数字'); return false }
    if (form.newPassword !== form.confirmPassword) { form.code = ''; form.newPassword = ''; form.confirmPassword = ''; notify('两次密码不一致'); return false }
    loading.value = true
    try {
      await resetAccountPassword({
        email: normalizeEmail(form.email),
        code: form.code,
        newPassword: form.newPassword,
      })
      notify('密码已重置，请使用新密码登录')
      return true
    } catch (e: any) { notify(e.message); return false } finally {
      form.code = ''
      form.newPassword = ''
      form.confirmPassword = ''
      loading.value = false
    }
  }

  async function updateEmail(form: UpdateEmailForm) {
    const failure = { success: false, reauthenticate: false }
    if (!form.currentPassword) { notify('请输入当前密码'); return failure }
    if (!isValidEmail(form.email)) { notify('请输入正确的邮箱'); return failure }
    if (!isValidEmailCode(form.code)) { notify('请输入 6 位邮箱验证码'); return failure }
    const hadEmail = !!profile.value?.email
    try {
      const result = await updateAccountEmail(request, {
        email: normalizeEmail(form.email),
        code: form.code,
        currentPassword: form.currentPassword,
      })
      if (result.reauthenticate) {
        clearSession()
        notify('邮箱已修改，请重新登录')
        return { success: true, reauthenticate: true }
      }
      await loadAll()
      notify(hadEmail ? '邮箱已更新' : '邮箱已绑定')
      return { success: true, reauthenticate: false }
    } catch (e: any) { notify(e.message); return failure }
  }

  async function logout() {
    const access = token.value
    const refresh = refreshToken.value
    clearSession()
    if (!access) return
    try {
      await apiRequest('/auth/logout', { method: 'POST', body: JSON.stringify({ refreshToken: refresh }) }, access)
    } catch {
      // Local logout must still succeed when the API is unavailable.
    }
  }

  async function loadAll() {
    if (!token.value) return
    const requestVersion = ++loadAllRequestVersion
    const requestSession = sessionRevision
    const isCurrentRequest = () => requestVersion === loadAllRequestVersion && requestSession === sessionRevision
    loading.value = true
    try {
      const [me, groupPage, todayData, upcomingData, preferences, fatigueProfileData, fatigueSurveyData] = await Promise.all([
        request<UserProfile>('/user/profile'), request<PageResult<TaskGroup>>('/task-groups?scope=personal'),
        request<TodayOverview>('/home/today'), request<UpcomingOverview>('/home/upcoming'),
        request<NotificationPreference>('/notifications/preferences'),
        request<FatigueProfile>('/fatigue/profile'), request<FatigueSurveyToday>('/fatigue/survey/today')
      ])
      if (!isCurrentRequest()) return
      profile.value = me; setDisplayTimezone(me.timezone); profileForm.nickname = me.nickname; profileForm.avatarUrl = me.avatarUrl || ''; profileForm.timezone = me.timezone; timezoneForm.timezone = me.timezone
      loadStoredScheduleView(me.id)
      notificationPreferences.value = preferences
      fatigueProfile.value = fatigueProfileData
      fatigueSurveyToday.value = fatigueSurveyData
      fatigueDaily.value = fatigueSurveyData.daily || todayData.fatigue || null
      taskGroups.value = (groupPage.list || []).sort((a, b) => a.sortOrder - b.sortOrder)
      if (!scheduleForm.groupId && taskGroups.value[0]) scheduleForm.groupId = String(taskGroups.value[0].id)
      today.value = todayData; upcomingSeven.value = upcomingData
      await Promise.all([loadSchedules(), loadTeams(), loadAssignedTasks(), loadCreatedTasks(), loadNotifications()])
      if (!isCurrentRequest()) return
      if (teams.value[0] && !taskForm.teamId) taskForm.teamId = String(teams.value[0].id)
    } catch (e: any) {
      if (!isCurrentRequest()) return
      if (e.code === 401 || e.code === 404) logout()
      notify(e.message)
    } finally {
      if (isCurrentRequest()) loading.value = false
    }
  }

  function syncPage(state: ListState, data: PageResult<unknown>) {
    state.page = data.page || state.page
    state.size = data.size || state.size
    state.total = data.total || 0
  }

  function beginListRequest(state: ListState) {
    const version = (listRequestVersions.get(state) || 0) + 1
    listRequestVersions.set(state, version)
    state.loading = true
    return { version, session: sessionRevision }
  }

  function isCurrentListRequest(state: ListState, requestState: { version: number; session: number }) {
    return listRequestVersions.get(state) === requestState.version && sessionRevision === requestState.session
  }

  function finishListRequest(state: ListState, requestState: { version: number; session: number }) {
    if (isCurrentListRequest(state, requestState)) state.loading = false
  }

  function listParams(state: ListState) {
    return {
      page: state.page,
      size: state.size,
      sort: state.sort,
      status: state.status,
      keyword: state.keyword,
      dateFrom: state.dateFrom,
      dateTo: state.dateTo
    }
  }

  async function loadSchedules(patch: Partial<ListState> = {}) {
    Object.assign(schedulePage, patch)
    const listRequest = beginListRequest(schedulePage)
    try {
      const data = await request<ScheduleListResult>(queryPath('/schedules', {
        ...listParams(schedulePage),
        viewMode: viewMode.value,
        urgencyLevel: urgencyLevelFilter.value,
        fatigueLevel: fatigueLevelFilter.value
      }))
      if (!isCurrentListRequest(schedulePage, listRequest)) return data.list || []
      schedules.value = data.list || []
      syncPage(schedulePage, data)
      sectionSummaries.value = data.sectionSummaries || []
      scheduleStatusCounts.value = data.statusCounts || {}
      return schedules.value
    } catch (e: any) {
      if (isCurrentListRequest(schedulePage, listRequest)) notify(e.message || '加载日程失败')
      return []
    } finally { finishListRequest(schedulePage, listRequest) }
  }

  function scheduleViewStorageKey(userId = profile.value?.id) {
    return userId ? `${SCHEDULE_VIEW_KEY_PREFIX}${userId}` : ''
  }

  function loadStoredScheduleView(userId: number) {
    const raw = localStorage.getItem(scheduleViewStorageKey(userId))
    if (raw && ['time', 'group', 'urgency', 'fatigue'].includes(raw)) viewMode.value = raw as ScheduleViewMode
  }

  async function setScheduleViewMode(mode: ScheduleViewMode) {
    viewMode.value = mode
    if (profile.value?.id) localStorage.setItem(scheduleViewStorageKey(profile.value.id), mode)
    schedulePage.sort = mode === 'group' ? 'manual' : mode === 'urgency' ? 'urgency_desc' : mode === 'fatigue' ? 'fatigue_desc' : 'time_asc'
    await loadSchedules({ page: 1 })
  }

  async function loadFatigueDaily(date?: string) {
    try {
      fatigueDaily.value = await request<FatigueDailySummary>(`/fatigue/daily${date ? `?date=${encodeURIComponent(date)}` : ''}`)
      return fatigueDaily.value
    } catch (e: any) { notify(e.message || '加载疲劳汇总失败'); return null }
  }

  async function loadFatigueProfile() {
    try {
      fatigueProfile.value = await request<FatigueProfile>('/fatigue/profile')
      return fatigueProfile.value
    } catch (e: any) { notify(e.message || '加载疲劳设置失败'); return null }
  }

  async function loadFatigueSurveyToday(date?: string) {
    try {
      fatigueSurveyToday.value = await request<FatigueSurveyToday>(`/fatigue/survey/today${date ? `?date=${encodeURIComponent(date)}` : ''}`)
      fatigueDaily.value = fatigueSurveyToday.value.daily
      fatigueProfile.value = fatigueSurveyToday.value.profile
      const comparison = fatigueSurveyToday.value.comparison
      fatigueSurveyComparison.value = comparison && 'predictedScore' in comparison ? comparison as FatigueSurveyComparison : null
      return fatigueSurveyToday.value
    } catch (e: any) { notify(e.message || '加载疲劳调查失败'); return null }
  }

  async function submitFatigueSurvey(payload: { localDate?: string; score: number; externalFactorLevel?: number; externalFactorTags?: string[] }) {
    try {
      const result = await request<{ daily: FatigueDailySummary; profile: FatigueProfile; comparison: FatigueSurveyComparison }>('/fatigue/surveys', { method: 'POST', body: JSON.stringify(payload) })
      fatigueDaily.value = result.daily
      fatigueProfile.value = result.profile
      fatigueSurveyComparison.value = result.comparison
      await loadFatigueSurveyToday(payload.localDate)
      notify('疲劳调查已保存')
      return true
    } catch (e: any) { notify(e.message || '保存疲劳调查失败'); return false }
  }

  async function updateFatiguePreferences(payload: Partial<Pick<FatigueProfile, 'fatigueTrackingEnabled' | 'fatigueAlertEnabled' | 'surveyEnabled' | 'surveyTime' | 'capacityLocked'>>) {
    try {
      fatigueProfile.value = await request<FatigueProfile>('/fatigue/preferences', { method: 'PUT', body: JSON.stringify(payload) })
      await loadFatigueDaily()
      notify('疲劳设置已保存')
      return true
    } catch (e: any) { notify(e.message || '保存疲劳设置失败'); return false }
  }

  async function resetFatigueProfile() {
    try {
      fatigueProfile.value = await request<FatigueProfile>('/fatigue/profile/reset', { method: 'POST' })
      await loadFatigueDaily()
      notify('个性化疲劳模型已重置')
      return true
    } catch (e: any) { notify(e.message || '重置疲劳模型失败'); return false }
  }

  async function loadFatigueHistory(dateFrom: string, dateTo: string) {
    try {
      fatigueHistory.value = await request<FatigueHistory>(queryPath('/fatigue/history', { dateFrom, dateTo }))
      fatigueProfile.value = fatigueHistory.value.profile
      return fatigueHistory.value
    } catch (e: any) { notify(e.message || '加载疲劳历史失败'); return null }
  }

  async function previewFatigue(payload: Record<string, unknown>) {
    try {
      return await request<FatiguePreview>('/fatigue/preview', { method: 'POST', body: JSON.stringify(payload) })
    } catch {
      return null
    }
  }

  async function snoozeFatigueSurvey(minutes = 30) {
    try {
      fatigueSurveyToday.value = await request<FatigueSurveyToday>('/fatigue/survey/snooze', { method: 'POST', body: JSON.stringify({ minutes }) })
      notify(`已稍后 ${minutes} 分钟提醒`)
      return true
    } catch (e: any) { notify(e.message || '暂缓提醒失败'); return false }
  }

  async function skipFatigueSurvey(localDate?: string) {
    try {
      fatigueSurveyToday.value = await request<FatigueSurveyToday>('/fatigue/survey/skip', { method: 'POST', body: JSON.stringify({ localDate }) })
      notify('今天已跳过疲劳调查')
      return true
    } catch (e: any) { notify(e.message || '跳过调查失败'); return false }
  }

  async function suppressFatigueAlertsToday(thresholdBand?: string) {
    try {
      await request('/fatigue/alerts/suppress-today', { method: 'POST', body: JSON.stringify({ thresholdBand }) })
      await loadFatigueProfile()
      notify('今天不再提醒当前及更低负荷档位')
      return true
    } catch (e: any) { notify(e.message || '设置提醒抑制失败'); return false }
  }

  async function deleteFatigueSurveyHistory() {
    try {
      const result = await request<{ deletedCount: number; profile: FatigueProfile }>('/fatigue/surveys/history', { method: 'DELETE' })
      fatigueProfile.value = result.profile
      fatigueHistory.value = null
      fatigueSurveyComparison.value = null
      await loadFatigueSurveyToday()
      notify(`已删除 ${result.deletedCount} 条疲劳调查记录`)
      return true
    } catch (e: any) { notify(e.message || '删除疲劳历史失败'); return false }
  }

  async function exportFatigueHistory(dateFrom: string, dateTo: string) {
    try {
      const blob = await apiDownload(queryPath('/fatigue/history/export', { dateFrom, dateTo }), token.value)
      const url = URL.createObjectURL(blob)
      const anchor = document.createElement('a')
      anchor.href = url
      anchor.download = `fatigue-history-${dateFrom}-${dateTo}.csv`
      document.body.appendChild(anchor)
      anchor.click()
      anchor.remove()
      URL.revokeObjectURL(url)
      notify('疲劳历史已导出')
      return true
    } catch (e: any) { notify(e.message || '导出疲劳历史失败'); return false }
  }

  async function loadTeams(patch: Partial<ListState> = {}) {
    Object.assign(teamPage, patch)
    const listRequest = beginListRequest(teamPage)
    try {
      const data = await request<PageResult<Team>>(queryPath('/teams', { page: teamPage.page, size: teamPage.size, sort: teamPage.sort }))
      if (!isCurrentListRequest(teamPage, listRequest)) return data.list || []
      teams.value = (data.list || []).map((team: any) => ({ ...team, myRole: team.myRole || team.role }))
      syncPage(teamPage, data)
      if (teams.value[0] && !taskForm.teamId) taskForm.teamId = String(teams.value[0].id)
      return teams.value
    } catch (e: any) {
      if (isCurrentListRequest(teamPage, listRequest)) notify(e.message || '加载团队失败')
      return []
    } finally { finishListRequest(teamPage, listRequest) }
  }

  async function loadAssignedTasks(patch: Partial<ListState> = {}) {
    Object.assign(assignedTaskPage, patch)
    const listRequest = beginListRequest(assignedTaskPage)
    try {
      const data = await request<PageResult<MyTask>>(queryPath('/team-tasks/my', listParams(assignedTaskPage)))
      if (!isCurrentListRequest(assignedTaskPage, listRequest)) return data.list || []
      myTasks.value = data.list || []
      syncPage(assignedTaskPage, data)
      return myTasks.value
    } catch (e: any) {
      if (isCurrentListRequest(assignedTaskPage, listRequest)) notify(e.message || '加载分配给我的任务失败')
      return []
    } finally { finishListRequest(assignedTaskPage, listRequest) }
  }

  async function loadTeamTaskGroups(teamId: number | string) {
    if (!teamId) return []
    try {
      const data = await request<{ list: TaskGroup[] }>(`/teams/${teamId}/task-groups`)
      const groups = (data.list || []).sort((a, b) => a.sortOrder - b.sortOrder)
      teamTaskGroups.value = { ...teamTaskGroups.value, [Number(teamId)]: groups }
      if (String(taskForm.teamId) === String(teamId) && !taskForm.groupId && groups[0]) taskForm.groupId = String(groups[0].id)
      return groups
    } catch (e: any) { notify(e.message || '加载团队分组失败'); return [] }
  }

  async function loadCreatedTasks(patch: Partial<ListState> = {}) {
    Object.assign(createdTaskPage, patch)
    const listRequest = beginListRequest(createdTaskPage)
    try {
      const data = await request<PageResult<MyTask>>(queryPath('/team-tasks/created', listParams(createdTaskPage)))
      if (!isCurrentListRequest(createdTaskPage, listRequest)) return data.list || []
      createdTasks.value = data.list || []
      syncPage(createdTaskPage, data)
      return createdTasks.value
    } catch (e: any) {
      if (isCurrentListRequest(createdTaskPage, listRequest)) notify(e.message || '加载我创建的任务失败')
      return []
    } finally { finishListRequest(createdTaskPage, listRequest) }
  }

  async function loadTeamTasks(teamId: number | string, patch: Partial<ListState> = {}) {
    Object.assign(teamTaskPage, patch, { teamId: String(teamId || '') })
    const listRequest = beginListRequest(teamTaskPage)
    if (!teamId) {
      teamTasks.value = []
      finishListRequest(teamTaskPage, listRequest)
      return []
    }
    try {
      const data = await request<PageResult<MyTask>>(queryPath(`/teams/${teamId}/tasks`, listParams(teamTaskPage)))
      if (!isCurrentListRequest(teamTaskPage, listRequest)) return data.list || []
      teamTasks.value = data.list || []
      syncPage(teamTaskPage, data)
      return teamTasks.value
    } catch (e: any) {
      if (isCurrentListRequest(teamTaskPage, listRequest)) notify(e.message || '加载团队任务失败')
      return []
    } finally { finishListRequest(teamTaskPage, listRequest) }
  }

  async function loadNotifications(patch: Partial<ListState> = {}, silent = false) {
    Object.assign(notificationPage, patch)
    const listRequest = beginListRequest(notificationPage)
    if (silent) notificationPage.loading = false
    try {
      const data = await request<PageResult<Notification>>(queryPath('/notifications', {
        page: notificationPage.page,
        size: notificationPage.size,
        sort: notificationPage.sort,
        is_read: notificationPage.isRead
      }))
      if (!isCurrentListRequest(notificationPage, listRequest)) return data.list || []
      notifications.value = data.list || []
      syncPage(notificationPage, data)
      showBrowserNotifications(notifications.value)
      return notifications.value
    } catch (e: any) {
      if (!silent && isCurrentListRequest(notificationPage, listRequest)) notify(e.message || '加载通知失败')
      return []
    } finally { finishListRequest(notificationPage, listRequest) }
  }

  async function loadUnreadCount(silent = false) {
    const requestVersion = ++unreadCountRequestVersion
    const requestSession = sessionRevision
    try {
      const data = await request<{ count: number }>('/notifications/unread-count')
      if (requestVersion !== unreadCountRequestVersion || requestSession !== sessionRevision) return today.value.unreadNotificationCount
      today.value = { ...today.value, unreadNotificationCount: Number(data.count || 0) }
      return Number(data.count || 0)
    } catch (e: any) {
      if (!silent && requestVersion === unreadCountRequestVersion && requestSession === sessionRevision) notify(e.message || '加载未读通知失败')
      return today.value.unreadNotificationCount
    }
  }

  async function pollNotifications() {
    if (!token.value) return
    const requestVersion = ++notificationPollVersion
    const requestSession = sessionRevision
    try {
      const latest = await request<PageResult<Notification>>('/notifications?page=1&size=20&sort=created_desc')
      if (requestVersion !== notificationPollVersion || requestSession !== sessionRevision) return
      showBrowserNotifications(latest.list || [])
      await Promise.all([loadNotifications({}, true), loadUnreadCount(true)])
    } catch {
      // Poll failures remain silent; foreground requests still surface API errors.
    }
  }

  async function fetchAllPages<T>(path: string, params: Record<string, unknown>) {
    const first = await request<PageResult<T>>(queryPath(path, { ...params, page: 1, size: 100 }))
    const pages = Math.ceil((first.total || 0) / 100)
    if (pages <= 1) return first.list || []
    const rest = await Promise.all(Array.from({ length: pages - 1 }, (_, index) =>
      request<PageResult<T>>(queryPath(path, { ...params, page: index + 2, size: 100 }))))
    return [...(first.list || []), ...rest.flatMap(result => result.list || [])]
  }

  async function loadCalendar(year: number, month: number) {
    const requestVersion = ++calendarRequestVersion
    const requestSession = sessionRevision
    const dateFrom = `${year}-${String(month).padStart(2, '0')}-01`
    const dateTo = `${year}-${String(month).padStart(2, '0')}-${String(new Date(year, month, 0).getDate()).padStart(2, '0')}`
    try {
      const [personal, assigned] = await Promise.all([
        fetchAllPages<Schedule>('/schedules', { dateFrom, dateTo, sort: 'time_asc' }),
        fetchAllPages<MyTask>('/team-tasks/my', { dateFrom, dateTo, sort: 'time_asc' })
      ])
      if (requestVersion !== calendarRequestVersion || requestSession !== sessionRevision) return
      calendarSchedules.value = personal
      calendarTasks.value = assigned
    } catch (e: any) {
      if (requestVersion === calendarRequestVersion && requestSession === sessionRevision) notify(e.message || '加载日历失败')
    }
  }

  async function createSchedule() {
    if (!scheduleForm.title.trim()) return notify('请输入标题')
    if (!scheduleForm.groupId) return notify('请先创建分组')
    try {
      await request('/schedules', { method: 'POST', body: JSON.stringify(toSchedulePayload(scheduleForm, profile.value?.timezone)) })
      Object.assign(scheduleForm, { title: '', description: '', timeType: 'point_event', startTime: '', endTime: '', deadlineTime: '', remindAt: '', urgencyLevel: 3, fatigueLevel: 3 })
      if (taskGroups.value[0]) scheduleForm.groupId = String(taskGroups.value[0].id)
      await loadAll(); scheduleModalOpen.value = false; notify('日程已创建')
    } catch (e: any) { notify(e.message) }
  }

  async function updateSchedule(id: number, form: ScheduleForm & { reminderChanged?: boolean }) {
    if (!form.title.trim()) return notify('请输入标题')
    if (!form.groupId) return notify('请选择模块')
    try {
      const payload = toSchedulePayload(form, profile.value?.timezone)
      if (form.reminderChanged && !form.remindAt) payload.remindAt = ''
      if (!form.reminderChanged) delete payload.remindAt
      await request(`/schedules/${id}`, { method: 'PUT', body: JSON.stringify(payload) })
      await loadAll(); notify('日程已更新')
      return true
    } catch (e: any) { notify(e.message); return false }
  }

  async function setScheduleStatus(item: Pick<Schedule, 'id'>, action: string) {
    try { await request(`/schedules/${item.id}/${action}`, { method: 'PUT' }); await loadAll(); return true } catch (e: any) { notify(e.message); return false }
  }
  async function deleteSchedule(id: number) {
    try { await request(`/schedules/${id}`, { method: 'DELETE' }); await loadAll(); notify('日程已删除'); return true } catch (e: any) { notify(e.message); return false }
  }
  async function moveScheduleGroup(id: number, groupId: number | string) {
    try { await request(`/schedules/${id}/move-group`, { method: 'PUT', body: JSON.stringify({ groupId: Number(groupId) }) }); await loadAll(); notify('日程已移动') } catch (e: any) { notify(e.message) }
  }
  async function sortSchedules(groupId: number, scheduleIds: number[]) {
    try { await request('/schedules/sort', { method: 'PUT', body: JSON.stringify({ groupId, scheduleIds }) }); await loadAll() } catch (e: any) { notify(e.message) }
  }
  async function sortCompletedSchedules(scheduleIds: number[]) {
    try { await request('/schedules/completed/sort', { method: 'PUT', body: JSON.stringify({ scheduleIds }) }); await loadSchedules(); notify('已完成日程顺序已保存') } catch (e: any) { notify(e.message) }
  }

  async function createTaskGroup() {
    if (!groupForm.name.trim()) return notify('请输入模块名称')
    try { await request('/task-groups', { method: 'POST', body: JSON.stringify({ scope: 'personal', name: groupForm.name.trim() }) }); groupForm.name = ''; await loadAll(); notify('模块已创建') } catch (e: any) { notify(e.message) }
  }
  async function createTaskGroupByName(name: string) {
    try {
      const group = await request<TaskGroup>('/task-groups', { method: 'POST', body: JSON.stringify({ scope: 'personal', name: name.trim() }) })
      await loadAll(); notify('模块已创建')
      return group
    } catch (e: any) { notify(e.message); return null }
  }
  async function updateTaskGroup(group: TaskGroup) {
    if (!String(group.name || '').trim()) return notify('请输入模块名称')
    try { await request(`/task-groups/${group.id}`, { method: 'PUT', body: JSON.stringify({ name: String(group.name).trim() }) }); await loadAll(); notify('模块已更新') } catch (e: any) { notify(e.message) }
  }
  async function deleteTaskGroup(group: TaskGroup) {
    try {
      await request(`/task-groups/${group.id}`, { method: 'DELETE' })
      if (String(scheduleForm.groupId) === String(group.id)) scheduleForm.groupId = ''
      await loadAll(); notify('模块已删除')
    } catch (e: any) { notify(e.message) }
  }
  async function sortTaskGroups(groupIds: number[]) {
    try { await request('/task-groups/sort', { method: 'PUT', body: JSON.stringify({ groupIds }) }); await loadAll() } catch (e: any) { notify(e.message) }
  }

  async function createTeam() {
    if (!teamForm.name.trim()) return notify('请输入团队名称')
    try { await request('/teams', { method: 'POST', body: JSON.stringify(teamForm) }); teamForm.name = ''; await loadAll(); notify('团队已创建') } catch (e: any) { notify(e.message) }
  }
  async function joinTeam() {
    if (!joinForm.inviteCode.trim()) return notify('请输入邀请码')
    try { await request('/teams/join', { method: 'POST', body: JSON.stringify({ inviteCode: joinForm.inviteCode.trim() }) }); joinForm.inviteCode = ''; await loadAll(); notify('已加入团队') } catch (e: any) { notify(e.message) }
  }

  async function createTask() {
    if (!taskForm.teamId || !taskForm.groupId || !taskForm.title.trim()) { notify('请选择团队、分组并填写标题'); return false }
    if (!taskForm.assigneeUserIds.length) { notify('请选择执行人'); return false }
    try {
      const created = await request<MyTask>('/team-tasks', { method: 'POST', body: JSON.stringify(toApiTimePayload({ ...taskForm, teamId: Number(taskForm.teamId), groupId: Number(taskForm.groupId) }, profile.value?.timezone)) })
      const teamId = taskForm.teamId
      Object.assign(taskForm, { teamId, groupId: String(teamTaskGroups.value[Number(teamId)]?.[0]?.id || ''), title: '', description: '', deadlineTime: '', startTime: '', remindAt: '', assigneeUserIds: [] })
      await loadAll(); await Promise.all([loadTeamTaskGroups(teamId), loadTeamTasks(teamId)])
      notify(created.status === 'pending_approval' || created.approvalStatus === 'pending' ? '团队任务已提交管理员审批' : '团队任务已创建')
      return true
    } catch (e: any) { notify(e.message); return false }
  }
  async function taskAction(task: MyTask, action: string) {
    try {
      await request(`/team-tasks/${task.id}/${action}`, { method: 'POST' })
      await loadAll()
      await Promise.all([loadTeamTaskGroups(task.teamId), loadTeamTasks(task.teamId)])
      return true
    } catch (e: any) { notify(e.message); return false }
  }
  async function moveTeamTaskGroup(task: MyTask, groupId: number | string) {
    try { await request(`/team-tasks/${task.id}/move-group`, { method: 'PUT', body: JSON.stringify({ groupId: Number(groupId) }) }); await loadAll(); await Promise.all([loadTeamTaskGroups(task.teamId), loadTeamTasks(task.teamId)]); notify('团队任务已移动') } catch (e: any) { notify(e.message) }
  }
  async function sortTeamTasks(teamId: number, groupId: number, taskIds: number[]) {
    try { await request(`/teams/${teamId}/tasks/sort`, { method: 'PUT', body: JSON.stringify({ groupId, taskIds }) }); await loadAll() } catch (e: any) { notify(e.message) }
  }
  async function sortCompletedTeamTasks(teamId: number, taskIds: number[]) {
    try { await request(`/teams/${teamId}/tasks/completed/sort`, { method: 'PUT', body: JSON.stringify({ taskIds }) }); await loadAll(); notify('已完成任务顺序已保存') } catch (e: any) { notify(e.message) }
  }
  async function createTeamTaskGroup(teamId: number, name: string) {
    if (!name.trim()) return null
    try {
      const group = await request<TaskGroup>(`/teams/${teamId}/task-groups`, { method: 'POST', body: JSON.stringify({ name: name.trim() }) })
      await loadTeamTaskGroups(teamId); notify('团队分组已创建'); return group
    } catch (e: any) { notify(e.message); return null }
  }
  async function updateTeamTaskGroup(teamId: number, group: TaskGroup) {
    try { await request(`/teams/${teamId}/task-groups/${group.id}`, { method: 'PUT', body: JSON.stringify({ name: group.name.trim() }) }); await Promise.all([loadTeamTaskGroups(teamId), loadAll()]); notify('团队分组已更新') } catch (e: any) { notify(e.message) }
  }
  async function deleteTeamTaskGroup(teamId: number, groupId: number) {
    try { await request(`/teams/${teamId}/task-groups/${groupId}`, { method: 'DELETE' }); await Promise.all([loadTeamTaskGroups(teamId), loadAll()]); notify('团队分组已删除') } catch (e: any) { notify(e.message) }
  }
  async function sortTeamTaskGroups(teamId: number, groupIds: number[]) {
    try { await request(`/teams/${teamId}/task-groups/sort`, { method: 'PUT', body: JSON.stringify({ groupIds }) }); await loadTeamTaskGroups(teamId) } catch (e: any) { notify(e.message) }
  }

  async function readAll() {
    try {
      await request('/notifications/read-all', { method: 'PUT' })
      await Promise.all([loadNotifications({}, true), loadUnreadCount(true)])
    } catch (e: any) { notify(e.message) }
  }
  async function readNotification(id: number) {
    try {
      await request(`/notifications/${id}/read`, { method: 'PUT' })
      await Promise.all([loadNotifications({}, true), loadUnreadCount(true)])
    } catch (e: any) { notify(e.message) }
  }
  function openNotificationDetail(n: Notification) { notificationDetail.value = n }
  function closeNotificationDetail() { notificationDetail.value = null }
  async function markNotificationRead(n: Notification) {
    try {
      await request(`/notifications/${n.id}/read`, { method: 'PUT' });
      n.isRead = true;
      await loadUnreadCount(true)
      if (notificationPage.isRead === 'false') await loadNotifications({}, true)
    } catch (e: any) { notify(e.message) }
  }

  async function requestBrowserNoticePermission() {
    if (typeof Notification === 'undefined') {
      browserNoticePermission.value = 'unsupported'
      notify('当前浏览器不支持系统通知')
      return
    }
    browserNoticePermission.value = await Notification.requestPermission()
    notificationPreferences.value.browserEnabled = browserNoticePermission.value === 'granted'
    if (token.value) await saveNotificationPreferences(true)
    notify(browserNoticePermission.value === 'granted' ? '浏览器通知已开启' : '浏览器通知未开启')
  }

  function showBrowserNotifications(items: Notification[]) {
    if (!notificationPreferences.value.browserEnabled || typeof Notification === 'undefined' || Notification.permission !== 'granted') return
    const shownIds = new Set(JSON.parse(localStorage.getItem(BROWSER_NOTICE_IDS_KEY) || '[]') as number[])
    items.filter(item => !item.isRead && !shownIds.has(item.id)).forEach(item => {
      const notice = new Notification(item.title, { body: item.content, tag: String(item.id) })
      notice.onclick = () => {
        window.focus()
        window.location.href = notificationUrl(item)
      }
      shownIds.add(item.id)
    })
    localStorage.setItem(BROWSER_NOTICE_IDS_KEY, JSON.stringify([...shownIds].slice(-200)))
  }

  function notificationUrl(item: Notification) {
    if (item.targetRoute) return item.targetRoute
    if (item.relatedType === 'schedule' && item.relatedId) return `/schedules/${item.relatedId}`
    if (item.relatedType === 'team_task' && item.relatedId) return `/tasks/${item.relatedId}`
    return '/notifications'
  }

  async function saveNotificationPreferences(silent = false) {
    try {
      notificationPreferences.value = await request<NotificationPreference>('/notifications/preferences', { method: 'PUT', body: JSON.stringify(notificationPreferences.value) })
      if (!silent) notify('通知设置已保存')
      return true
    } catch (e: any) { if (!silent) notify(e.message || '保存通知设置失败'); return false }
  }
  async function updateProfile() { try { await request('/user/profile', { method: 'PUT', body: JSON.stringify({ nickname: profileForm.nickname, avatarUrl: profileForm.avatarUrl }) }); await loadAll(); notify('资料已更新') } catch (e: any) { notify(e.message) } }
  async function changePassword() {
    if (!passwordForm.oldPassword) { clearPasswordSecrets(); notify('请输入旧密码'); return false }
    if (!isValidPassword(passwordForm.newPassword)) { clearPasswordSecrets(); notify('新密码至少 8 位，且包含字母和数字'); return false }
    if (passwordForm.newPassword !== passwordForm.confirmPassword) { clearPasswordSecrets(); notify('两次密码不一致'); return false }
    try {
      await request('/user/password', { method: 'PUT', body: JSON.stringify({ oldPassword: passwordForm.oldPassword, newPassword: passwordForm.newPassword }) })
      clearSession()
      notify('密码已修改，请重新登录')
      return true
    } catch (e: any) {
      notify(e.message)
      return false
    } finally {
      clearPasswordSecrets()
    }
  }
  async function updateTimezone() { try { await request('/user/timezone', { method: 'PUT', body: JSON.stringify({ timezone: timezoneForm.timezone }) }); await loadAll(); notify('时区已更新') } catch (e: any) { notify(e.message) } }

  async function setMemberRole(teamId: number, userId: number, role: string) { try { await request(`/teams/${teamId}/members/${userId}/role`, { method: 'PUT', body: JSON.stringify({ role }) }); await loadAll(); notify('角色已修改') } catch (e: any) { notify(e.message) } }
  async function removeMember(teamId: number, userId: number) { try { await request(`/teams/${teamId}/members/${userId}`, { method: 'DELETE' }); await loadAll(); notify('成员已移除') } catch (e: any) { notify(e.message) } }
  async function regenerateInviteCode(teamId: number) { try { const data = await request<{ inviteCode: string }>(`/teams/${teamId}/regenerate-invite-code`, { method: 'POST' }); await loadAll(); return data.inviteCode } catch (e: any) { notify(e.message); return '' } }

  function notify(message: string) {
    toast.value = message
    clearTimeout((notify as any)._timer)
    ;(notify as any)._timer = setTimeout(() => { toast.value = '' }, 2600)
  }

  function toggleAiRecord() {
    aiRecordEnabled.value = !aiRecordEnabled.value
    localStorage.setItem(AI_RECORD_KEY, String(aiRecordEnabled.value))
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

  return {
    token, refreshToken, theme, browserNoticePermission, toast, loading, registrationEnabled, registrationStatusLoading, registrationStatusChecked, registrationStatusError, scheduleModalOpen, profile, schedules, taskGroups, teamTaskGroups, teams, myTasks, createdTasks, teamTasks, notifications, notificationPreferences, today, upcomingSeven, viewMode, urgencyLevelFilter, fatigueLevelFilter, sectionSummaries, fatigueProfile, fatigueDaily, fatigueSurveyToday, fatigueSurveyComparison, fatigueHistory, notificationDetail, selectedDate,
    schedulePage, teamPage, assignedTaskPage, createdTaskPage, teamTaskPage, notificationPage, scheduleStatusCounts,
    loginForm, registerForm, scheduleForm, groupForm, teamForm, taskForm, joinForm, profileForm, passwordForm, timezoneForm,
    pendingScheduleCount, activeTaskCount, activeTeam, timelineItems, upcoming, timelineStats, calendarItems, monthDays, loggedIn, aiRecordEnabled,
    request, aiRequest, openScheduleModal, closeScheduleModal, login, loadRegistrationStatus, register, sendEmailCode, resetPassword, updateEmail, logout, loadAll, loadSchedules, setScheduleViewMode, loadFatigueDaily, loadFatigueProfile, loadFatigueSurveyToday, submitFatigueSurvey, updateFatiguePreferences, resetFatigueProfile, loadFatigueHistory, previewFatigue, snoozeFatigueSurvey, skipFatigueSurvey, suppressFatigueAlertsToday, deleteFatigueSurveyHistory, exportFatigueHistory, loadTeams, loadAssignedTasks, loadTeamTaskGroups, loadCreatedTasks, loadTeamTasks, loadNotifications, loadUnreadCount, pollNotifications, loadCalendar,
    createSchedule, updateSchedule, setScheduleStatus, deleteSchedule, moveScheduleGroup, sortSchedules, sortCompletedSchedules,
    createTaskGroup, createTaskGroupByName, updateTaskGroup, deleteTaskGroup, sortTaskGroups,
    createTeam, joinTeam, createTask, taskAction, moveTeamTaskGroup, sortTeamTasks, sortCompletedTeamTasks, createTeamTaskGroup, updateTeamTaskGroup, deleteTeamTaskGroup, sortTeamTaskGroups,
    readAll, readNotification, markNotificationRead, openNotificationDetail, closeNotificationDetail, requestBrowserNoticePermission, saveNotificationPreferences, updateProfile, changePassword, updateTimezone,
    setMemberRole, removeMember, regenerateInviteCode, notify, primaryTime, toggleAiRecord, toggleTheme
  }
})
