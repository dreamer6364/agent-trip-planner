<script setup lang="ts">
import AppNavbar from '@/components/layout/AppNavbar.vue'
</script>

<template>
  <div class="min-h-screen bg-surface-50 dark:bg-surface-900">
    <AppNavbar />
    <main>
      <router-view />
    </main>
  </div>
</template>
