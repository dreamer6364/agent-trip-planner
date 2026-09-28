<script setup lang="ts">
defineProps<{
  disabled?: boolean
}>()
</script>

<template>
  <div
    :class="[
      'flex items-center justify-center w-6 h-8 rounded cursor-grab active:cursor-grabbing text-surface-400 hover:text-surface-600 dark:hover:text-surface-300 transition-all duration-200 select-none',
      disabled ? 'opacity-30 cursor-not-allowed' : 'hover:bg-surface-100 dark:hover:bg-surface-700'
    ]"
    :draggable="!disabled"
  >
    <svg class="w-4 h-4" viewBox="0 0 16 16" fill="currentColor">
      <circle cx="5" cy="3" r="1.5" />
      <circle cx="11" cy="3" r="1.5" />
      <circle cx="5" cy="8" r="1.5" />
      <circle cx="11" cy="8" r="1.5" />
      <circle cx="5" cy="13" r="1.5" />
      <circle cx="11" cy="13" r="1.5" />
    </svg>
  </div>
</template>
