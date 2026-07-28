import { defineStore } from 'pinia'
import { ref, computed, reactive } from 'vue'
import { apiRequest } from '../api/http'
import type {
  UserProfile, Schedule, TaskGroup, Team, MyTask, Notification,
  TodayOverview, TimelineItem, CalendarDay, ScheduleForm, TaskForm,
  LoginForm, RegisterForm, PageResult
} from '../types'
import { toSchedulePayload, toApiTimePayload, normalizeTimelineItem, buildMonthDays, primaryTime } from '../utils/helpers'

const TOKEN_KEY = 'dayliane_token'
const AI_RECORD_KEY = 'dayliane_ai_record_enabled'
const THEME_KEY = 'dayliane_theme'
const BROWSER_NOTICE_IDS_KEY = 'dayliane_browser_notice_ids'

export const useAppStore = defineStore('app', () => {
  const token = ref(localStorage.getItem(TOKEN_KEY) || '')
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
  const notifications = ref<Notification[]>([])
  const today = ref<TodayOverview>({ personalSchedules: [], teamTasks: [], unreadNotificationCount: 0, groups: [] })
  const notificationDetail = ref<Notification | null>(null)
  const selectedDate = ref('')

  const loginForm = reactive<LoginForm>({ phone: '13800138000', password: 'Abc12345' })
  const registerForm = reactive<RegisterForm>({ phone: '', password: '', confirmPassword: '', nickname: '' })
  const scheduleForm = reactive<ScheduleForm>({ title: '', groupId: '', groupName: '', timeType: 'point_event', startTime: '', endTime: '', deadlineTime: '' })
  const groupForm = reactive({ name: '' })
  const teamForm = reactive({ name: '' })
  const taskForm = reactive<TaskForm>({ teamId: '', groupId: '', title: '', deadlineTime: '', startTime: '', assigneeUserIds: [] })
  const joinForm = reactive({ inviteCode: '' })
  const profileForm = reactive({ nickname: '', timezone: '' })
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
    const todayKey = new Date().toISOString().slice(0, 10)
    return {
      today: timelineItems.value.filter(i => i.sortAt.startsWith(todayKey)).length,
      overdue: timelineItems.value.filter(i => new Date(i.sortAt).getTime() < Date.now()).length,
      upcoming: timelineItems.value.filter(i => new Date(i.sortAt).getTime() >= Date.now()).length
    }
  })
  const calendarItems = computed(() => [
    ...schedules.value.map(item => ({ ...item, sourceType: 'schedule' })),
    ...myTasks.value.map(item => ({ ...item, sourceType: 'team_task' }))
  ])
  const monthDays = computed<CalendarDay[]>(() => buildMonthDays(calendarItems.value))
  const loggedIn = computed(() => !!token.value)
  const aiRecordEnabled = ref(localStorage.getItem(AI_RECORD_KEY) !== 'false')

  async function request<T = any>(path: string, options: RequestInit = {}) {
    return apiRequest<T>(path, options, token.value)
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
      const data = await request<{ accessToken: string }>('/auth/login', { method: 'POST', body: JSON.stringify(loginForm) })
      token.value = data.accessToken
      localStorage.setItem(TOKEN_KEY, data.accessToken)
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
      const data = await request<{ accessToken: string }>('/auth/register', {
        method: 'POST', body: JSON.stringify({ phone: registerForm.phone, password: registerForm.password, nickname: registerForm.nickname || undefined })
      })
      token.value = data.accessToken
      localStorage.setItem(TOKEN_KEY, data.accessToken)
      await loadAll()
      notify('注册成功')
      return true
    } catch (e: any) { notify(e.message); return false } finally { loading.value = false }
  }

  function logout() {
    token.value = ''; localStorage.removeItem(TOKEN_KEY); profile.value = null
    schedules.value = []; taskGroups.value = []; teamTaskGroups.value = {}; teams.value = []; myTasks.value = []; notifications.value = []
    today.value = { personalSchedules: [], teamTasks: [], unreadNotificationCount: 0, groups: [] }
  }

  async function loadAll() {
    if (!token.value) return
    loading.value = true
    try {
      const [me, schedulePage, groupPage, teamPage, taskPage, noticePage, todayData] = await Promise.all([
        request<UserProfile>('/user/profile'), request<PageResult<Schedule>>('/schedules?size=80'),
        request<PageResult<TaskGroup>>('/task-groups?scope=personal'), request<PageResult<Team>>('/teams?size=80'),
        request<PageResult<MyTask>>('/team-tasks/my?size=80'), request<PageResult<Notification>>('/notifications?size=80'), request<TodayOverview>('/home/today')
      ])
      profile.value = me; profileForm.nickname = me.nickname; profileForm.timezone = me.timezone; timezoneForm.timezone = me.timezone
      schedules.value = schedulePage.list || []
      taskGroups.value = (groupPage.list || []).sort((a, b) => a.sortOrder - b.sortOrder)
      if (!scheduleForm.groupId && taskGroups.value[0]) scheduleForm.groupId = String(taskGroups.value[0].id)
      teams.value = (teamPage.list || []).map((team: any) => ({ ...team, myRole: team.myRole || team.role }))
      myTasks.value = taskPage.list || []
      notifications.value = noticePage.list || []; today.value = todayData
      showBrowserNotifications(notifications.value)
      if (teams.value[0] && !taskForm.teamId) taskForm.teamId = String(teams.value[0].id)
    } catch (e: any) {
      if (e.code === 401 || e.code === 404) logout()
      notify(e.message)
    } finally { loading.value = false }
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

  async function createSchedule() {
    if (!scheduleForm.title.trim()) return notify('请输入标题')
    if (!scheduleForm.groupId) return notify('请先创建分组')
    try {
      await request('/schedules', { method: 'POST', body: JSON.stringify(toSchedulePayload(scheduleForm)) })
      Object.assign(scheduleForm, { title: '', timeType: 'point_event', startTime: '', endTime: '', deadlineTime: '' })
      if (taskGroups.value[0]) scheduleForm.groupId = String(taskGroups.value[0].id)
      await loadAll(); scheduleModalOpen.value = false; notify('日程已创建')
    } catch (e: any) { notify(e.message) }
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
    if (!taskForm.teamId || !taskForm.groupId || !taskForm.title.trim()) return notify('请选择团队、分组并填写标题')
    if (!taskForm.assigneeUserIds.length) return notify('请选择执行人')
    try {
      await request('/team-tasks', { method: 'POST', body: JSON.stringify(toApiTimePayload({ ...taskForm, teamId: Number(taskForm.teamId), groupId: Number(taskForm.groupId) })) })
      const teamId = taskForm.teamId
      Object.assign(taskForm, { teamId, groupId: String(teamTaskGroups.value[Number(teamId)]?.[0]?.id || ''), title: '', deadlineTime: '', startTime: '', assigneeUserIds: [] })
      await loadAll(); await loadTeamTaskGroups(teamId); notify('团队任务已创建')
    } catch (e: any) { notify(e.message) }
  }
  async function taskAction(task: MyTask, action: string) {
    try { await request(`/team-tasks/${task.id}/${action}`, { method: 'POST' }); await loadAll(); await loadTeamTaskGroups(task.teamId) } catch (e: any) { notify(e.message) }
  }
  async function moveTeamTaskGroup(task: MyTask, groupId: number | string) {
    try { await request(`/team-tasks/${task.id}/move-group`, { method: 'PUT', body: JSON.stringify({ groupId: Number(groupId) }) }); await loadAll(); await loadTeamTaskGroups(task.teamId); notify('团队任务已移动') } catch (e: any) { notify(e.message) }
  }
  async function sortTeamTasks(teamId: number, groupId: number, taskIds: number[]) {
    try { await request(`/teams/${teamId}/tasks/sort`, { method: 'PUT', body: JSON.stringify({ groupId, taskIds }) }); await loadAll() } catch (e: any) { notify(e.message) }
  }

  async function readAll() { try { await request('/notifications/read-all', { method: 'PUT' }); await loadAll() } catch (e: any) { notify(e.message) } }
  async function readNotification(id: number) { try { await request(`/notifications/${id}/read`, { method: 'PUT' }); await loadAll() } catch (e: any) { notify(e.message) } }
  function openNotificationDetail(n: Notification) { notificationDetail.value = n }
  function closeNotificationDetail() { notificationDetail.value = null }

  async function requestBrowserNoticePermission() {
    if (typeof Notification === 'undefined') {
      browserNoticePermission.value = 'unsupported'
      notify('当前浏览器不支持系统通知')
      return
    }
    browserNoticePermission.value = await Notification.requestPermission()
    notify(browserNoticePermission.value === 'granted' ? '浏览器通知已开启' : '浏览器通知未开启')
  }

  function showBrowserNotifications(items: Notification[]) {
    if (typeof Notification === 'undefined' || Notification.permission !== 'granted' || document.visibilityState === 'visible') return
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

  async function updateProfile() { try { await request('/user/profile', { method: 'PUT', body: JSON.stringify({ nickname: profileForm.nickname }) }); await loadAll(); notify('资料已更新') } catch (e: any) { notify(e.message) } }
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
    token, theme, browserNoticePermission, toast, loading, scheduleModalOpen, profile, schedules, taskGroups, teamTaskGroups, teams, myTasks, notifications, today, notificationDetail, selectedDate,
    loginForm, registerForm, scheduleForm, groupForm, teamForm, taskForm, joinForm, profileForm, passwordForm, timezoneForm,
    pendingScheduleCount, activeTaskCount, activeTeam, timelineItems, upcoming, timelineStats, calendarItems, monthDays, loggedIn, aiRecordEnabled,
    request, aiRequest, openScheduleModal, closeScheduleModal, login, register, logout, loadAll, loadTeamTaskGroups,
    createSchedule, setScheduleStatus, deleteSchedule, moveScheduleGroup, sortSchedules,
    createTaskGroup, createTaskGroupByName, updateTaskGroup, deleteTaskGroup, sortTaskGroups,
    createTeam, joinTeam, createTask, taskAction, moveTeamTaskGroup, sortTeamTasks,
    readAll, readNotification, openNotificationDetail, closeNotificationDetail, requestBrowserNoticePermission, updateProfile, changePassword, updateTimezone,
    setMemberRole, removeMember, regenerateInviteCode, notify, primaryTime, toggleAiRecord, toggleTheme
  }
})
