import { computed, reactive, ref } from 'vue'
import { apiRequest } from '../api/http'

const TOKEN_KEY = 'dayliane_token'
const zh = (...codes) => String.fromCharCode(...codes)

const labels = {
  appTitle: '\u65e5\u7a0b\u63d0\u9192',
  appSubtitle: '\u4e2a\u4eba\u65e5\u7a0b\u4e0e\u5c0f\u56e2\u961f\u4efb\u52a1\u534f\u4f5c\u5de5\u5177\u3002',
  phone: '\u624b\u673a\u53f7', password: '\u5bc6\u7801', login: '\u767b\u5f55',
  demoAccount: '\u6f14\u793a\u8d26\u53f7\uff1a13800138000 / Abc12345', user: '\u7528\u6237', logout: '\u9000\u51fa\u767b\u5f55',
  today: '\u4eca\u5929', newItem: '\u65b0\u5efa', refresh: '\u5237\u65b0', pendingSchedules: '\u5f85\u529e\u65e5\u7a0b',
  teamTasks: '\u56e2\u961f\u4efb\u52a1', unreadNotifications: '\u672a\u8bfb\u901a\u77e5', upcomingWork: '\u8fd1\u671f\u4efb\u52a1', add: '\u6dfb\u52a0',
  timeline: '\u65f6\u95f4\u8f74', overdue: '\u903e\u671f', monthCalendar: '\u672c\u6708\u65e5\u5386', items: '\u9879', upcomingItems: '\u8fd1\u671f\u4e8b\u9879',
  newSchedule: '\u65b0\u5efa\u65e5\u7a0b', undo: '\u6062\u590d', complete: '\u5b8c\u6210', title: '\u6807\u9898', type: '\u7c7b\u578b',
  deadlineTask: '\u622a\u6b62\u4efb\u52a1', pointEvent: '\u77ac\u65f6\u65e5\u7a0b', startTime: '\u5f00\u59cb\u65f6\u95f4', endTime: '\u7ed3\u675f\u65f6\u95f4', deadlineTime: '\u622a\u6b62\u65f6\u95f4',
  cancel: '\u53d6\u6d88', save: '\u4fdd\u5b58', teams: '\u56e2\u961f', teamName: '\u56e2\u961f\u540d\u79f0', create: '\u521b\u5efa', inviteCode: '\u9080\u8bf7\u7801',
  members: '\u6210\u5458', tasks: '\u4efb\u52a1', team: '\u56e2\u961f', taskTitle: '\u4efb\u52a1\u6807\u9898', assigneeIds: '\u6267\u884c\u4eba\u7528\u6237 ID\uff0c\u9017\u53f7\u5206\u9694',
  accept: '\u63a5\u53d7', reject: '\u62d2\u7edd', taskDetail: '\u4efb\u52a1\u8be6\u60c5', taskDetailNote: '\u6267\u884c\u4eba\u5361\u7247\u3001\u72b6\u6001\u4fee\u6b63\u548c\u91cd\u65b0\u5206\u914d\u5c06\u5728\u540e\u7eed MVP \u6b65\u9aa4\u8865\u9f50\u3002',
  notifications: '\u901a\u77e5', readAll: '\u5168\u90e8\u5df2\u8bfb', unread: '\u672a\u8bfb', timezone: '\u65f6\u533a\uff1a'
}

