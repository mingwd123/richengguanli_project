<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useAdminStore } from '../stores/admin'
import { columnLabels, dataLabels } from '../utils/analytics'
import RecordTable from './RecordTable.vue'

const props = defineProps({ userId: Number, modelValue: Boolean })
const emit = defineEmits(['update:modelValue'])
const store = useAdminStore()
const section = ref('profile')
const data = ref(null)
const profile = ref(null)
const page = ref(1)
const loading = ref(false)
const error = ref('')
const tabs = { profile: '用户资料', teams: '所属团队', fatigue: '疲劳记录', ai: 'AI 用量', risks: '风险记录', audit: '操作历史' }
const title = computed(() => `${profile.value?.nickname || '用户'} #${props.userId || ''}`)
let sequence = 0
let controller
async function load() {
  const current = ++sequence
  controller?.abort()
  if (!props.modelValue || !props.userId) return
  controller = new AbortController()
  loading.value = true
  error.value = ''
  data.value = null
  try {
    const path = section.value === 'profile' ? `/admin/users/${props.userId}`
      : `/admin/users/${props.userId}/workspace/${section.value}?page=${page.value}&size=20`
    const result = await store.request(path, { signal: controller.signal })
    if (current === sequence) {
      if (section.value === 'profile') profile.value = result
      else data.value = result
    }
  } catch (reason) { if (current === sequence && reason.name !== 'AbortError') error.value = reason.message }
  finally { if (current === sequence) loading.value = false }
}
watch(() => [props.userId, props.modelValue], () => {
  profile.value = null
  section.value = 'profile'
  page.value = 1
  load()
}, { immediate: true })
watch(section, () => { page.value = 1; load() })
onBeforeUnmount(() => { sequence++; controller?.abort() })
</script>

<template>
  <el-drawer :model-value="modelValue" :title="title" size="min(100%, 1060px)" @close="emit('update:modelValue', false)">
    <el-tabs v-model="section"><el-tab-pane v-for="(label, key) in tabs" :key="key" :label="label" :name="key" /></el-tabs>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-descriptions v-if="section === 'profile'" :column="1" border v-loading="loading">
      <el-descriptions-item v-for="(value, key) in profile" :key="key" :label="columnLabels[key] || key">{{ dataLabels[value] || value || '--' }}</el-descriptions-item>
    </el-descriptions>
    <template v-else>
      <RecordTable :rows="data?.items || []" :loading="loading" />
      <el-pagination :current-page="page" :page-size="20" :total="data?.total || 0" layout="total, prev, pager, next" @current-change="value => { page = value; load() }" />
    </template>
  </el-drawer>
</template>
