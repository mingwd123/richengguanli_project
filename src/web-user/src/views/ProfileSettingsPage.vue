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
      <label>
        头像 URL
        <input v-model="store.profileForm.avatarUrl" type="url" placeholder="https://example.com/avatar.jpg" />
      </label>
      <div v-if="store.profileForm.avatarUrl" class="avatar-preview">
        <img :src="store.profileForm.avatarUrl" alt="头像预览" />
      </div>
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

    <hr style="margin: 24px 0; border: none; border-top: 1px solid #eef2f7;" />
    <h2>AI 数据记录</h2>
    <form @submit.prevent="store.toggleAiRecord()">
      <label class="toggle-row">
        <span>允许记录 AI 调用数据</span>
        <input type="checkbox" v-model="store.aiRecordEnabled" @change="store.toggleAiRecord()" />
      </label>
      <p class="hint">关闭后，AI 调用不再保存输入/输出内容，仅保留调用记录。</p>
      <div class="form-actions">
        <button type="button" @click="goBack">返回</button>
      </div>
    </form>
  </section>
</template>
