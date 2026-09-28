<template>
  <div>
    <h1>BPM Platform</h1>
    <p v-if="health">Core Status: {{ health.status }}</p>
    <p v-else>Loading...</p>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import http from '../services/http'

const health = ref(null)

onMounted(async () => {
  try {
    const { data } = await http.get('/api/health')
    health.value = data
  } catch {
    health.value = { status: 'UNREACHABLE' }
  }
})
</script>
