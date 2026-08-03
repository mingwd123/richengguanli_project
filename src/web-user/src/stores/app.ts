import { defineStore } from 'pinia'
import { ref, computed, reactive } from 'vue'
import { apiRequest } from '../api/http'
import type {
  UserProfile, Schedule, TaskGroup, Team, MyTask, Notification,
  TodayOverview, TimelineItem, CalendarDay, ScheduleForm, TaskForm,
  LoginForm, RegisterForm, PageResult, UpcomingOverview, NotificationPreference
} from '../types'
import { toSchedulePayload, toApiTimePayload, normalizeTimelineItem, buildMonthDays, primaryTime, dateKeyInTimezone, setDisplayTimezone } from '../utils/helpers'

const TOKEN_KEY = 'dayliane_token'
const REFRESH_TOKEN_KEY = 'dayliane_refresh_token'
const AI_RECORD_KEY = 'dayliane_ai_record_enabled'
const THEME_KEY = 'dayliane_theme'
const BROWSER_NOTICE_IDS_KEY = 'dayliane_browser_notice_ids'

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
  const notificationDetail = ref<Notification | null>(null)
  const notificationPreferences = ref<NotificationPreference>({ browserEnabled: false, taskAssignedEnabled: true, taskStatusEnabled: true, reminderEnabled: true, reminderPresetMinutes: [15, 30, 60, 1440] })
  const selectedDate = ref('')

  const loginForm = reactive<LoginForm>({ phone: '13800138000', password: 'Abc12345' })
  const registerForm = reactive<RegisterForm>({ phone: '', password: '', confirmPassword: '', nickname: '' })
  const scheduleForm = reactive<ScheduleForm>({ title: '', description: '', groupId: '', groupName: '', timeType: 'point_event', startTime: '', endTime: '', deadlineTime: '', remindAt: '' })
  const groupForm = reactive({ name: '' })
  const teamForm = reactive({ name: '' })
  const taskForm = reactive<TaskForm>({ teamId: '', groupId: '', title: '', description: '', deadlineTime: '', startTime: '', remindAt: '', assigneeUserIds: [] })
  const joinForm = reactive({ inviteCode: '' })
  const profileForm = reactive({ nickname: '', avatarUrl: '', timezone: '' })
  const passwordForm = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })
  const timezoneForm = reactive({ timezone: '' })

  const pendingScheduleCount = computed(() => schedules.value.filter(s => s.status === 'pending').length)
  const activeTaskCount = computed(() => myTasks.value.filter(t => t.status === 'active').length)
  const activeTeam = computed(() => teams.value[0] || null)
  const timelineItems = computed<TimelineItem[]>(() =>
    [...schedules.value.map(i => normalizeTimelineItem(i, 'schedule')), ...myTasks.value.map(i => normalizeTimelineItem(i, 'team_task'))]
      .filter(i => ['pending', 'active', 'accepted'].includes(i.status) || ['pending', 'accepted'].includes(i.assignStatus || ''))
      .filter(i => i.sortAt)
      .sort((a, b) => new Date(a.sortAt).getTime() - new Date(b.sortAt).getTime()))
  const upcoming = computed(() => timelineItems.value.slice(0, 6))
  const timelineStats = computed(() => {
    const todayKey = dateKeyInTimezone()
    return {
      today: timelineItems.value.filter(i => dateKeyInTimezone(i.sortAt) === todayKey).length,
      overdue: timelineItems.value.filter(i => new Date(i.sortAt).getTime() < Date.now()).length,
      upcoming: timelineItems.value.filter(i => new Date(i.sortAt).getTime() >= Date.now()).length
    }
  })
  const calendarItems = computed(() => [
    ...calendarSchedules.value.map(item => ({ ...item, sourceType: 'schedule' })),
    ...calendarTasks.value.map(item => ({ ...item, sourceType: 'team_task' }))
  ])
  const monthDays = computed<CalendarDay[]>(() => buildMonthDays(calendarItems.value, profile.value?.timezone))
  const loggedIn = computed(() => !!token.value)
  const aiRecordEnabled = ref(localStorage.getItem(AI_RECORD_KEY) !== 'false')

  let refreshPromise: Promise<boolean> | null = null

  function persistTokens(access: string, refresh: string) {
    token.value = access
    refreshToken.value = refresh
    localStorage.setItem(TOKEN_KEY, access)
    localStorage.setItem(REFRESH_TOKEN_KEY, refresh)
  }

  function clearSession() {
    token.value = ''
    refreshToken.value = ''
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(REFRESH_TOKEN_KEY)
    profile.value = null
    schedules.value = []; taskGroups.value = []; teamTaskGroups.value = {}; teams.value = []; myTasks.value = []; createdTasks.value = []; teamTasks.value = []; notifications.value = []
    notificationPreferences.value = { browserEnabled: false, taskAssignedEnabled: true, taskStatusEnabled: true, reminderEnabled: true, reminderPresetMinutes: [15, 30, 60, 1440] }
    calendarSchedules.value = []; calendarTasks.value = []
    ;[schedulePage, teamPage, assignedTaskPage, createdTaskPage, teamTaskPage, notificationPage].forEach(state => {
      state.page = 1
      state.total = 0
      state.loading = false
    })
    today.value = { personalSchedules: [], teamTasks: [], unreadNotificationCount: 0, groups: [] }
    upcomingSeven.value = { timezone: '', dateFrom: '', dateTo: '', personalSchedules: [], teamTasks: [], list: [] }
  }

  async function refreshSession() {
    if (!refreshToken.value) return false
    if (!refreshPromise) {
      refreshPromise = apiRequest<{ accessToken: string; refreshToken: string }>('/auth/refresh-token', {
        method: 'POST', body: JSON.stringify({ refreshToken: refreshToken.value })
      }).then(data => {
        persistTokens(data.accessToken, data.refreshToken)
        return true
      }).catch(() => {
        clearSession()
        return false
      }).finally(() => { refreshPromise = null })
    }
    return refreshPromise
  }

  async function request<T = any>(path: string, options: RequestInit = {}) {
    const attemptedToken = token.value
    try {
      return await apiRequest<T>(path, options, attemptedToken)
    } catch (error: any) {
      const canRefresh = error.code === 401 && !path.startsWith('/auth/') && !!refreshToken.value
      if (!canRefresh) throw error
      if (attemptedToken !== token.value) return apiRequest<T>(path, options, token.value)
      if (!(await refreshSession())) throw error
      return apiRequest<T>(path, options, token.value)
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
    loading.value = true
    try {
      const data = await request<{ accessToken: string; refreshToken: string }>('/auth/login', { method: 'POST', body: JSON.stringify(loginForm) })
      persistTokens(data.accessToken, data.refreshToken)
      await loadAll()
      notify('登录成功')
      return true
    } catch (e: any) { notify(e.message); return false } finally { loading.value = false }
  }

  async function register() {
    if (!/^1\d{10}$/.test(registerForm.phone)) { notify('请输入正确的手机号'); return false }
    if (registerForm.password.length < 8) { notify('密码至少 8 位'); return false }
    if (registerForm.password !== registerForm.confirmPassword) { notify('两次密码不一致'); return false }
    loading.value = true
    try {
      const data = await request<{ accessToken: string; refreshToken: string }>('/auth/register', {
        method: 'POST', body: JSON.stringify({ phone: registerForm.phone, password: registerForm.password, nickname: registerForm.nickname || undefined, timezone: Intl.DateTimeFormat().resolvedOptions().timeZone })
      })
      persistTokens(data.accessToken, data.refreshToken)
      await loadAll()
      notify('注册成功')
      return true
    } catch (e: any) { notify(e.message); return false } finally { loading.value = false }
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
    loading.value = true
    try {
      const [me, groupPage, todayData, upcomingData, preferences] = await Promise.all([
        request<UserProfile>('/user/profile'), request<PageResult<TaskGroup>>('/task-groups?scope=personal'),
        request<TodayOverview>('/home/today'), request<UpcomingOverview>('/home/upcoming'),
        request<NotificationPreference>('/notifications/preferences')
      ])
      profile.value = me; setDisplayTimezone(me.timezone); profileForm.nickname = me.nickname; profileForm.avatarUrl = me.avatarUrl || ''; profileForm.timezone = me.timezone; timezoneForm.timezone = me.timezone
      notificationPreferences.value = preferences
      taskGroups.value = (groupPage.list || []).sort((a, b) => a.sortOrder - b.sortOrder)
      if (!scheduleForm.groupId && taskGroups.value[0]) scheduleForm.groupId = String(taskGroups.value[0].id)
      today.value = todayData; upcomingSeven.value = upcomingData
      await Promise.all([loadSchedules(), loadTeams(), loadAssignedTasks(), loadCreatedTasks(), loadNotifications()])
      if (teams.value[0] && !taskForm.teamId) taskForm.teamId = String(teams.value[0].id)
    } catch (e: any) {
      if (e.code === 401 || e.code === 404) logout()
      notify(e.message)
    } finally { loading.value = false }
  }

  function syncPage(state: ListState, data: PageResult<unknown>) {
    state.page = data.page || state.page
    state.size = data.size || state.size
    state.total = data.total || 0
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
    schedulePage.loading = true
    try {
      const data = await request<PageResult<Schedule>>(queryPath('/schedules', listParams(schedulePage)))
      schedules.value = data.list || []
      syncPage(schedulePage, data)
      return schedules.value
    } catch (e: any) { notify(e.message || '加载日程失败'); return [] } finally { schedulePage.loading = false }
  }

  async function loadTeams(patch: Partial<ListState> = {}) {
    Object.assign(teamPage, patch)
    teamPage.loading = true
    try {
      const data = await request<PageResult<Team>>(queryPath('/teams', { page: teamPage.page, size: teamPage.size, sort: teamPage.sort }))
      teams.value = (data.list || []).map((team: any) => ({ ...team, myRole: team.myRole || team.role }))
      syncPage(teamPage, data)
      if (teams.value[0] && !taskForm.teamId) taskForm.teamId = String(teams.value[0].id)
      return teams.value
    } catch (e: any) { notify(e.message || '加载团队失败'); return [] } finally { teamPage.loading = false }
  }

  async function loadAssignedTasks(patch: Partial<ListState> = {}) {
    Object.assign(assignedTaskPage, patch)
    assignedTaskPage.loading = true
    try {
      const data = await request<PageResult<MyTask>>(queryPath('/team-tasks/my', listParams(assignedTaskPage)))
      myTasks.value = data.list || []
      syncPage(assignedTaskPage, data)
      return myTasks.value
    } catch (e: any) { notify(e.message || '加载分配给我的任务失败'); return [] } finally { assignedTaskPage.loading = false }
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
    createdTaskPage.loading = true
    try {
      const data = await request<PageResult<MyTask>>(queryPath('/team-tasks/created', listParams(createdTaskPage)))
      createdTasks.value = data.list || []
      syncPage(createdTaskPage, data)
      return createdTasks.value
    } catch (e: any) { notify(e.message || '加载我创建的任务失败'); return [] } finally { createdTaskPage.loading = false }
  }

  async function loadTeamTasks(teamId: number | string, patch: Partial<ListState> = {}) {
    Object.assign(teamTaskPage, patch, { teamId: String(teamId || '') })
    if (!teamId) { teamTasks.value = []; return [] }
    teamTaskPage.loading = true
    try {
      const data = await request<PageResult<MyTask>>(queryPath(`/teams/${teamId}/tasks`, listParams(teamTaskPage)))
      teamTasks.value = data.list || []
      syncPage(teamTaskPage, data)
      return teamTasks.value
    } catch (e: any) { notify(e.message || '加载团队任务失败'); return [] } finally { teamTaskPage.loading = false }
  }

  async function loadNotifications(patch: Partial<ListState> = {}, silent = false) {
    Object.assign(notificationPage, patch)
    notificationPage.loading = !silent
    try {
      const data = await request<PageResult<Notification>>(queryPath('/notifications', {
        page: notificationPage.page,
        size: notificationPage.size,
        sort: notificationPage.sort,
        is_read: notificationPage.isRead
      }))
      notifications.value = data.list || []
      syncPage(notificationPage, data)
      showBrowserNotifications(notifications.value)
      return notifications.value
    } catch (e: any) {
      if (!silent) notify(e.message || '加载通知失败')
      return []
    } finally { notificationPage.loading = false }
  }

  async function loadUnreadCount(silent = false) {
    try {
      const data = await request<{ count: number }>('/notifications/unread-count')
      today.value = { ...today.value, unreadNotificationCount: Number(data.count || 0) }
      return Number(data.count || 0)
    } catch (e: any) {
      if (!silent) notify(e.message || '加载未读通知失败')
      return today.value.unreadNotificationCount
    }
  }

  async function pollNotifications() {
    if (!token.value) return
    try {
      const latest = await request<PageResult<Notification>>('/notifications?page=1&size=20&sort=created_desc')
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
    const dateFrom = `${year}-${String(month).padStart(2, '0')}-01`
    const dateTo = `${year}-${String(month).padStart(2, '0')}-${String(new Date(year, month, 0).getDate()).padStart(2, '0')}`
    try {
      const [personal, assigned] = await Promise.all([
        fetchAllPages<Schedule>('/schedules', { dateFrom, dateTo, sort: 'time_asc' }),
        fetchAllPages<MyTask>('/team-tasks/my', { dateFrom, dateTo, sort: 'time_asc' })
      ])
      calendarSchedules.value = personal
      calendarTasks.value = assigned
    } catch (e: any) { notify(e.message || '加载日历失败') }
  }

  async function createSchedule() {
    if (!scheduleForm.title.trim()) return notify('请输入标题')
    if (!scheduleForm.groupId) return notify('请先创建分组')
    try {
      await request('/schedules', { method: 'POST', body: JSON.stringify(toSchedulePayload(scheduleForm)) })
      Object.assign(scheduleForm, { title: '', description: '', timeType: 'point_event', startTime: '', endTime: '', deadlineTime: '', remindAt: '' })
      if (taskGroups.value[0]) scheduleForm.groupId = String(taskGroups.value[0].id)
      await loadAll(); scheduleModalOpen.value = false; notify('日程已创建')
    } catch (e: any) { notify(e.message) }
  }

  async function updateSchedule(id: number, form: ScheduleForm & { reminderChanged?: boolean }) {
    if (!form.title.trim()) return notify('请输入标题')
    if (!form.groupId) return notify('请选择模块')
    try {
      const payload = toSchedulePayload(form)
      if (form.reminderChanged) payload.remindAt = form.remindAt ? new Date(form.remindAt).toISOString() : ''
      else delete payload.remindAt
      await request(`/schedules/${id}`, { method: 'PUT', body: JSON.stringify(payload) })
      await loadAll(); notify('日程已更新')
      return true
    } catch (e: any) { notify(e.message); return false }
  }

  async function setScheduleStatus(item: Schedule, action: string) {
    try { await request(`/schedules/${item.id}/${action}`, { method: 'PUT' }); await loadAll() } catch (e: any) { notify(e.message) }
  }
  async function deleteSchedule(id: number) {
    try { await request(`/schedules/${id}`, { method: 'DELETE' }); await loadAll(); notify('日程已删除') } catch (e: any) { notify(e.message) }
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
      await request('/team-tasks', { method: 'POST', body: JSON.stringify(toApiTimePayload({ ...taskForm, teamId: Number(taskForm.teamId), groupId: Number(taskForm.groupId) })) })
      const teamId = taskForm.teamId
      Object.assign(taskForm, { teamId, groupId: String(teamTaskGroups.value[Number(teamId)]?.[0]?.id || ''), title: '', description: '', deadlineTime: '', startTime: '', remindAt: '', assigneeUserIds: [] })
      await loadAll(); await Promise.all([loadTeamTaskGroups(teamId), loadTeamTasks(teamId)]); notify('团队任务已创建')
      return true
    } catch (e: any) { notify(e.message); return false }
  }
  async function taskAction(task: MyTask, action: string) {
    try { await request(`/team-tasks/${task.id}/${action}`, { method: 'POST' }); await loadAll(); await Promise.all([loadTeamTaskGroups(task.teamId), loadTeamTasks(task.teamId)]) } catch (e: any) { notify(e.message) }
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
    if (passwordForm.newPassword.length < 8) return notify('新密码至少 8 位')
    if (passwordForm.newPassword !== passwordForm.confirmPassword) return notify('两次密码不一致')
    try { await request('/user/password', { method: 'PUT', body: JSON.stringify({ oldPassword: passwordForm.oldPassword, newPassword: passwordForm.newPassword }) }); passwordForm.oldPassword = ''; passwordForm.newPassword = ''; passwordForm.confirmPassword = ''; notify('密码已修改') } catch (e: any) { notify(e.message) }
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
    token, refreshToken, theme, browserNoticePermission, toast, loading, scheduleModalOpen, profile, schedules, taskGroups, teamTaskGroups, teams, myTasks, createdTasks, teamTasks, notifications, notificationPreferences, today, upcomingSeven, notificationDetail, selectedDate,
    schedulePage, teamPage, assignedTaskPage, createdTaskPage, teamTaskPage, notificationPage,
    loginForm, registerForm, scheduleForm, groupForm, teamForm, taskForm, joinForm, profileForm, passwordForm, timezoneForm,
    pendingScheduleCount, activeTaskCount, activeTeam, timelineItems, upcoming, timelineStats, calendarItems, monthDays, loggedIn, aiRecordEnabled,
    request, aiRequest, openScheduleModal, closeScheduleModal, login, register, logout, loadAll, loadSchedules, loadTeams, loadAssignedTasks, loadTeamTaskGroups, loadCreatedTasks, loadTeamTasks, loadNotifications, loadUnreadCount, pollNotifications, loadCalendar,
    createSchedule, updateSchedule, setScheduleStatus, deleteSchedule, moveScheduleGroup, sortSchedules, sortCompletedSchedules,
    createTaskGroup, createTaskGroupByName, updateTaskGroup, deleteTaskGroup, sortTaskGroups,
    createTeam, joinTeam, createTask, taskAction, moveTeamTaskGroup, sortTeamTasks, sortCompletedTeamTasks, createTeamTaskGroup, updateTeamTaskGroup, deleteTeamTaskGroup, sortTeamTaskGroups,
    readAll, readNotification, markNotificationRead, openNotificationDetail, closeNotificationDetail, requestBrowserNoticePermission, saveNotificationPreferences, updateProfile, changePassword, updateTimezone,
    setMemberRole, removeMember, regenerateInviteCode, notify, primaryTime, toggleAiRecord, toggleTheme
  }
})