export function useDaylianeApp() {
  const token = ref(localStorage.getItem(TOKEN_KEY) || '')
  const activeView = ref('home')
  const toast = ref('')
  const loading = ref(false)
  const profile = ref(null)
  const schedules = ref([])
  const teams = ref([])
  const myTasks = ref([])
  const notifications = ref([])
  const today = ref({ personalSchedules: [], teamTasks: [], unreadNotificationCount: 0, groups: [] })

  const loginForm = reactive({ phone: '13800138000', password: 'Abc12345' })
  const scheduleForm = reactive({ title: '', groupName: '\u5de5\u4f5c', timeType: 'deadline_task', startTime: '', endTime: '', deadlineTime: '' })
  const teamForm = reactive({ name: '' })
  const taskForm = reactive({ teamId: '', title: '', deadlineTime: '', assigneeUserIds: '' })

  const nav = [
    { id: 'home', label: '\u9996\u9875', icon: 'H' },
    { id: 'calendar', label: '\u65e5\u5386', icon: 'C' },
    { id: 'schedules', label: '\u4e2a\u4eba\u65e5\u7a0b', icon: 'S' },
    { id: 'newSchedule', label: '\u65b0\u5efa', icon: '+' },
    { id: 'teams', label: '\u56e2\u961f', icon: 'T' },
    { id: 'tasks', label: '\u56e2\u961f\u4efb\u52a1', icon: 'K' },
    { id: 'notifications', label: '\u901a\u77e5', icon: 'N' },
    { id: 'profile', label: '\u6211\u7684', icon: 'P' },
  ]

  const pendingScheduleCount = computed(() => schedules.value.filter(s => s.status === 'pending').length)
  const activeTaskCount = computed(() => myTasks.value.filter(t => t.status === 'active').length)
  const activeTeam = computed(() => teams.value[0] || null)
  const timelineItems = computed(() => [...schedules.value.map(i => normalizeTimelineItem(i, 'schedule')), ...myTasks.value.map(i => normalizeTimelineItem(i, 'team_task'))]
    .filter(i => ['pending', 'active', 'accepted'].includes(i.status) || ['pending', 'accepted'].includes(i.assignStatus))
    .filter(i => i.sortAt)
    .sort((a, b) => new Date(a.sortAt) - new Date(b.sortAt)))
  const upcoming = computed(() => timelineItems.value.slice(0, 6))
  const timelineStats = computed(() => {
    const todayKey = new Date().toISOString().slice(0, 10)
    return {
      today: timelineItems.value.filter(i => i.sortAt.startsWith(todayKey)).length,
      overdue: timelineItems.value.filter(i => new Date(i.sortAt).getTime() < Date.now()).length,
      upcoming: timelineItems.value.filter(i => new Date(i.sortAt).getTime() >= Date.now()).length
    }
  })
  const monthDays = computed(() => buildMonthDays(schedules.value))

  async function request(path, options = {}) { return apiRequest(path, options, token.value) }
  async function login() {
    loading.value = true
    try {
      const data = await request('/auth/login', { method: 'POST', body: JSON.stringify(loginForm) })
      token.value = data.accessToken
      localStorage.setItem(TOKEN_KEY, data.accessToken)
      await loadAll()
      notify('\u767b\u5f55\u6210\u529f')
    } catch (e) { notify(e.message) } finally { loading.value = false }
  }
  function logout() { token.value = ''; localStorage.removeItem(TOKEN_KEY); profile.value = null }
  async function loadAll() {
    if (!token.value) return
    loading.value = true
    try {
      const [me, schedulePage, teamPage, taskPage, noticePage, todayData] = await Promise.all([
        request('/user/profile'), request('/schedules?size=80'), request('/teams?size=80'),
        request('/team-tasks/my?size=80'), request('/notifications?size=80'), request('/home/today')
      ])
      profile.value = me
      schedules.value = schedulePage.list || []
      teams.value = teamPage.list || []
      myTasks.value = taskPage.list || []
      notifications.value = noticePage.list || []
      today.value = todayData
      if (teams.value[0] && !taskForm.teamId) taskForm.teamId = teams.value[0].id
    } catch (e) { notify(e.message) } finally { loading.value = false }
  }
  async function createSchedule() {
    if (!scheduleForm.title.trim()) return notify('\u8bf7\u8f93\u5165\u6807\u9898')
    try {
      await request('/schedules', { method: 'POST', body: JSON.stringify(toApiTimePayload(scheduleForm)) })
      Object.assign(scheduleForm, { title: '', groupName: '\u5de5\u4f5c', timeType: 'deadline_task', startTime: '', endTime: '', deadlineTime: '' })
      await loadAll(); activeView.value = 'schedules'; notify('\u65e5\u7a0b\u5df2\u521b\u5efa')
    } catch (e) { notify(e.message) }
  }
  async function setScheduleStatus(item, action) { try { await request(`/schedules/${item.id}/${action}`, { method: 'PUT' }); await loadAll() } catch (e) { notify(e.message) } }
  async function createTeam() { if (!teamForm.name.trim()) return notify('\u8bf7\u8f93\u5165\u56e2\u961f\u540d\u79f0'); try { await request('/teams', { method: 'POST', body: JSON.stringify(teamForm) }); teamForm.name = ''; await loadAll(); notify('\u56e2\u961f\u5df2\u521b\u5efa') } catch (e) { notify(e.message) } }
  async function createTask() {
    if (!taskForm.teamId || !taskForm.title.trim()) return notify('\u8bf7\u9009\u62e9\u56e2\u961f\u5e76\u586b\u5199\u6807\u9898')
    const assigneeUserIds = taskForm.assigneeUserIds.split(',').map(v => Number(v.trim())).filter(Boolean)
    if (!assigneeUserIds.length) return notify('\u8bf7\u8f93\u5165\u6267\u884c\u4eba\u7528\u6237 ID')
    try {
      await request('/team-tasks', { method: 'POST', body: JSON.stringify(toApiTimePayload({ ...taskForm, teamId: Number(taskForm.teamId), assigneeUserIds })) })
      Object.assign(taskForm, { teamId: activeTeam.value?.id || '', title: '', deadlineTime: '', assigneeUserIds: '' })
      await loadAll(); notify('\u56e2\u961f\u4efb\u52a1\u5df2\u521b\u5efa')
    } catch (e) { notify(e.message) }
  }
  async function taskAction(task, action) { try { await request(`/team-tasks/${task.id}/${action}`, { method: 'POST' }); await loadAll() } catch (e) { notify(e.message) } }
  async function readAll() { try { await request('/notifications/read-all', { method: 'PUT' }); await loadAll() } catch (e) { notify(e.message) } }
  function notify(message) { toast.value = message; clearTimeout(notify.timer); notify.timer = setTimeout(() => { toast.value = '' }, 2600) }

  return { token, activeView, toast, loading, profile, schedules, teams, myTasks, notifications, today, loginForm, scheduleForm, teamForm, taskForm, nav, labels, pendingScheduleCount, activeTaskCount, upcoming, timelineItems, timelineStats, activeTeam, monthDays, login, logout, loadAll, createSchedule, setScheduleStatus, createTeam, createTask, taskAction, readAll, primaryTime, formatTime, countdown, urgency }
}

