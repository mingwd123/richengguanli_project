<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { BarChart, FunnelChart, LineChart, PieChart } from 'echarts/charts'
import { AriaComponent, GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { useAdminStore } from '../stores/admin'

echarts.use([BarChart, FunnelChart, LineChart, PieChart, GridComponent, LegendComponent, TooltipComponent, AriaComponent, CanvasRenderer])
const props = defineProps({
  title: { type: String, required: true }, rows: { type: Array, default: () => [] },
  series: { type: Array, default: () => [{ key: 'value', name: '数量' }] },
  category: { type: String, default: 'date' }, type: { type: String, default: 'line' }, note: { type: String, default: '' },
})
const emit = defineEmits(['select'])
const store = useAdminStore()
const canvas = ref(null)
let chart
let observer

function render() {
  if (!chart) return
  const styles = getComputedStyle(document.documentElement)
  const color = styles.getPropertyValue('--admin-secondary').trim()
  const border = styles.getPropertyValue('--admin-border-soft').trim()
  const palette = ['#19978a', '#477bd8', '#df9860', '#d86479', '#81918e']
  const categorical = props.type === 'pie' || props.type === 'funnel'
  const series = categorical
    ? [{ type: props.type, radius: ['38%', '65%'], top: 24, bottom: 40, left: '12%', width: '76%',
        label: { color, position: 'inside', formatter: '{c}' }, data: props.rows.map(row => ({ name: row[props.category], value: row.value })) }]
    : props.series.map((series, index) => ({
        name: series.name, type: series.type || props.type, yAxisIndex: series.axis || 0, connectNulls: false,
        showSymbol: props.rows.length < 15, symbolSize: 5, barMaxWidth: 40,
        lineStyle: { width: 2 }, itemStyle: { color: palette[index] },
        data: props.rows.map(row => row[series.key] ?? null),
      }))
  chart.setOption({
    color: palette, animation: false, backgroundColor: 'transparent', textStyle: { color, fontFamily: 'Microsoft YaHei, sans-serif' },
    aria: { enabled: true }, tooltip: { trigger: categorical ? 'item' : 'axis', confine: true },
    legend: { bottom: 0, type: 'scroll', textStyle: { color } },
    grid: { top: 30, bottom: 55, left: 12, right: 18, containLabel: true },
    ...(categorical ? {} : {
      xAxis: { type: 'category', data: props.rows.map(row => String(row[props.category]).replace(/^\d{4}-/, '')),
        axisLabel: { color, hideOverlap: true }, axisLine: { lineStyle: { color: border } } },
      yAxis: Array.from({ length: Math.max(0, ...props.series.map(series => series.axis || 0)) + 1 }, (_, index) => ({
        type: 'value', name: props.series.find(series => (series.axis || 0) === index)?.unit || '',
        axisLabel: { color }, splitLine: { show: index === 0, lineStyle: { color: border } },
      })),
    }),
    series,
  }, true)
}

onMounted(() => {
  chart = echarts.init(canvas.value)
  observer = new ResizeObserver(() => chart?.resize())
  observer.observe(canvas.value)
  chart.on('click', event => emit('select', props.rows[event.dataIndex], props.series[event.seriesIndex]?.key || 'value'))
  render()
})
watch(() => [props.rows, props.series, props.type, store.theme], async () => { await nextTick(); render() }, { deep: true })
onBeforeUnmount(() => { observer?.disconnect(); chart?.dispose(); chart = null })
</script>

<template>
  <section class="analytics-chart">
    <div class="analytics-section-heading"><h2>{{ title }}</h2><slot name="action" /></div>
    <div class="analytics-plot">
      <div ref="canvas" class="analytics-canvas" role="img" :aria-label="title" />
      <p v-if="!rows.length" class="analytics-empty">所选时间内暂无记录</p>
    </div>
    <p v-if="note" class="analytics-note">{{ note }}</p>
  </section>
</template>
