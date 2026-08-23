<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useAppStore } from '../stores/app'

const router = useRouter()
const store = useAppStore()

function handleLogout() {
  store.logout()
  router.push('/login')
}

function goToSettings() {
  router.push('/profile/settings')
}
</script>

<template>
  <section class="settings-card">
    <div class="profile-head">
      <div class="avatar">
        <img v-if="store.profile?.avatarUrl" :src="store.profile.avatarUrl" alt="" />
        <span v-else>{{ store.profile?.nickname?.slice(0, 1) || 'U' }}</span>
      </div>
      <div>
        <h2>{{ store.profile?.nickname || '用户' }}</h2>
        <p>{{ store.profile?.phone || store.profile?.email || '未绑定联系方式' }}</p>
      </div>
    </div>

    <article>
      时区：{{ store.profile?.timezone || '未设置' }}
    </article>

    <section class="module-manager">
      <div class="section-head">
        <h2>任务模块</h2>
      </div>
      <form class="module-form" @submit.prevent="store.createTaskGroup">
        <input v-model="store.groupForm.name" placeholder="模块名称" />
        <button class="primary">添加模块</button>
      </form>
      <article v-for="group in store.taskGroups" :key="group.id" class="module-row">
        <input v-model="group.name" />
        <button @click="store.updateTaskGroup(group)">重命名</button>
        <button @click="store.deleteTaskGroup(group)">删除</button>
      </article>
      <p v-if="store.taskGroups.length === 0" class="muted" style="padding: 10px 0;">暂无任务模块，请添加</p>
    </section>

    <article @click="goToSettings" style="cursor: pointer; color: #2f80ed;">
      个人设置 ›
    </article>

    <article @click="router.push('/profile/notifications')" style="cursor: pointer; color: #2f80ed;">
      通知设置 ›
    </article>

    <article @click="router.push('/profile/fatigue')" style="cursor: pointer; color: #2f80ed;">
      疲劳评估设置 ›
    </article>

    <article @click="handleLogout" style="cursor: pointer; color: #e11d48;">
      退出登录
    </article>
  </section>
</template>
