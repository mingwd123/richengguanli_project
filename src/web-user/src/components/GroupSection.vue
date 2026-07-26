<script setup lang="ts">
defineProps<{
  groups: { name: string; items: number }[]
}>()
</script>

<template>
  <section class="group-panel">
    <div class="section-head">
      <h2>任务模块</h2>
    </div>
    <div v-if="groups.length === 0" class="muted">暂无数据</div>
    <div v-for="g in groups" :key="g.name" class="group-block">
      <div class="group-title">
        <span>{{ g.name }}</span>
        <em>{{ g.items }}</em>
      </div>
    </div>
  </section>
</template>
