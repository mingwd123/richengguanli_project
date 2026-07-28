<script setup>
import { onMounted, ref } from 'vue'
import { useAdminStore } from '../stores/admin'
import DataTable from '../components/DataTable.vue'

const store = useAdminStore()
const showDetail = ref(false)
const detailLog = ref(null)

const columns = [
  { key: 'id', label: 'ID' },
  { key: 'userId', label: '用户 ID' },
  { key: 'featureType', label: '功能类型' },
  { key: 'status', label: '状态' },
  { key: 'createdAt', label: '调用时间' },
]

const featureTypeOptions = [
  { value: '', label: '全部功能' },
  { value: 'schedule_parse', label: '日程解析' },
  { value: 'team_task_breakdown', label: '任务拆解' },
  { value: 'daily_plan', label: '每日计划' },
  { value: 'task_description_optimize', label: '描述优化' },
]

onMounted(async () => {
  await fetchLogs({ page: 1 })
})

async function fetchLogs(params = {}) {
  store.page = { list: [], total: 0, page: 1, size: 20 }
  await store.fetchAiUsageLogs({
    page: params.page || 1,
    size: params.size || 20,
    userId: localUserId.value,
    featureType: localFeatureType.value,
    status: localStatus.value,
    dateFrom: localDateFrom.value,
    dateTo: localDateTo.value,
  })
}

const localUserId = ref('')
const localFeatureType = ref('')
const localStatus = ref('')
const localDateFrom = ref('')
const localDateTo = ref('')

function onSearch() {
  fetchLogs({ page: 1 })
}

function onReset() {
  localUserId.value = ''
  localFeatureType.value = ''
  localStatus.value = ''
  localDateFrom.value = ''
  localDateTo.value = ''
  fetchLogs({ page: 1 })
}

function onPageChange(page) {
  fetchLogs({ page })
}

function onSizeChange(size) {
  fetchLogs({ page: 1, size })
}

function viewDetail(row) {
  detailLog.value = row
  showDetail.value = true
}

function closeDetail() {
  showDetail.value = false
  detailLog.value = null
}
</script>

