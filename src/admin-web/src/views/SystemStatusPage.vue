<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { useAdminStore } from '../stores/admin'

const store = useAdminStore()
const loading = ref(false)
const status = ref(null)
const error = ref('')
let active = true
let timer

const checkedAt = computed(() => status.value?.checkedAt ? new Date(status.value.checkedAt).toLocaleString('zh-CN') : '--')
const bytes = value => {
  if (!Number.isFinite(Number(value))) return '--'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let size = Number(value)
  let index = 0
  while (size >= 1024 && index < units.length - 1) { size /= 1024; index += 1 }
  return `${size.toFixed(index ? 1 : 0)} ${units[index]}`
}
async function load() {
  if (loading.value) return
  loading.value = true
  try {
    const data = await store.request('/admin/ops/system-status')
    if (active) { status.value = data; error.value = '' }
  } catch (reason) { if (active) error.value = reason.message || '系统状态加载失败' }
  finally { loading.value = false }
}
onMounted(() => { load(); timer = setInterval(load, 60000) })
onUnmounted(() => { active = false; clearInterval(timer) })
</script>

<template>
  <div class="analytics-page">
    <header class="topbar">
      <div><span class="analytics-eyebrow">系统运维</span><h1>系统状态</h1><p>只读检查，不触发备份、重启或其他运维操作。</p></div>
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
    </header>
    <el-alert v-if="error" :title="error" :description="status ? '以下为上次检查结果，当前状态尚未确认。' : ''" type="error" show-icon :closable="false" class="analytics-alert" />
    <el-skeleton v-if="loading && !status" :rows="10" animated />
    <template v-else-if="status">
      <el-alert v-if="status.alerts?.length" :title="`${status.alerts.length} 项需要关注`" type="warning" show-icon :closable="false">
        <template #default><ul class="system-alerts"><li v-for="alert in status.alerts" :key="alert">{{ alert }}</li></ul></template>
      </el-alert>
      <el-alert v-else-if="!error" title="当前检查项未发现异常" type="success" show-icon :closable="false" />
      <section class="system-status-grid">
        <article v-if="status.recentApi" class="system-status-card">
          <div class="analytics-section-heading"><h2>最近 15 分钟</h2><el-tag :type="status.recentApi.errors ? 'danger' : 'info'">{{ status.recentApi.errors }} 次 5xx</el-tag></div>
          <strong>{{ status.recentApi.requests }}<small> 次请求</small></strong>
          <p>平均 {{ status.recentApi.averageLatencyMs ?? '--' }} ms · 最大 {{ status.recentApi.maxLatencyMs ?? '--' }} ms</p>
          <p>ERROR {{ status.recentApi.errorLogs ?? '--' }} · WARN {{ status.recentApi.warningLogs ?? '--' }} · 本进程</p>
        </article>
        <article v-if="status.telemetry" class="system-status-card">
          <div class="analytics-section-heading"><h2>采集写入</h2><span>{{ status.telemetry.queued }} / {{ status.telemetry.capacity }} 排队</span></div>
          <strong>{{ status.telemetry.written }}<small> 次成功</small></strong>
          <p>失败 {{ status.telemetry.failed }} · 丢弃 {{ status.telemetry.dropped }} · 本进程累计</p>
          <p>最近成功 {{ status.telemetry.lastSuccessAt ? new Date(status.telemetry.lastSuccessAt).toLocaleString('zh-CN') : '--' }}</p>
        </article>
        <article class="system-status-card">
          <div class="analytics-section-heading"><h2>数据库</h2><el-tag :type="status.database.status === 'up' ? 'success' : 'danger'">{{ status.database.status === 'up' ? '正常' : '异常' }}</el-tag></div>
          <strong>{{ status.database.latencyMs ?? '--' }}<small> ms</small></strong>
          <p>最近一次 select 1 检查 · 活跃连接 {{ status.database.activeConnections ?? '--' }} / {{ status.database.maxConnections ?? '--' }}</p>
        </article>
        <article class="system-status-card">
          <div class="analytics-section-heading"><h2>API 累计</h2><el-tag :type="status.api.errorRate == null ? 'info' : Number(status.api.errorRate) > 5 ? 'danger' : 'success'">{{ status.api.errorRate == null ? '--' : Number(status.api.errorRate).toFixed(1) }}% 5xx</el-tag></div>
          <strong>{{ status.api.averageLatencyMs ?? '--' }}<small> ms 平均</small></strong>
          <p>{{ status.api.requests?.toLocaleString() || 0 }} 次请求 · 进程启动于 {{ status.api.since ? new Date(status.api.since).toLocaleString('zh-CN') : '--' }}</p>
        </article>
        <article class="system-status-card">
          <div class="analytics-section-heading"><h2>JVM 堆内存</h2><span>{{ status.heap.usedPercent ?? '--' }}%</span></div>
          <el-progress :percentage="Math.min(100, Number(status.heap.usedPercent || 0))" :status="Number(status.heap.usedPercent || 0) > 85 ? 'exception' : ''" :show-text="false" />
          <p>{{ bytes(status.heap.usedBytes) }} / {{ bytes(status.heap.totalBytes) }}</p>
        </article>
        <article class="system-status-card">
          <div class="analytics-section-heading"><h2>磁盘</h2><span>{{ status.disk.usedPercent ?? '--' }}%</span></div>
          <el-progress :percentage="Math.min(100, Number(status.disk.usedPercent || 0))" :status="Number(status.disk.usedPercent || 0) > 90 ? 'exception' : ''" :show-text="false" />
          <p>{{ bytes(status.disk.usedBytes) }} / {{ bytes(status.disk.totalBytes) }}</p>
        </article>
      </section>
      <section class="analytics-table-section">
        <div class="analytics-section-heading"><h2>备份状态</h2><span>只读</span></div>
        <div class="system-detail-grid">
          <div><span>状态</span><strong>{{ status.backup.status === 'up' ? '正常' : status.backup.status === 'overdue' ? '已过期' : '未配置 / 未知' }}</strong></div>
          <div><span>最近成功时间</span><strong>{{ status.backup.lastSuccessAt ? new Date(status.backup.lastSuccessAt).toLocaleString('zh-CN') : '--' }}</strong></div>
          <div><span>异地备份</span><strong>{{ status.backup.offsite === true ? '已确认' : status.backup.offsite === false ? '未确认' : '--' }}</strong></div>
          <div><span>监控采集</span><strong>{{ status.monitorStatus === 'current' ? `已接入 · ${new Date(status.monitorObservedAt).toLocaleString('zh-CN')}` : '未接入或不可用' }}</strong></div>
        </div>
      </section>
      <section v-if="status.containers?.length" class="analytics-table-section">
        <div class="analytics-section-heading"><h2>容器健康</h2><span>只读</span></div>
        <el-table :data="status.containers"><el-table-column prop="name" label="容器" /><el-table-column label="状态"><template #default="{ row }"><el-tag :type="row.status === 'up' ? 'success' : 'danger'">{{ row.status === 'up' ? '正常' : '未知 / 异常' }}</el-tag></template></el-table-column></el-table>
      </section>
      <p class="analytics-note">最后检查于 {{ checkedAt }}。系统状态由应用进程和可选的 APP_OPS_STATUS_FILE 采集文件提供；未接入项显示为未知，不推断为正常。</p>
    </template>
  </div>
</template>
