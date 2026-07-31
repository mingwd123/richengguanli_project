import { createRouter, createWebHashHistory } from 'vue-router'
import { useAppStore } from '@web/stores/app'
import DesktopLayout from '../views/DesktopLayout.vue'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@web/views/LoginRegister.vue'),
    meta: { guest: true, title: '登录' },
  },
  {
    path: '/',
    component: DesktopLayout,
    meta: { requiresAuth: true },
    children: [
      { path: '', name: 'Home', component: () => import('@web/views/HomePage.vue'), meta: { title: '今日概览' } },
      { path: 'calendar', name: 'Calendar', component: () => import('@web/views/CalendarPage.vue'), meta: { title: '日历' } },
      { path: 'schedules', name: 'Schedules', component: () => import('@web/views/SchedulesPage.vue'), meta: { title: '个人日程' } },
      { path: 'schedules/:id', name: 'ScheduleDetail', component: () => import('@web/views/ScheduleDetailPage.vue'), props: true, meta: { title: '日程详情' } },
      { path: 'teams', name: 'Teams', component: () => import('@web/views/TeamsPage.vue'), meta: { title: '团队' } },
      { path: 'teams/:id', name: 'TeamDetail', component: () => import('@web/views/TeamDetailPage.vue'), props: true, meta: { title: '团队详情' } },
      { path: 'tasks', name: 'Tasks', component: () => import('@web/views/TasksPage.vue'), meta: { title: '团队任务' } },
      { path: 'tasks/:id', name: 'TeamTaskDetail', component: () => import('@web/views/TeamTaskDetailPage.vue'), props: true, meta: { title: '任务详情' } },
      { path: 'tasks/:id/assignees/:userId/status', name: 'TeamTaskAssigneeStatus', component: () => import('@web/views/TeamTaskAssigneeStatusPage.vue'), props: true, meta: { title: '修正执行状态' } },
      { path: 'tasks/:id/assignees/:userId/reassign', name: 'TeamTaskReassign', component: () => import('@web/views/TeamTaskReassignPage.vue'), props: true, meta: { title: '重新分配任务' } },
      { path: 'notifications', name: 'Notifications', component: () => import('@web/views/NotificationsPage.vue'), meta: { title: '通知' } },
      { path: 'reminders', name: 'Reminders', component: () => import('@web/views/RemindersPage.vue'), meta: { title: '提醒记录' } },
      { path: 'profile', name: 'Profile', component: () => import('@web/views/ProfilePage.vue'), meta: { title: '个人中心' } },
      { path: 'profile/settings', name: 'ProfileSettings', component: () => import('@web/views/ProfileSettingsPage.vue'), meta: { title: '个人设置' } },
      { path: 'profile/notifications', name: 'NotificationSettings', component: () => import('@web/views/NotificationSettingsPage.vue'), meta: { title: '通知设置' } },
    ],
  },
]

const router = createRouter({
  history: createWebHashHistory(),
  routes,
})

router.beforeEach((to) => {
  const store = useAppStore()
  if (to.meta.requiresAuth && !store.loggedIn) return '/login'
  if (to.meta.guest && store.loggedIn) return '/'
})

router.afterEach((to) => {
  document.title = `${String(to.meta.title || '工作台')} | Dayliane`
})

export default router
