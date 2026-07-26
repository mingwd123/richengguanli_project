import { defineStore } from 'pinia'
import { ref, computed, reactive } from 'vue'
import { apiRequest } from '../api/http'
import type {
  UserProfile, Schedule, TaskGroup, Team, MyTask, Notification,
  TodayOverview, TimelineItem, CalendarDay, ScheduleForm,
  LoginForm, RegisterForm, PageResult
} from '../types'
import { toSchedulePayload, toApiTimePayload, normalizeTimelineItem, buildMonthDays, primaryTime } from '../utils/helpers'
import { labels } from '../utils/labels'

const TOKEN_KEY = 'dayliane_token'

export const useAppStore = defineStore('app', () => {
  /* =========== 状态 =========== */
  const token = ref(localStorage.getItem(TOKEN_KEY) || '')
  const toast = ref('')
  const loading = ref(false)
  const scheduleModalOpen = ref(false)
  const profile = ref<UserProfile | null>(null)
  const schedules = ref<Schedule[]>([])
  const taskGroups = ref<TaskGroup[]>([])
  const teams = ref<Team[]>([])
  const myTasks = ref<MyTask[]>([])
  const notifications = ref<Notification[]>([])
  const today = ref<TodayOverview>({ personalSchedules: [], teamTasks: [], unreadNotificationCount: 0, groups: [] })
  const notificationDetail = ref<Notification | null>(null)
  /* 日历选中日期 */
  const selectedDate = ref('')

  /* =========== 表单状态 =========== */
  const loginForm = reactive<LoginForm>({ phone: '13800138000', password: 'Abc12345' })
  const registerForm = reactive<RegisterForm>({ phone: '', password: '', confirmPassword: '', nickname: '' })
  const scheduleForm = reactive<ScheduleForm>({ title: '', groupId: '', groupName: '', timeType: 'point_event', startTime: '', endTime: '', deadlineTime: '' })
  const groupForm = reactive({ name: '' })
  const teamForm = reactive({ name: '' })
  const taskForm = reactive({ teamId: '', title: '', deadlineTime: '', startTime: '', assigneeUserIds: '' })
  /* 加入团队表单 */
  const joinForm = reactive({ inviteCode: '' })
  /* 修改资料表单 */
  const profileForm = reactive({ nickname: '', timezone: '' })
  /* 修改密码表单 */
  const passwordForm = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })
  /* 时区表单 */
  const timezoneForm = reactive({ timezone: '' })

  /* =========== 计算属性 =========== */
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
  const monthDays = computed<CalendarDay[]>(() => buildMonthDays(schedules.value))
  const loggedIn = computed(() => !!token.value)

  /* =========== 方法 =========== */
  async function request<T = any>(path: string, options: RequestInit = {}) {
    return apiRequest<T>(path, options, token.value)
  }

  function openScheduleModal() { scheduleModalOpen.value = true }
  function closeScheduleModal() { scheduleModalOpen.value = false }

  async function login() {
    loading.value = true
    try {
      const data = await request<{ accessToken: string; refreshToken: string }>('/auth/login', {
        method: 'POST', body: JSON.stringify(loginForm)
      })
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
      const data = await request<{ accessToken: string; refreshToken: string }>('/auth/register', {
        method: 'POST', body: JSON.stringify({
          phone: registerForm.phone,
          password: registerForm.password,
          nickname: registerForm.nickname || undefined
        })
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
    schedules.value = []; taskGroups.value = []; teams.value = []; myTasks.value = []; notifications.value = []
    today.value = { personalSchedules: [], teamTasks: [], unreadNotificationCount: 0, groups: [] }
  }

  async function loadAll() {
    if (!token.value) return
    loading.value = true
    try {
      const [me, schedulePage, groupPage, teamPage, taskPage, noticePage, todayData] = await Promise.all([
        request<UserProfile>('/user/profile'),
        request<PageResult<Schedule>>('/schedules?size=80'),
        request<PageResult<TaskGroup>>('/task-groups?scope=personal'),
        request<PageResult<Team>>('/teams?size=80'),
        request<PageResult<MyTask>>('/team-tasks/my?size=80'),
        request<PageResult<Notification>>('/notifications?size=80'),
        request<TodayOverview>('/home/today')
      ])
      profile.value = me
      profileForm.nickname = me.nickname
      profileForm.timezone = me.timezone
      timezoneForm.timezone = me.timezone
      schedules.value = schedulePage.list || []
      taskGroups.value = groupPage.list || []
      if (!scheduleForm.groupId && taskGroups.value[0]) scheduleForm.groupId = String(taskGroups.value[0].id)
      teams.value = teamPage.list || []
      myTasks.value = taskPage.list || []
      notifications.value = noticePage.list || []
      today.value = todayData
      if (teams.value[0] && !taskForm.teamId) taskForm.teamId = String(teams.value[0].id)
    } catch (e: any) {
      if (e.code === 401 || e.code === 404) logout()
      notify(e.message)
    } finally { loading.value = false }
  }

  async function createSchedule() {
    if (!scheduleForm.title.trim()) return notify('请输入标题')
    if (!scheduleForm.groupId) return notify('请先在「我的」中添加模块')
    const submittedFromModal = scheduleModalOpen.value
    try {
      await request('/schedules', { method: 'POST', body: JSON.stringify(toSchedulePayload(scheduleForm)) })
      Object.assign(scheduleForm, { title: '', timeType: 'point_event', startTime: '', endTime: '', deadlineTime: '' })
      if (taskGroups.value[0]) scheduleForm.groupId = String(taskGroups.value[0].id)
      await loadAll()
      scheduleModalOpen.value = false
      notify('日程已创建')
    } catch (e: any) { notify(e.message) }
  }

  async function setScheduleStatus(item: Schedule, action: string) {
    try { await request(`/schedules/${item.id}/${action}`, { method: 'PUT' }); await loadAll() } catch (e: any) { notify(e.message) }
  }
  async function deleteSchedule(id: number) {
    try { await request(`/schedules/${id}`, { method: 'DELETE' }); await loadAll(); notify('日程已删除') } catch (e: any) { notify(e.message) }
  }

  async function createTaskGroup() {
    if (!groupForm.name.trim()) return notify('请输入模块名称')
    try {
      await request('/task-groups', { method: 'POST', body: JSON.stringify({ scope: 'personal', name: groupForm.name.trim() }) })
      groupForm.name = ''; await loadAll(); notify('模块已创建')
    } catch (e: any) { notify(e.message) }
  }
  async function updateTaskGroup(group: TaskGroup) {
    if (!String(group.name || '').trim()) return notify('请输入模块名称')
    try {
      await request(`/task-groups/${group.id}`, { method: 'PUT', body: JSON.stringify({ name: String(group.name).trim() }) })
      await loadAll(); notify('模块已更新')
    } catch (e: any) { notify(e.message) }
  }
  async function deleteTaskGroup(group: TaskGroup) {
    try {
      await request(`/task-groups/${group.id}`, { method: 'DELETE' })
      if (String(scheduleForm.groupId) === String(group.id)) scheduleForm.groupId = ''
      await loadAll(); notify('模块已删除')
    } catch (e: any) { notify(e.message) }
  }

  async function createTeam() {
    if (!teamForm.name.trim()) return notify('请输入团队名称')
    try { await request('/teams', { method: 'POST', body: JSON.stringify(teamForm) }); teamForm.name = ''; await loadAll(); notify('团队已创建') } catch (e: any) { notify(e.message) }
  }

  async function joinTeam() {
    if (!joinForm.inviteCode.trim()) return notify('请输入邀请码')
    try {
      await request('/teams/join', { method: 'POST', body: JSON.stringify({ inviteCode: joinForm.inviteCode.trim() }) })
      joinForm.inviteCode = ''; await loadAll(); notify('已加入团队')
    } catch (e: any) { notify(e.message) }
  }

  async function createTask() {
    if (!taskForm.teamId || !taskForm.title.trim()) return notify('请选择团队并填写标题')
    const assigneeUserIds = taskForm.assigneeUserIds.split(',').map(v => Number(v.trim())).filter(Boolean)
    if (!assigneeUserIds.length) return notify('请输入执行人用户 ID')
    try {
      await request('/team-tasks', {
        method: 'POST',
        body: JSON.stringify(toApiTimePayload({ ...taskForm, teamId: Number(taskForm.teamId), assigneeUserIds }))
      })
      Object.assign(taskForm, { teamId: activeTeam.value?.id ? String(activeTeam.value.id) : '', title: '', deadlineTime: '', startTime: '', assigneeUserIds: '' })
      await loadAll(); notify('团队任务已创建')
    } catch (e: any) { notify(e.message) }
  }

  async function taskAction(task: MyTask, action: string) {
    try { await request(`/team-tasks/${task.id}/${action}`, { method: 'POST' }); await loadAll() } catch (e: any) { notify(e.message) }
  }

  async function readAll() {
    try { await request('/notifications/read-all', { method: 'PUT' }); await loadAll() } catch (e: any) { notify(e.message) }
  }
  async function readNotification(id: number) {
    try { await request(`/notifications/${id}/read`, { method: 'PUT' }); await loadAll() } catch (e: any) { notify(e.message) }
  }
  function openNotificationDetail(n: Notification) { notificationDetail.value = n }
  function closeNotificationDetail() { notificationDetail.value = null }

  /* 个人设置 */
  async function updateProfile() {
    try {
      await request('/user/profile', { method: 'PUT', body: JSON.stringify({ nickname: profileForm.nickname }) })
      await loadAll(); notify('资料已更新')
    } catch (e: any) { notify(e.message) }
  }
  async function changePassword() {
    if (passwordForm.newPassword.length < 8) return notify('新密码至少 8 位')
    if (passwordForm.newPassword !== passwordForm.confirmPassword) return notify('两次密码不一致')
    try {
      await request('/user/password', { method: 'PUT', body: JSON.stringify({ oldPassword: passwordForm.oldPassword, newPassword: passwordForm.newPassword }) })
      passwordForm.oldPassword = ''; passwordForm.newPassword = ''; passwordForm.confirmPassword = ''
      notify('密码已修改')
    } catch (e: any) { notify(e.message) }
  }
  async function updateTimezone() {
    try {
      await request('/user/timezone', { method: 'PUT', body: JSON.stringify({ timezone: timezoneForm.timezone }) })
      await loadAll(); notify('时区已更新')
    } catch (e: any) { notify(e.message) }
  }

  /* 团队管理 */
  async function setMemberRole(teamId: number, userId: number, role: string) {
    try { await request(`/teams/${teamId}/members/${userId}/role`, { method: 'PUT', body: JSON.stringify({ role }) }); await loadAll(); notify('角色已修改') } catch (e: any) { notify(e.message) }
  }
  async function removeMember(teamId: number, userId: number) {
    try { await request(`/teams/${teamId}/members/${userId}`, { method: 'DELETE' }); await loadAll(); notify('成员已移除') } catch (e: any) { notify(e.message) }
  }
  async function regenerateInviteCode(teamId: number) {
    try { const data = await request<{ inviteCode: string }>(`/teams/${teamId}/regenerate-invite-code`, { method: 'POST' }); await loadAll(); return data.inviteCode } catch (e: any) { notify(e.message); return '' }
  }

  function notify(message: string) {
    toast.value = message
    clearTimeout((notify as any)._timer)
    ;(notify as any)._timer = setTimeout(() => { toast.value = '' }, 2600)
  }

  return {
    /* 状态 */
    token, toast, loading, scheduleModalOpen, profile, schedules, taskGroups,
    teams, myTasks, notifications, today, notificationDetail, selectedDate,
    /* 表单 */
    loginForm, registerForm, scheduleForm, groupForm, teamForm, taskForm,
    joinForm, profileForm, passwordForm, timezoneForm,
    /* 计算属性 */
    pendingScheduleCount, activeTaskCount, activeTeam, timelineItems, upcoming,
    timelineStats, monthDays, loggedIn,
    /* 方法 */
    request, openScheduleModal, closeScheduleModal, login, register, logout, loadAll,
    createSchedule, setScheduleStatus, deleteSchedule,
    createTaskGroup, updateTaskGroup, deleteTaskGroup,
    createTeam, joinTeam, createTask, taskAction,
    readAll, readNotification, openNotificationDetail, closeNotificationDetail,
    updateProfile, changePassword, updateTimezone,
    setMemberRole, removeMember, regenerateInviteCode,
    notify, primaryTime
  }
})
