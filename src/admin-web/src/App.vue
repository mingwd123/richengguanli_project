<script setup>
import { onMounted } from 'vue'
import { useAdminApp } from './composables/useAdminApp'

const { token, activeResource, loading, toast, profile, page, loginForm, resources, currentResource, stats, login, logout, loadProfile, loadResource, setUserStatus, formatValue } = useAdminApp()

onMounted(async () => {
  if (token.value) {
    await loadProfile()
    await loadResource(activeResource.value)
  }
})
</script>

<template>
  <main v-if="!token" class="login-page">
    <section class="login-panel">
      <p class="eyebrow">Dayliane Admin</p>
      <h1>日程提醒管理后台</h1>
      <p class="muted">MVP 阶段先复用登录用户 Token 查看管理数据。</p>
      <form class="login-form" @submit.prevent="login">
        <label>手机号<input v-model="loginForm.phone" autocomplete="username" /></label>
        <label>密码<input v-model="loginForm.password" type="password" autocomplete="current-password" /></label>
        <button class="primary" :disabled="loading">登录后台</button>
      </form>
    </section>
  </main>

  <main v-else class="admin-shell">
    <aside class="sidebar">
      <div class="brand"><span>D</span><strong>管理后台</strong></div>
      <nav>
        <button v-for="item in resources" :key="item.id" :class="{ active: activeResource === item.id }" @click="loadResource(item.id)">{{ item.label }}</button>
      </nav>
      <div class="account">
        <strong>{{ profile?.nickname || '管理员' }}</strong>
        <small>{{ profile?.phone }}</small>
        <button @click="logout">退出</button>
      </div>
    </aside>

    <section class="workspace">
      <header class="topbar">
        <div>
          <h1>{{ currentResource.label }}</h1>
          <p>查看 MVP 核心业务数据，后续补充管理员角色与操作日志。</p>
        </div>
        <button class="primary" @click="loadResource(activeResource)">刷新</button>
      </header>

      <section class="stats">
        <article><span>总数</span><strong>{{ stats.total }}</strong></article>
        <article><span>启用</span><strong>{{ stats.active }}</strong></article>
        <article><span>待处理</span><strong>{{ stats.pending }}</strong></article>
      </section>

      <section class="table-panel">
        <div class="table-head">
          <h2>{{ currentResource.label }}</h2>
          <small>第 {{ page.page }} 页 / 每页 {{ page.size }} 条</small>
        </div>
        <div class="data-table">
          <div class="table-row table-title" :style="{ gridTemplateColumns: `repeat(${currentResource.columns.length + (activeResource === 'users' ? 1 : 0)}, minmax(120px, 1fr))` }">
            <strong v-for="column in currentResource.columns" :key="column">{{ column }}</strong>
            <strong v-if="activeResource === 'users'">操作</strong>
          </div>
          <div v-for="row in page.list" :key="row.id" class="table-row" :style="{ gridTemplateColumns: `repeat(${currentResource.columns.length + (activeResource === 'users' ? 1 : 0)}, minmax(120px, 1fr))` }">
            <span v-for="column in currentResource.columns" :key="column" :title="String(formatValue(row[column]))">{{ formatValue(row[column]) }}</span>
            <span v-if="activeResource === 'users'" class="row-actions">
              <button v-if="row.status !== 'active'" @click="setUserStatus(row, 'active')">启用</button>
              <button v-else @click="setUserStatus(row, 'disabled')">禁用</button>
            </span>
          </div>
          <div v-if="!page.list.length" class="empty">暂无数据</div>
        </div>
      </section>
    </section>

    <div v-if="toast" class="toast">{{ toast }}</div>
  </main>
</template>