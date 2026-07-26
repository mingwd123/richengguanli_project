<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAppStore } from './stores/app'

const router = useRouter()
const store = useAppStore()

onMounted(() => {
  if (store.loggedIn) {
    store.loadAll()
  }
})
</script>

<template>
  <router-view />
</template>
