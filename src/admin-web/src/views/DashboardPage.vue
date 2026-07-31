<script setup>
import {
  ArrowRight,
  Bell,
  Calendar,
  Document,
  List,
  Management,
  Timer,
  User,
  UserFilled,
} from '@element-plus/icons-vue'
import { useAdminStore } from '../stores/admin'

const store = useAdminStore()
const quickLinks = [
  { to: '/users', label: '用户管理', description: '账号状态与用户详情', icon: User, tone: 'teal' },
  { to: '/teams', label: '团队管理', description: '团队结构与成员信息', icon: UserFilled, tone: 'blue' },
  { to: '/schedules', label: '日程管理', description: '全站日程记录', icon: Calendar, tone: 'amber' },
  { to: '/team-tasks', label: '团队任务', description: '任务状态与执行人', icon: List, tone: 'coral' },
  { to: '/notifications', label: '通知记录', description: '站内通知投递记录', icon: Bell, tone: 'blue' },
  { to: '/reminders', label: '提醒记录', description: '定时提醒执行结果', icon: Timer, tone: 'amber' },
  { to: '/admin-users', label: '管理员账号', description: '后台访问权限', icon: Management, tone: 'teal' },
  { to: '/operation-logs', label: '操作日志', description: '敏感操作审计', icon: Document, tone: 'coral' },
]
</script>

<template>
  <div class="dashboard">
    <header class="topbar">
      <div><h1>运营总览</h1><p>快速进入资源管理和系统审计。</p></div>
    </header>

    <section class="admin-welcome-band">
      <div>
        <p>ADMIN WORKSPACE</p>
        <h2>欢迎回来，{{ store.profile?.username || '管理员' }}</h2>
        <span>所有核心管理入口都已就绪。</span>
      </div>
      <div class="admin-health">
        <span class="health-dot"></span>
        <div><strong>服务正常</strong><small>API 与数据库已连接</small></div>
      </div>
    </section>

    <div class="dashboard-section-head"><div><h2>管理入口</h2><p>按资源类型进入对应工作区</p></div><span>{{ quickLinks.length }} 个模块</span></div>
    <section class="quick-links">
      <router-link v-for="item in quickLinks" :key="item.to" :to="item.to" class="quick-link-card">
        <span :class="['quick-link-icon', item.tone]"><el-icon><component :is="item.icon" /></el-icon></span>
        <div><strong>{{ item.label }}</strong><span>{{ item.description }}</span></div>
        <el-icon class="quick-link-arrow"><ArrowRight /></el-icon>
      </router-link>
    </section>
  </div>
</template>