function toApiTimePayload(input) {
  const out = { ...input }
  for (const key of ['startTime', 'endTime', 'deadlineTime']) if (out[key]) out[key] = new Date(out[key]).toISOString()
  return out
}
function normalizeTimelineItem(item, sourceType) {
  const startAt = item.startTime || ''
  const deadlineAt = item.deadlineTime || ''
  const kind = sourceType === 'team_task' ? (startAt && deadlineAt ? 'duration_task' : 'deadline_task') : item.timeType === 'point_event' ? 'point_event' : (startAt && deadlineAt ? 'duration_task' : 'deadline_task')
  const sortAt = kind === 'point_event' ? startAt : (deadlineAt || startAt)
  return { ...item, sourceType, kind, kindLabel: kindName(kind), sortAt, statusText: item.assignStatus || item.status, sourceLabel: sourceType === 'team_task' ? (item.teamName || '\u56e2\u961f\u4efb\u52a1') : (item.groupName || '\u4e2a\u4eba\u65e5\u7a0b') }
}
function kindName(kind) { return kind === 'point_event' ? '\u65e5\u7a0b' : kind === 'duration_task' ? '\u6301\u7eed\u4efb\u52a1' : '\u622a\u6b62\u4efb\u52a1' }
function buildMonthDays(schedules) {
  const now = new Date(); const first = new Date(now.getFullYear(), now.getMonth(), 1); const days = []
  for (let i = 0; i < (first.getDay() + 6) % 7; i++) days.push({ day: '', items: [] })
  const count = new Date(now.getFullYear(), now.getMonth() + 1, 0).getDate()
  for (let d = 1; d <= count; d++) { const key = new Date(now.getFullYear(), now.getMonth(), d).toISOString().slice(0, 10); days.push({ day: d, today: d === now.getDate(), items: schedules.filter(s => primaryTime(s).startsWith(key)) }) }
  return days
}
function primaryTime(item) { return item.deadlineTime || item.startTime || item.createdAt || '' }
function formatTime(value) { if (!value) return '\u672a\u8bbe\u7f6e'; const date = new Date(value); return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(date) }
function countdown(value) { if (!value) return '\u65e0\u622a\u6b62\u65f6\u95f4'; const diff = new Date(value).getTime() - Date.now(); const abs = Math.abs(diff); const minutes = Math.floor(abs / 60000); const hours = Math.floor(minutes / 60); const days = Math.floor(hours / 24); if (diff < 0) return days > 0 ? `\u5df2\u903e\u671f ${days} \u5929` : hours > 0 ? `\u5df2\u903e\u671f ${hours} \u5c0f\u65f6` : `\u5df2\u903e\u671f ${minutes} \u5206\u949f`; if (hours >= 24) return `\u5269\u4f59 ${days} \u5929`; return `\u5269\u4f59 ${hours} \u5c0f\u65f6 ${minutes % 60} \u5206\u949f` }
function urgency(value) { if (!value) return ''; const diff = new Date(value).getTime() - Date.now(); if (diff < 0 || diff <= 10 * 60000) return 'danger'; if (diff <= 30 * 60000) return 'warning'; return '' }