<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessageBox, ElMessage } from 'element-plus'
import { useAdminStore } from '../stores/admin'
import { useTicketAdminStore } from '../stores/tickets'

const adminStore = useAdminStore()
const store = useTicketAdminStore()

const statusLabels = { open: '待处理', in_progress: '处理中', waiting_reporter: '待补充', resolved: '已解决', closed: '已关闭' }
const categoryLabels = { bug: '功能异常', display: '显示问题', data: '数据问题', account: '账号问题', question: '使用咨询', suggestion: '功能建议', other: '其他' }
const moduleLabels = { schedule: '日程', team_task: '团队任务', daily_progress: '每日进度', fatigue: '疲劳评估', notification: '通知提醒', ai: 'AI', account: '账号与设置', desktop: '桌面端', other: '其他' }
const priorityLabels = { low: '低', normal: '普通', high: '高', urgent: '紧急' }
const closeReasonLabels = {
  resolved: '已解决', duplicate: '重复问题', user_withdrawn: '用户撤回',
  insufficient_info: '信息不足', not_supported: '暂不支持', violation: '违规或无效内容', other: '其他',
}
const statusTagTypes = { open: 'info', in_progress: 'warning', waiting_reporter: 'warning', resolved: 'success', closed: 'info' }

const detailOpen = ref(false)
const replyForm = reactive({ mode: 'public', content: '', attachments: [] })
const statusForm = reactive({ status: '', note: '', resolution: '', fixedVersion: '', closeReason: '' })
const mergeForm = reactive({ sourceId: '', note: '' })
const busy = ref(false)
const uploading = ref(false)
const attachmentInput = ref(null)

const isSuperAdmin = computed(() => adminStore.isSuperAdmin)
const statusCountsText = computed(() => {
  const counts = store.counts || {}
  return `待处理 ${counts.openCount || 0} · 处理中 ${counts.inProgressCount || 0} · 待补充 ${counts.waitingCount || 0}`
})
const currentVersion = computed(() => Number(store.detail?.version ?? 0))

async function load(pageNumber) {
  await store.fetchList(pageNumber)
}

async function toggleEnabled(enabled) {
  const counts = statusCountsText.value
  const confirmMessage = enabled
    ? '确认开放问题反馈模块？'
    : `确认关闭问题反馈模块？当前 ${counts}。历史数据会保留，再开启后恢复可见。`
  try {
    await ElMessageBox.confirm(confirmMessage, '模块开关', { type: 'warning' })
  } catch { return }
  try {
    await store.updateSettings(enabled)
    ElMessage.success(enabled ? '模块已开放' : '模块已关闭，历史数据保留')
  } catch (error) {
    ElMessage.error(error.message || '开关更新失败')
  }
}

async function openDetail(row) {
  detailOpen.value = true
  replyForm.mode = 'public'
  replyForm.content = ''
  replyForm.attachments = []
  resetStatusForm()
  mergeForm.sourceId = ''
  mergeForm.note = ''
  await refreshDetail(row.id)
}

async function refreshDetail(id) {
  await Promise.all([store.fetchDetail(id), store.fetchMessages(id), store.fetchEvents(id)])
  await preloadAttachmentUrls()
}

async function preloadAttachmentUrls() {
  const ids = [
    ...(store.detail?.attachments || []).map(item => item.id),
    ...store.messages.flatMap(message => (message.attachments || []).map(item => item.id)),
  ]
  await Promise.all(ids.map(id => store.loadAttachmentUrl(id)))
}

function resetStatusForm() {
  statusForm.status = store.detail?.status || ''
  statusForm.note = ''
  statusForm.resolution = ''
  statusForm.fixedVersion = ''
  statusForm.closeReason = statusForm.status === 'closed' ? (store.detail?.closeReason || '') : ''
}

async function pickAttachment(event) {
  const files = Array.from(event.target.files ?? [])
  event.target.value = ''
  for (const file of files) {
    if (replyForm.attachments.length >= 5) { ElMessage.warning('每条回复最多 5 张截图'); break }
    if (file.size > 10 * 1024 * 1024) { ElMessage.warning(`「${file.name}」超过 10MB`); continue }
    uploading.value = true
    try {
      const uploaded = await store.uploadAttachment(file)
      replyForm.attachments.push({ id: uploaded.id, name: file.name })
    } catch (error) {
      ElMessage.error(error.message || '截图上传失败')
    } finally {
      uploading.value = false
    }
  }
}

