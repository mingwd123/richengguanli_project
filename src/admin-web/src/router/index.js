import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('../views/LoginPage.vue'),
    meta: { guest: true },
  },
  {
    path: '/',
    component: () => import('@/layouts/AdminLayout.vue'),
    meta: { requiresAuth: true },
    children: [
      { path: '', name: 'Dashboard', component: () => import('../views/DashboardPage.vue') },
      { path: 'users', name: 'Users', component: () => import('../views/UsersPage.vue') },
      { path: 'teams', name: 'Teams', component: () => import('../views/TeamsPage.vue') },
      { path: 'schedules', name: 'Schedules', component: () => import('../views/SchedulesPage.vue') },
      { path: 'team-tasks', name: 'TeamTasks', component: () => import('../views/TeamTasksPage.vue') },
      { path: 'notifications', name: 'Notifications', component: () => import('../views/NotificationsPage.vue') },
      { path: 'tickets', name: 'Tickets', component: () => import('../views/TicketsPage.vue') },
      { path: 'reminders', name: 'Reminders', component: () => import('../views/RemindersPage.vue') },
      { path: 'admin-users', name: 'AdminUsers', component: () => import('../views/AdminUsersPage.vue'), meta: { superAdmin: true } },
      { path: 'operation-logs', name: 'OperationLogs', component: () => import('../views/OperationLogsPage.vue') },
      { path: 'ai-config', name: 'AiConfig', component: () => import('../views/AiConfigPage.vue'), meta: { superAdmin: true } },
      { path: 'ai-logs', name: 'AiLogs', component: () => import('../views/AiLogsPage.vue') },
      { path: 'views/fatigue', name: 'FatigueView', component: () => import('../views/DataViewsPage.vue'), props: { kind: 'fatigue' } },
      { path: 'views/ops', name: 'OpsView', component: () => import('../views/DataViewsPage.vue'), props: { kind: 'ops' } },
      { path: 'views/collab', name: 'CollabView', component: () => import('../views/DataViewsPage.vue'), props: { kind: 'collab' } },
      { path: 'views/ai', name: 'AiView', component: () => import('../views/DataViewsPage.vue'), props: { kind: 'ai' } },
      { path: 'views/system', name: 'SystemStatus', component: () => import('../views/SystemStatusPage.vue') },
      { path: 'views/security', name: 'SecurityView', component: () => import('../views/DataViewsPage.vue'), props: { kind: 'security' } },
      { path: 'ai-quota', name: 'AiQuota', component: () => import('../views/AiQuotaPage.vue'), meta: { superAdmin: true } },
    ],
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach((to, from, next) => {
  const token = localStorage.getItem('dayliane_admin_token')
  if (to.meta.requiresAuth && !token) next('/login')
  else if (to.meta.superAdmin && localStorage.getItem('dayliane_admin_role') !== 'super_admin') next('/')
  else if (to.meta.guest && token) next('/')
  else next()
})

export default router
