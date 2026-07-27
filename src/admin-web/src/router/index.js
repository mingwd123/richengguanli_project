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
      { path: 'reminders', name: 'Reminders', component: () => import('../views/RemindersPage.vue') },
      { path: 'admin-users', name: 'AdminUsers', component: () => import('../views/AdminUsersPage.vue') },
      { path: 'operation-logs', name: 'OperationLogs', component: () => import('../views/OperationLogsPage.vue') },
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
  else if (to.meta.guest && token) next('/')
  else next()
})

export default router