async function submitReply() {
  if (!replyForm.content.trim()) { ElMessage.warning('请填写回复内容'); return }
  busy.value = true
  try {
    await store.reply(store.detail.id, {
      content: replyForm.content.trim(),
      internal: replyForm.mode === 'internal',
      attachmentIds: replyForm.attachments.map(item => item.id),
    })
    await preloadAttachmentUrls()
    replyForm.content = ''
    replyForm.attachments = []
    ElMessage.success(replyForm.mode === 'internal' ? '内部备注已保存' : '公开回复已发送')
  } catch (error) {
    ElMessage.error(error.message || '回复失败')
  } finally {
    busy.value = false
  }
}

async function applyStatus() {
  const payload = {
    status: statusForm.status,
    note: statusForm.note.trim(),
    resolution: statusForm.resolution.trim(),
    fixedVersion: statusForm.fixedVersion.trim(),
    closeReason: statusForm.status === 'closed' ? statusForm.closeReason : '',
    version: currentVersion.value,
  }
  busy.value = true
  try {
    await store.changeStatus(store.detail.id, payload)
    resetStatusForm()
    ElMessage.success('状态已更新')
  } catch (error) {
    if (error.code === 409) ElMessage.error('工单状态已被其他管理员更新，请刷新后重试')
    else ElMessage.error(error.message || '状态更新失败')
  } finally {
    busy.value = false
  }
}

async function applyField(action, payload, successMessage) {
  busy.value = true
  try {
    await store.updateField(store.detail.id, action, payload)
    ElMessage.success(successMessage)
  } catch (error) {
    if (error.code === 409) ElMessage.error('工单状态已被其他管理员更新，请刷新后重试')
    else ElMessage.error(error.message || '更新失败')
  } finally {
    busy.value = false
  }
}

async function toggleHide() {
  const hidden = Boolean(store.detail?.hiddenAt)
  try {
    let payload = { version: currentVersion.value }
    if (hidden) {
      await ElMessageBox.confirm('确认恢复该工单对用户可见？', '恢复工单', { type: 'warning' })
    } else {
      const { value } = await ElMessageBox.prompt('隐藏后用户无法再看到该工单，请填写原因：', '隐藏工单', { inputValidator: v => (v || '').trim().length >= 3 || '至少 3 个字' })
      payload = { ...payload, reason: value.trim() }
    }
    await store.hide(store.detail.id, !hidden, payload)
    ElMessage.success(hidden ? '已恢复可见' : '已隐藏')
  } catch (error) {
    if (error !== 'cancel' && error?.message) ElMessage.error(error.message)
  }
}

async function submitMerge() {
  const sourceId = Number(mergeForm.sourceId)
  if (!Number.isFinite(sourceId) || sourceId <= 0) { ElMessage.warning('请填写源工单数字 ID'); return }
  const source = store.list.find(item => item.id === sourceId)
  try {
    await ElMessageBox.confirm(
      `确认把「${source ? source.title : `#${sourceId}`}」合并入当前主工单「${store.detail.title}」？`,
      '合并工单', { type: 'warning' },
    )
  } catch { return }
  busy.value = true
  try {
    await store.merge(store.detail.id, { sourceId, note: mergeForm.note.trim() })
    ElMessage.success('已合并')
    mergeForm.sourceId = ''
    mergeForm.note = ''
  } catch (error) {
    ElMessage.error(error.message || '合并失败')
  } finally {
    busy.value = false
  }
}

async function unmergeTicket() {
  try {
    const { value } = await ElMessageBox.prompt('取消合并后源工单回到待处理，请填写原因：', '取消合并', { inputValidator: v => (v || '').trim().length >= 5 || '至少 5 个字' })
    busy.value = true
    await store.unmerge(store.detail.id, { note: value.trim() })
    ElMessage.success('已取消合并')
  } catch (error) {
    if (error !== 'cancel' && error?.message) ElMessage.error(error.message)
  } finally {
    busy.value = false
  }
}

