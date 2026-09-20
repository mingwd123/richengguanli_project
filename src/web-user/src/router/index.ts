import { createRouter, createWebHistory } from 'vue-router'
import { useAppStore } from '../stores/app'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('../views/LoginRegister.vue'),
    meta: { guest: true }
  },
  {
    path: '/forgot-password',
    name: 'ForgotPassword',
    component: () => import('../views/ForgotPasswordPage.vue'),
    meta: { guest: true }
  },
  {
    path: '/',
    component: () => import('../views/MainLayout.vue'),
    meta: { requiresAuth: true },
    children: [
      { path: '', name: 'Home', component: () => import('../views/HomePage.vue') },
      { path: 'calendar', name: 'Calendar', component: () => import('../views/CalendarPage.vue') },
      { path: 'schedules', name: 'Schedules', component: () => import('../views/SchedulesPage.vue') },
      { path: 'schedules/:id', name: 'ScheduleDetail', component: () => import('../views/ScheduleDetailPage.vue'), props: true },
      { path: 'teams', name: 'Teams', component: () => import('../views/TeamsPage.vue') },
      { path: 'teams/:id', name: 'TeamDetail', component: () => import('../views/TeamDetailPage.vue'), props: true },
      { path: 'tasks', name: 'Tasks', component: () => import('../views/TasksPage.vue') },
      { path: 'tasks/:id', name: 'TeamTaskDetail', component: () => import('../views/TeamTaskDetailPage.vue'), props: true },
      { path: 'tasks/:id/assignees/:assigneeId/status', name: 'TeamTaskAssigneeStatus', component: () => import('../views/TeamTaskAssigneeStatusPage.vue'), props: true },
      { path: 'tasks/:id/assignees/:assigneeId/reassign', name: 'TeamTaskReassign', component: () => import('../views/TeamTaskReassignPage.vue'), props: true },
      { path: 'notifications', name: 'Notifications', component: () => import('../views/NotificationsPage.vue') },
      { path: 'tickets', name: 'Tickets', component: () => import('../views/TicketsPage.vue') },
      { path: 'tickets/:id', name: 'TicketDetail', component: () => import('../views/TicketDetailPage.vue'), props: true },
      { path: 'fatigue/survey', name: 'FatigueSurvey', component: () => import('../views/FatigueSurveyPage.vue') },
      { path: 'fatigue/report', name: 'FatigueReport', component: () => import('../views/FatigueReportPage.vue') },
      { path: 'reminders', name: 'Reminders', component: () => import('../views/RemindersPage.vue') },
      { path: 'profile', name: 'Profile', component: () => import('../views/ProfilePage.vue') },
      { path: 'profile/settings', name: 'ProfileSettings', component: () => import('../views/ProfileSettingsPage.vue') },
      { path: 'profile/settings/calendar-subscription', name: 'CalendarSubscriptionHelp', component: () => import('../views/CalendarSubscriptionHelpPage.vue') },
      { path: 'profile/fatigue', name: 'FatigueSettings', component: () => import('../views/FatigueSettingsPage.vue') },
      { path: 'profile/notifications', name: 'NotificationSettings', component: () => import('../views/NotificationSettingsPage.vue') },
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach((to, _from, next) => {
  const store = useAppStore()
  if (to.meta.requiresAuth && !store.loggedIn) {
    next('/login')
  } else if (to.meta.guest && store.loggedIn) {
    next('/')
  } else {
    next()
  }
})

export default router
