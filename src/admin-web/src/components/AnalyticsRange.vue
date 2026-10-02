<script setup>
import { computed, ref, watch } from 'vue'
import { Download, Refresh } from '@element-plus/icons-vue'
import { datasetLabels, presetRange, validRange } from '../utils/analytics'

const props = defineProps({ modelValue: { type: Array, required: true }, loading: Boolean, unit: { type: String, default: 'UTC' }, exportable: Boolean, datasets: { type: Array, default: () => [] }, exporting: Boolean })
const emit = defineEmits(['update:modelValue', 'refresh', 'export'])
const preset = ref('custom')
const custom = ref([...props.modelValue])
const invalid = computed(() => preset.value === 'custom' && !validRange(custom.value))
watch(() => props.modelValue, value => {
  custom.value = [...value]
  preset.value = ['1', '7', '30'].find(days => presetRange(Number(days)).every((date, index) => date === value[index])) || 'custom'
}, { immediate: true })
function select(value) {
  if (value !== 'custom') emit('update:modelValue', presetRange(Number(value)))
}
function apply(value) { if (validRange(value)) emit('update:modelValue', value) }
</script>

<template>
  <div class="analytics-toolbar">
    <el-radio-group v-model="preset" size="small" aria-label="时间范围" @change="select">
      <el-radio-button value="1">今日</el-radio-button>
      <el-radio-button value="7">近 7 天</el-radio-button>
      <el-radio-button value="30">近 30 天</el-radio-button>
      <el-radio-button value="custom">自定义</el-radio-button>
    </el-radio-group>
    <el-date-picker v-if="preset === 'custom'" v-model="custom" type="daterange" value-format="YYYY-MM-DD" format="YYYY-MM-DD"
      start-placeholder="开始日期" end-placeholder="结束日期" :clearable="false" size="small" @change="apply" />
    <span class="analytics-period">{{ modelValue[0] }} ~ {{ modelValue[1] }} · {{ unit }}</span>
    <span v-if="invalid" class="analytics-error-text">请选择不超过 366 天且不晚于今天的日期</span>
    <div class="analytics-toolbar-actions">
      <el-tooltip content="刷新数据"><el-button :icon="Refresh" circle :loading="loading" aria-label="刷新数据" @click="emit('refresh')" /></el-tooltip>
      <el-dropdown v-if="exportable" @command="command => emit('export', command)">
        <el-button :icon="Download" circle :loading="exporting" :disabled="loading" aria-label="导出 CSV" title="导出 CSV" />
        <template #dropdown><el-dropdown-menu>
          <template v-for="(dataset, index) in datasets" :key="dataset">
            <el-dropdown-item :command="{ index, all: true }">{{ datasetLabels[dataset] }} · 完整结果</el-dropdown-item>
            <el-dropdown-item :command="{ index, all: false }">{{ datasetLabels[dataset] }} · 当前页</el-dropdown-item>
          </template>
        </el-dropdown-menu></template>
      </el-dropdown>
    </div>
  </div>
</template>