async function refresh() {
  await Promise.all([store.loadSettings(), load(store.page)])
}

onMounted(refresh)
</script>

<template>
  <div class="admin-page">
    <div class="admin-page-head">
      <div>
        <h2>工单管理</h2>
        <p class="muted">{{ statusCountsText }}；用户端开关默认关闭，开放后用户才能访问问题反馈。</p>
      </div>
      <div class="ticket-switch">
        <span>模块开关</span>
        <el-switch
          :model-value="store.settings.enabled"
          :disabled="!isSuperAdmin"
          @change="toggleEnabled"
        />
        <small v-if="!isSuperAdmin" class="muted">仅超级管理员可切换</small>
      </div>
    </div>

    <el-card shadow="never" class="ticket-filter-card">
      <div class="ticket-filters">
        <el-input v-model="store.filters.keyword" placeholder="标题 / 工单编号" clearable style="width:200px" @keyup.enter="load(1)" />
        <el-select v-model="store.filters.status" placeholder="状态" clearable style="width:130px">
          <el-option v-for="(label, key) in statusLabels" :key="key" :label="label" :value="key" />
        </el-select>
        <el-select v-model="store.filters.category" placeholder="类型" clearable style="width:130px">
          <el-option v-for="(label, key) in categoryLabels" :key="key" :label="label" :value="key" />
        </el-select>
        <el-select v-model="store.filters.module" placeholder="模块" clearable style="width:130px">
          <el-option v-for="(label, key) in moduleLabels" :key="key" :label="label" :value="key" />
        </el-select>
        <el-select v-model="store.filters.priority" placeholder="优先级" clearable style="width:120px">
          <el-option v-for="(label, key) in priorityLabels" :key="key" :label="label" :value="key" />
        </el-select>
        <el-input v-model="store.filters.author" placeholder="提交者昵称/手机/邮箱" clearable style="width:180px" @keyup.enter="load(1)" />
        <el-date-picker v-model="store.filters.dateFrom" type="date" placeholder="开始日期" value-format="YYYY-MM-DD" style="width:140px" />
        <el-date-picker v-model="store.filters.dateTo" type="date" placeholder="结束日期" value-format="YYYY-MM-DD" style="width:140px" />
        <el-button type="primary" @click="load(1)">查询</el-button>
        <el-button @click="refresh">刷新</el-button>
      </div>
    </el-card>

    <el-card shadow="never">
      <el-table :data="store.list" v-loading="store.loading" @row-click="openDetail">
        <el-table-column prop="ticketNo" label="编号" width="120" />
        <el-table-column prop="title" label="标题" min-width="220" show-overflow-tooltip />
        <el-table-column label="状态" width="100">
          <template #default="{ row }"><el-tag :type="statusTagTypes[row.status]">{{ statusLabels[row.status] }}</el-tag></template>
        </el-table-column>
        <el-table-column label="优先级" width="90">
          <template #default="{ row }">{{ priorityLabels[row.priority] }}</template>
        </el-table-column>
        <el-table-column prop="authorName" label="提交者" width="120" show-overflow-tooltip />
        <el-table-column prop="assigneeName" label="处理人" width="140" show-overflow-tooltip />
        <el-table-column label="回复" width="70">
          <template #default="{ row }">{{ row.replyCount }}</template>
        </el-table-column>
        <el-table-column label="遇到" width="70">
          <template #default="{ row }">{{ row.reactionCount }}</template>
        </el-table-column>
        <el-table-column label="最后活动" width="160">
          <template #default="{ row }">{{ (row.lastActivityAt || '').slice(0, 16).replace('T', ' ') }}</template>
        </el-table-column>
        <template #empty>暂无工单</template>
      </el-table>
      <el-pagination
        layout="prev, pager, next, total"
        :total="store.total"
        :page-size="store.size"
        :current-page="store.page"
        @current-change="load"
      />
    </el-card>

    <el-dialog v-model="detailOpen" :title="store.detail ? `${store.detail.ticketNo} ${store.detail.title}` : '工单详情'" width="860px" top="4vh">
      <div v-if="store.detail" class="ticket-admin-detail">
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="状态">{{ statusLabels[store.detail.status] }}</el-descriptions-item>
          <el-descriptions-item label="优先级">{{ priorityLabels[store.detail.priority] }}</el-descriptions-item>
          <el-descriptions-item label="处理人">{{ store.detail.assigneeName || '未分配' }}</el-descriptions-item>
          <el-descriptions-item label="提交者">{{ store.detail.authorName }}</el-descriptions-item>
          <el-descriptions-item label="类型 / 模块">{{ categoryLabels[store.detail.category] }} / {{ moduleLabels[store.detail.module] }}</el-descriptions-item>
          <el-descriptions-item label="版本号">{{ store.detail.version }}</el-descriptions-item>
        </el-descriptions>

        <div class="ticket-admin-actions">
          <el-select v-model="statusForm.status" style="width:140px" @change="resetStatusForm">
            <el-option v-for="(label, key) in statusLabels" :key="key" :label="label" :value="key" />
          </el-select>
          <el-button :disabled="busy || statusForm.status === store.detail.status" @click="applyStatus">应用状态变更</el-button>
          <el-button :disabled="busy" @click="applyField('priority', { priority: store.detail.priority === 'urgent' ? 'high' : 'urgent', version: currentVersion }, '优先级已调整')">
            {{ store.detail.priority === 'urgent' ? '降为高' : '提为紧急' }}
          </el-button>
          <el-button :disabled="busy" @click="applyField('pinned', { pinned: !store.detail.pinned, version: currentVersion }, store.detail.pinned ? '已取消置顶' : '已置顶')">
            {{ store.detail.pinned ? '取消置顶' : '置顶' }}
          </el-button>
          <el-button :disabled="busy" @click="toggleHide">{{ store.detail.hiddenAt ? '恢复可见' : '隐藏' }}</el-button>
        </div>

        <el-input
          v-if="statusForm.status && statusForm.status !== store.detail.status"
          v-model="statusForm.note"
          type="textarea"
          :rows="2"
          class="ticket-status-note"
          :placeholder="statusForm.status === 'resolved' ? '解决摘要（至少 10 字，公开可见）' : statusForm.status === 'closed' ? '关闭说明（部分原因必填）' : statusForm.status === 'waiting_reporter' ? '需要用户补充什么信息（至少 5 字）' : '备注（可选）'"
        />
        <el-input
          v-if="statusForm.status === 'resolved' && statusForm.status !== store.detail.status"
          v-model="statusForm.fixedVersion"
          placeholder="修复版本（可选，例如 v2.3.0）"
          class="ticket-status-note"
        />
        <el-select
          v-if="statusForm.status === 'closed' && statusForm.status !== store.detail.status"
          v-model="statusForm.closeReason"
          placeholder="关闭原因（必选）"
          class="ticket-status-note"
        >
          <el-option v-for="(label, key) in closeReasonLabels" :key="key" :label="label" :value="key" />
        </el-select>

        <el-divider content-position="left">原文</el-divider>
        <p class="ticket-pre">{{ store.detail.description }}</p>
        <p v-if="store.detail.steps" class="ticket-pre muted">复现步骤：{{ store.detail.steps }}</p>
        <div v-if="store.detail.attachments?.length" class="ticket-admin-attachments">
          <el-image
            v-for="attachment in store.detail.attachments"
            :key="attachment.id"
            :src="store.attachmentUrls.get(attachment.id)"
            :preview-src-list="[store.attachmentUrls.get(attachment.id)].filter(Boolean)"
            fit="cover"
            class="ticket-admin-thumb"
            hide-on-click-modal
          />
        </div>

        <el-divider content-position="left">处理操作</el-divider>
        <div class="ticket-admin-actions">
          <el-button :disabled="busy" @click="applyField('assignee', { assigneeAdminId: adminStore.profile?.id, version: currentVersion }, '已认领')">
            认领给我
          </el-button>
          <el-button :disabled="busy" @click="applyField('assignee', { assigneeAdminId: null, version: currentVersion }, '已清空处理人')">清空处理人</el-button>
        </div>
        <div class="ticket-admin-actions">
          <el-input v-model="mergeForm.sourceId" placeholder="重复工单的数字 ID" style="width:180px" />
          <el-input v-model="mergeForm.note" placeholder="合并说明（至少 5 字）" style="width:260px" />
          <el-button :disabled="busy" @click="submitMerge">合并入本工单</el-button>
          <el-button v-if="store.detail.duplicateOfId" :disabled="busy" @click="unmergeTicket">取消合并（本工单为源）</el-button>
        </div>

        <el-divider content-position="left">回复与内部备注</el-divider>
        <el-radio-group v-model="replyForm.mode" style="margin-bottom:8px">
          <el-radio-button value="public">公开回复</el-radio-button>
          <el-radio-button value="internal">内部备注</el-radio-button>
        </el-radio-group>
        <el-input v-model="replyForm.content" type="textarea" :rows="3" maxlength="3000" show-word-limit
          :placeholder="replyForm.mode === 'internal' ? '内部备注：仅管理端可见，不产生用户通知' : '公开回复：作者与关注者会收到通知'" />
        <div class="ticket-admin-actions">
          <input type="file" accept="image/jpeg,image/png,image/webp" multiple @change="pickAttachment" />
          <small v-if="uploading" class="muted">上传中…</small>
          <span v-for="item in replyForm.attachments" :key="item.id" class="muted">📎{{ item.name }}</span>
        </div>
        <el-button type="primary" :disabled="busy || uploading" @click="submitReply">
          {{ replyForm.mode === 'internal' ? '保存内部备注' : '发送公开回复' }}
        </el-button>

        <el-divider content-position="left">讨论与备注（{{ store.messages.length }}）</el-divider>
        <div v-for="message in store.messages" :key="message.id" class="ticket-admin-message" :class="{ internal: message.visibility === 'internal' }">
          <header>
            <strong>{{ message.actorName }}</strong>
            <el-tag v-if="message.isAdmin" size="small">管理员</el-tag>
            <el-tag v-if="message.visibility === 'internal'" size="small" type="warning">内部</el-tag>
            <small class="muted">{{ (message.createdAt || '').slice(0, 16).replace('T', ' ') }}</small>
          </header>
          <p class="ticket-pre">{{ message.content }}</p>
        </div>

        <el-divider content-position="left">状态时间线</el-divider>
        <div v-for="(event, index) in store.events" :key="index" class="ticket-admin-event">
          <small class="muted">{{ (event.createdAt || '').slice(0, 16).replace('T', ' ') }}</small>
          <span>{{ event.actorName }} · {{ event.action }}
            <template v-if="event.toStatus"> → {{ statusLabels[event.toStatus] || event.toStatus }}</template>
            <template v-if="event.note">（{{ event.note }}）</template>
            <el-tag v-if="event.visibility === 'internal'" size="small" type="warning">内部</el-tag>
          </span>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<style scoped>
.admin-page { display: grid; gap: 14px; }
.admin-page-head { display: flex; justify-content: space-between; align-items: center; gap: 14px; flex-wrap: wrap; }
.ticket-switch { display: flex; align-items: center; gap: 8px; }
.ticket-filters { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
.ticket-admin-detail { display: grid; gap: 12px; }
.ticket-admin-actions { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
.ticket-status-note { max-width: 640px; }
.ticket-admin-detail :deep(.el-divider--horizontal) { margin: 12px 0 8px; }
.ticket-pre { white-space: pre-wrap; overflow-wrap: anywhere; margin: 0; }
.ticket-admin-attachments { display: flex; flex-wrap: wrap; gap: 8px; }
.ticket-admin-thumb { width: 120px; height: 84px; border-radius: 6px; }
.ticket-admin-message { border-top: 1px solid var(--admin-border, #ebeef5); padding: 8px 0; display: grid; gap: 4px; }
.ticket-admin-message.internal { background: rgba(230, 162, 60, .08); padding: 8px; border-radius: 6px; }
.ticket-admin-message header { display: flex; align-items: center; gap: 8px; }
.ticket-admin-message p { margin: 0; }
.ticket-admin-event { display: flex; gap: 10px; align-items: baseline; }
</style>