<template>
  <div class="resource-page">
    <header class="topbar">
      <div>
        <h1>AI 调用记录</h1>
        <p>查看所有 AI 功能的调用日志，支持搜索和筛选。</p>
      </div>
      <button class="primary" @click="fetchLogs({ page: 1 })">刷新</button>
    </header>

    <section class="stats">
      <article><span>总数</span><strong>{{ store.page.total }}</strong></article>
      <article><span>成功</span><strong>{{ store.page.list.filter(r => r.status === 'success').length }}</strong></article>
      <article><span>失败</span><strong>{{ store.page.list.filter(r => r.status === 'failed').length }}</strong></article>
    </section>

    <!-- 自定义筛选栏 -->
    <div class="table-toolbar" style="background:#fff;border:1px solid #e5eaf2;border-radius:8px;padding:12px;margin-top:16px;display:flex;gap:8px;flex-wrap:wrap;align-items:center">
      <input v-model="localUserId" placeholder="用户 ID" style="width:100px" />
      <select v-model="localFeatureType" style="width:130px">
        <option v-for="opt in featureTypeOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
      </select>
      <select v-model="localStatus" style="width:100px">
        <option value="">全部状态</option>
        <option value="success">成功</option>
        <option value="failed">失败</option>
      </select>
      <input type="date" v-model="localDateFrom" placeholder="开始日期" />
      <input type="date" v-model="localDateTo" placeholder="结束日期" />
      <button class="primary" @click="onSearch">查询</button>
      <button @click="onReset">重置</button>
    </div>

    <div class="data-table" style="margin-top:12px;background:#fff;border:1px solid #e5eaf2;border-radius:8px;">
      <div v-if="store.loading" class="loading" style="padding:40px;text-align:center">加载中...</div>
      <div v-else-if="store.page.list.length === 0" class="empty" style="padding:40px;text-align:center">暂无数据</div>
      <template v-else>
        <div class="table-row table-title" style="display:grid;grid-template-columns:60px 80px 110px 80px 1fr 80px;padding:12px 16px;border-bottom:1px solid #eef2f7;font-weight:700;font-size:13px">
          <span>ID</span><span>用户ID</span><span>功能类型</span><span>状态</span><span>错误信息</span><span>调用时间</span>
        </div>
        <div v-for="row in store.page.list" :key="row.id" class="table-row" style="display:grid;grid-template-columns:60px 80px 110px 80px 1fr 80px;padding:12px 16px;border-bottom:1px solid #eef2f7;align-items:center;cursor:pointer" @click="viewDetail(row)">
          <span>{{ row.id }}</span>
          <span>{{ row.userId ?? '-' }}</span>
          <span>{{ row.featureType }}</span>
          <span :class="row.status === 'success' ? 'tag blue' : 'tag danger'" style="display:inline-block;padding:1px 8px;border-radius:10px;font-size:11px;font-weight:700;text-align:center">
            {{ row.status === 'success' ? '成功' : '失败' }}
          </span>
          <span style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:12px;color:#718096">{{ row.errorMessage || '-' }}</span>
          <span style="font-size:12px;color:#718096">{{ row.createdAt }}</span>
        </div>
      </template>
    </div>

    <!-- 分页 -->
    <div class="pagination" v-if="store.page.total > 0" style="display:flex;align-items:center;gap:12px;justify-content:center;margin-top:16px">
      <span>共 {{ store.page.total }} 条</span>
      <button :disabled="store.page.page <= 1" @click="onPageChange(store.page.page - 1)">上一页</button>
      <span>第 {{ store.page.page }} / {{ Math.max(1, Math.ceil(store.page.total / store.page.size)) }} 页</span>
      <button :disabled="store.page.page >= Math.ceil(store.page.total / store.page.size)" @click="onPageChange(store.page.page + 1)">下一页</button>
      <select :value="store.page.size" @change="onSizeChange(Number($event.target.value))">
        <option :value="10">10条/页</option>
        <option :value="20">20条/页</option>
        <option :value="50">50条/页</option>
      </select>
    </div>

    <!-- 详情弹窗 -->
    <div v-if="showDetail && detailLog" class="modal-overlay" @click.self="closeDetail">
      <div class="detail-modal">
        <div class="modal-header">
          <h2>调用详情 (ID: {{ detailLog.id }})</h2>
          <button @click="closeDetail">&times;</button>
        </div>
        <div class="modal-body">
          <div class="detail-grid">
            <div class="detail-item"><span class="detail-label">ID</span><span class="detail-value">{{ detailLog.id }}</span></div>
            <div class="detail-item"><span class="detail-label">用户 ID</span><span class="detail-value">{{ detailLog.userId ?? '-' }}</span></div>
            <div class="detail-item"><span class="detail-label">功能类型</span><span class="detail-value">{{ detailLog.featureType }}</span></div>
            <div class="detail-item"><span class="detail-label">状态</span><span class="detail-value">{{ detailLog.status }}</span></div>
            <div class="detail-item" style="grid-column:1/-1"><span class="detail-label">输入内容</span><pre style="margin-top:4px;background:#f7fafc;padding:8px;border-radius:4px;font-size:12px;max-height:200px;overflow:auto;white-space:pre-wrap">{{ detailLog.inputText || '(无)' }}</pre></div>
            <div class="detail-item" style="grid-column:1/-1"><span class="detail-label">输出内容</span><pre style="margin-top:4px;background:#f7fafc;padding:8px;border-radius:4px;font-size:12px;max-height:200px;overflow:auto;white-space:pre-wrap">{{ detailLog.outputText || '(无)' }}</pre></div>
            <div class="detail-item" style="grid-column:1/-1"><span class="detail-label">错误信息</span><span class="detail-value">{{ detailLog.errorMessage || '(无)' }}</span></div>
            <div class="detail-item"><span class="detail-label">调用时间</span><span class="detail-value">{{ detailLog.createdAt }}</span></div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tag { display:inline-block;padding:1px 8px;border-radius:10px;font-size:11px;font-weight:700; }
.tag.blue { background:#ebf8ff;color:#2b6cb0; }
.tag.danger { background:#fff5f5;color:#c53030; }
.modal-overlay { position:fixed;inset:0;background:rgba(0,0,0,.4);z-index:1000;display:grid;place-items:center; }
.detail-modal { background:#fff;border-radius:8px;width:640px;max-height:80vh;overflow:auto; }
.modal-header { display:flex;align-items:center;justify-content:space-between;padding:16px 20px;border-bottom:1px solid #eef2f7; }
.modal-body { padding:16px 20px; }
.detail-grid { display:grid;gap:12px; }
.detail-item { display:grid;gap:4px; }
.detail-label { font-weight:700;font-size:12px;color:#718096; }
.detail-value { font-size:14px; }
</style>
