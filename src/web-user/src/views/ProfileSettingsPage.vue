<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useAppStore } from '../stores/app'

const router = useRouter()
const store = useAppStore()

function goBack() {
  router.push('/profile')
}
</script>

<template>
  <section class="form-card" style="margin-top: 0;">
    <!-- 修改资料 -->
    <h2>修改资料</h2>
    <form @submit.prevent="store.updateProfile">
      <label>
        昵称
        <input v-model="store.profileForm.nickname" />
      </label>
      <div class="form-actions">
        <button type="button" @click="goBack">返回</button>
        <button class="primary">保存</button>
      </div>
    </form>

    <hr style="margin: 24px 0; border: none; border-top: 1px solid #eef2f7;" />

    <!-- 修改密码 -->
    <h2>修改密码</h2>
    <form @submit.prevent="store.changePassword">
      <label>
        旧密码
        <input v-model="store.passwordForm.oldPassword" type="password" />
      </label>
      <label>
        新密码
        <input v-model="store.passwordForm.newPassword" type="password" />
      </label>
      <label>
        确认新密码
        <input v-model="store.passwordForm.confirmPassword" type="password" />
      </label>
      <div class="form-actions">
        <button type="button" @click="goBack">返回</button>
        <button class="primary">保存</button>
      </div>
    </form>

    <hr style="margin: 24px 0; border: none; border-top: 1px solid #eef2f7;" />

    <!-- 时区设置 -->
    <h2>时区设置</h2>
    <form @submit.prevent="store.updateTimezone">
      <label>
        时区
        <input v-model="store.timezoneForm.timezone" placeholder="如 Asia/Shanghai" />
      </label>
      <div class="form-actions">
        <button type="button" @click="goBack">返回</button>
        <button class="primary">保存</button>
      </div>
    </form>
  </section>
</template>
