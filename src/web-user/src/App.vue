<script setup>
import { onMounted } from 'vue'
import { useDaylianeApp } from './composables/useDaylianeApp'

const {
  token, activeView, toast, loading, profile, schedules, teams, myTasks, notifications, today,
  loginForm, scheduleForm, teamForm, taskForm, nav, pendingScheduleCount, activeTaskCount,
  upcoming, timelineItems, timelineStats, monthDays, login, logout, loadAll, createSchedule,
  setScheduleStatus, createTeam, createTask, taskAction, readAll, primaryTime, formatTime,
  countdown, urgency, labels
} = useDaylianeApp()

onMounted(loadAll)
</script>

<template>
  <main v-if="!token" class="login-canvas">
    <section class="login-card">
      <p class="eyebrow">Dayliane</p>
      <h1>{{ labels.appTitle }}</h1>
      <p class="muted">{{ labels.appSubtitle }}</p>
      <form class="login-form" @submit.prevent="login">
        <label>{{ labels.phone }}<input v-model="loginForm.phone" autocomplete="username" /></label>
        <label>{{ labels.password }}<input v-model="loginForm.password" type="password" autocomplete="current-password" /></label>
        <button class="primary block" :disabled="loading">{{ labels.login }}</button>
      </form>
      <p class="hint">{{ labels.demoAccount }}</p>
    </section>
  </main>

  <main v-else class="app-frame">
    <aside class="side-nav">
      <div class="logo-area"><span class="logo-icon">D</span><strong>Dayliane</strong></div>
      <nav class="nav-items">
        <button v-for="item in nav" :key="item.id" :class="{ active: activeView === item.id }" @click="activeView = item.id">
          <span>{{ item.icon }}</span>{{ item.label }}
        </button>
      </nav>
      <div class="user-info">
        <strong>{{ profile?.nickname || labels.user }}</strong>
        <small>{{ profile?.phone }}</small>
        <button @click="logout">{{ labels.logout }}</button>
      </div>
    </aside>

    <section class="main-area">
      <header class="page-topbar">
        <div>
          <h1>{{ nav.find(n => n.id === activeView)?.label }}</h1>
          <p>{{ new Date().toLocaleDateString('zh-CN') }} {{ labels.today }}</p>
        </div>
        <div class="top-actions"><button @click="activeView='newSchedule'">{{ labels.newItem }}</button><button class="primary" @click="loadAll">{{ labels.refresh }}</button></div>
      </header>

      <section v-if="activeView === 'home'" class="home-layout">
        <div class="stats-row">
          <article class="stat-card"><span>{{ labels.pendingSchedules }}</span><strong>{{ pendingScheduleCount }}</strong></article>
          <article class="stat-card"><span>{{ labels.teamTasks }}</span><strong>{{ activeTaskCount }}</strong></article>
          <article class="stat-card"><span>{{ labels.unreadNotifications }}</span><strong>{{ today.unreadNotificationCount }}</strong></article>
        </div>
        <div class="home-content">
          <section class="group-panel">
            <div class="section-head"><h2>{{ labels.upcomingWork }}</h2><button @click="activeView='newSchedule'">{{ labels.add }}</button></div>
            <article v-for="item in upcoming" :key="`${item.sourceType}-${item.id}`" class="task-card">
              <div><strong>{{ item.title }}</strong><small>{{ item.sourceLabel }} - {{ formatTime(item.sortAt) }}</small></div>
              <span :class="['tag', urgency(item.sortAt)]">{{ countdown(item.sortAt) }}</span>
            </article>
          </section>
          <aside class="timeline-panel">
            <div class="timeline-head"><div><h2>{{ labels.timeline }}</h2><p>{{ timelineStats.today }} {{ labels.today }} / {{ timelineStats.overdue }} {{ labels.overdue }}</p></div></div>
            <div class="timeline-track">
              <article v-for="item in timelineItems.slice(0, 8)" :key="`${item.sourceType}-${item.id}-${item.assignRound || 0}`" :class="['timeline-entry', item.kind]">
                <div class="time-node"><span :class="['line-dot', urgency(item.sortAt)]"></span></div>
                <div class="timeline-card">
                  <div class="timeline-card-top"><span class="time-text">{{ formatTime(item.sortAt) }}</span><span class="kind-chip">{{ item.kindLabel }}</span></div>
                  <strong>{{ item.title }}</strong>
                  <small>{{ item.sourceLabel }}</small>
                </div>
              </article>
            </div>
          </aside>
        </div>
      </section>

      <section v-if="activeView === 'calendar'" class="split-layout">
        <section class="calendar-card"><h2>{{ labels.monthCalendar }}</h2><div class="month-grid"><article v-for="(d,i) in monthDays" :key="i" :class="['date-cell',{today:d.today}]"><strong>{{ d.day }}</strong><small v-if="d.items.length">{{ d.items.length }} {{ labels.items }}</small></article></div></section>
        <aside class="detail-panel"><h2>{{ labels.upcomingItems }}</h2><article v-for="item in upcoming" :key="item.id" class="mini-row"><strong>{{ item.title }}</strong><small>{{ countdown(item.sortAt) }}</small></article></aside>
      </section>

      <section v-if="activeView === 'schedules'" class="list-page">
        <div class="filter-bar"><button class="primary" @click="activeView='newSchedule'">{{ labels.newSchedule }}</button></div>
        <section class="table-card"><article v-for="s in schedules" :key="s.id" class="table-row"><div><strong>{{ s.title }}</strong><small>{{ s.groupName }} - {{ s.timeType }}</small></div><span class="tag">{{ s.status }}</span><span>{{ formatTime(primaryTime(s)) }}</span><button @click="setScheduleStatus(s, s.status === 'completed' ? 'uncomplete' : 'complete')">{{ s.status === 'completed' ? labels.undo : labels.complete }}</button></article></section>
      </section>

      <section v-if="activeView === 'newSchedule'" class="form-card"><h2>{{ labels.newSchedule }}</h2><form @submit.prevent="createSchedule"><label>{{ labels.title }}<input v-model="scheduleForm.title" /></label><label>{{ labels.type }}<select v-model="scheduleForm.timeType"><option value="deadline_task">{{ labels.deadlineTask }}</option><option value="point_event">{{ labels.pointEvent }}</option></select></label><label>{{ labels.startTime }}<input v-model="scheduleForm.startTime" type="datetime-local" /></label><label>{{ labels.endTime }}<input v-model="scheduleForm.endTime" type="datetime-local" /></label><label>{{ labels.deadlineTime }}<input v-model="scheduleForm.deadlineTime" type="datetime-local" /></label><div class="form-actions"><button type="button" @click="activeView='schedules'">{{ labels.cancel }}</button><button class="primary">{{ labels.save }}</button></div></form></section>

      <section v-if="activeView === 'teams'" class="split-layout"><section class="list-card"><div class="section-head"><h2>{{ labels.teams }}</h2></div><form class="inline-form" @submit.prevent="createTeam"><input v-model="teamForm.name" :placeholder="labels.teamName" /><button class="primary">{{ labels.create }}</button></form><article v-for="team in teams" :key="team.id" class="team-line"><div><strong>{{ team.name }}</strong><small>{{ labels.inviteCode }} {{ team.inviteCode }}</small></div><span>{{ team.memberCount }} {{ labels.members }}</span><span>{{ team.activeTaskCount }} {{ labels.tasks }}</span></article></section><aside class="detail-panel"><h2>{{ labels.inviteCode }}</h2><article v-for="team in teams.slice(0,1)" :key="team.id" class="code-box">{{ team.inviteCode }}</article></aside></section>

      <section v-if="activeView === 'tasks'" class="split-layout"><section class="list-card"><div class="section-head"><h2>{{ labels.teamTasks }}</h2></div><form class="inline-form" @submit.prevent="createTask"><select v-model="taskForm.teamId"><option value="">{{ labels.team }}</option><option v-for="team in teams" :key="team.id" :value="team.id">{{ team.name }}</option></select><input v-model="taskForm.title" :placeholder="labels.taskTitle" /><input v-model="taskForm.deadlineTime" type="datetime-local" /><input v-model="taskForm.assigneeUserIds" :placeholder="labels.assigneeIds" /><button>{{ labels.create }}</button></form><article v-for="task in myTasks" :key="task.assigneeId || task.id" class="task-card"><div><strong>{{ task.title }}</strong><small>{{ task.teamName || labels.team }} - {{ countdown(task.deadlineTime) }}</small></div><span class="tag blue">{{ task.assignStatus || task.status }}</span><button @click="taskAction(task,'accept')">{{ labels.accept }}</button><button @click="taskAction(task,'reject')">{{ labels.reject }}</button><button @click="taskAction(task,'complete')">{{ labels.complete }}</button></article></section><aside class="detail-panel"><h2>{{ labels.taskDetail }}</h2><p class="muted">{{ labels.taskDetailNote }}</p></aside></section>

      <section v-if="activeView === 'notifications'" class="split-layout"><section class="notice-list"><div class="section-head"><h2>{{ labels.notifications }}</h2><button @click="readAll">{{ labels.readAll }}</button></div><article v-for="n in notifications" :key="n.id" :class="['notice-row',{read:n.isRead}]"><strong>{{ n.title }}</strong><small>{{ n.content }}</small><span>{{ formatTime(n.createdAt) }}</span></article></section><aside class="detail-panel"><h2>{{ labels.unread }}</h2><strong>{{ today.unreadNotificationCount }}</strong></aside></section>

      <section v-if="activeView === 'profile'" class="settings-card"><div class="profile-head"><div class="avatar">{{ profile?.nickname?.slice(0,1) || 'U' }}</div><div><h2>{{ profile?.nickname }}</h2><p>{{ profile?.phone }}</p></div></div><article>{{ labels.timezone }}{{ profile?.timezone }}</article><article @click="logout">{{ labels.logout }}</article></section>
    </section>

    <div v-if="toast" class="toast">{{ toast }}</div>
  </main>
</template>