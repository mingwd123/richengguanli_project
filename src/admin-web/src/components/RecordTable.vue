<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { columnLabels, dataLabels } from '../utils/analytics'

const props = defineProps({ rows: { type: Array, default: () => [] }, loading: Boolean })
const router = useRouter()
const columns = computed(() => [...new Set(props.rows.flatMap(row => Object.keys(row)))])
const destinations = { userId: ['/users', 'userId'], teamId: ['/teams', 'teamId'], taskId: ['/team-tasks', 'taskId'], usageLogId: ['/ai-logs', 'logId'] }
function open(key, row) {
  const [path, parameter] = destinations[key]
  router.push({ path, query: { [parameter]: row[key] } })
}
function value(value) {
  if (value == null) return '--'
  if (typeof value === 'boolean') return value ? '是' : '否'
  if (typeof value === 'object') return JSON.stringify(value)
  return dataLabels[value] || value
}
</script>

<template>
  <el-table :data="rows" v-loading="loading" empty-text="暂无记录" class="record-table">
    <el-table-column v-for="key in columns" :key="key" :label="columnLabels[key] || key" :min-width="key.endsWith('At') || key.endsWith('Time') ? 190 : 130" show-overflow-tooltip>
      <template #default="{ row }">
        <el-button v-if="destinations[key] && row[key]" link type="primary" @click="open(key, row)">#{{ row[key] }}</el-button>
        <span v-else>{{ value(row[key]) }}</span>
      </template>
    </el-table-column>
  </el-table>
</template>
