<script setup>
import { useAdminStore } from '../stores/admin'

const store = useAdminStore()
</script>

<template>
  <div class="dashboard">
    <header class="topbar">
      <div>
        <h1>总览</h1>
        <p>欢迎使用 Dayliane 管理后台。</p>
      </div>
    </header>

    <section class="welcome-card">
      <h2>欢迎回来，{{ store.profile?.username || '管理员' }}</h2>
      <p>您可以通过左侧导航菜单管理各项资源。</p>
    </section>

    <section class="quick-links">
      <router-link to="/users" class="quick-link-card">
        <strong>用户管理</strong>
        <span>查看和管理所有用户</span>
      </router-link>
      <router-link to="/teams" class="quick-link-card">
        <strong>团队管理</strong>
        <span>查看和管理所有团队</span>
      </router-link>
      <router-link to="/schedules" class="quick-link-card">
        <strong>日程查看</strong>
        <span>查看所有日程安排</span>
      </router-link>
      <router-link to="/team-tasks" class="quick-link-card">
        <strong>团队任务</strong>
        <span>查看所有团队任务</span>
      </router-link>
      <router-link to="/notifications" class="quick-link-card">
        <strong>通知记录</strong>
        <span>查看所有通知</span>
      </router-link>
      <router-link to="/reminders" class="quick-link-card">
        <strong>提醒记录</strong>
        <span>查看所有提醒</span>
      </router-link>
      <router-link to="/admin-users" class="quick-link-card">
        <strong>管理员账号</strong>
        <span>管理后台管理员</span>
      </router-link>
      <router-link to="/operation-logs" class="quick-link-card">
        <strong>操作日志</strong>
        <span>查看操作历史</span>
      </router-link>
    </section>
  </div>
</template>

<style scoped>
.dashboard {
  padding-top: 0;
}

.welcome-card {
  margin-bottom: 24px;
  padding: 24px;
  border: 1px solid #e5eaf2;
  border-radius: 8px;
  background: #fff;
}

.welcome-card h2 {
  margin-bottom: 8px;
}

.welcome-card p {
  color: #718096;
}

.quick-links {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 16px;
}

.quick-link-card {
  display: grid;
  gap: 8px;
  padding: 20px;
  border: 1px solid #e5eaf2;
  border-radius: 8px;
  background: #fff;
  text-decoration: none;
  color: inherit;
  transition: box-shadow 0.2s, border-color 0.2s;
}

.quick-link-card:hover {
  border-color: #2563eb;
  box-shadow: 0 4px 12px rgba(37, 99, 235, 0.1);
}

.quick-link-card span {
  font-size: 13px;
  color: #718096;
}
</style>
