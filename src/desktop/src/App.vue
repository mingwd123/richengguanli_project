<script setup lang="ts">
import { onMounted } from 'vue'
import { useAppStore } from '@web/stores/app'
import DesktopTitleBar from './components/DesktopTitleBar.vue'
import DesktopPreferences from './components/DesktopPreferences.vue'
import { useDesktopShell } from './stores/desktopShell'

const store = useAppStore()
const shell = useDesktopShell()

onMounted(() => {
  shell.initialize()
  if (store.loggedIn) store.loadAll()
})
</script>

<template>
  <div class="desktop-root">
    <DesktopTitleBar />
    <div class="desktop-viewport">
      <router-view />
    </div>
    <DesktopPreferences />
  </div>
</template>
