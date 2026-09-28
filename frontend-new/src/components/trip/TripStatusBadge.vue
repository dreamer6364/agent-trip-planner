<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  status: string
}>()

const statusConfig = computed(() => {
  const configs: Record<string, { label: string; dotClass: string; badgeClass: string; pulse: boolean }> = {
    draft: {
      label: '草稿',
      dotClass: 'bg-surface-400',
      badgeClass: 'bg-surface-100 text-surface-600 dark:bg-surface-700 dark:text-surface-400',
      pulse: false,
    },
    planning: {
      label: '规划中',
      dotClass: 'bg-blue-500',
      badgeClass: 'bg-blue-50 text-blue-700 dark:bg-blue-900/30 dark:text-blue-400',
      pulse: true,
    },
    planned: {
      label: '已规划',
      dotClass: 'bg-blue-500',
      badgeClass: 'bg-blue-50 text-blue-700 dark:bg-blue-900/30 dark:text-blue-400',
      pulse: false,
    },
    completed: {
      label: '已完成',
      dotClass: 'bg-success-500',
      badgeClass: 'bg-success-50 text-success-700 dark:bg-success-900/30 dark:text-success-400',
      pulse: false,
    },
    archived: {
      label: '已归档',
      dotClass: 'bg-surface-400',
      badgeClass: 'bg-surface-100 text-surface-500 dark:bg-surface-700 dark:text-surface-500',
      pulse: false,
    },
  }
  return configs[props.status] ?? configs.draft
})
</script>

<template>
  <span
    :class="[
      'inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-medium backdrop-blur-sm',
      statusConfig.badgeClass
    ]"
  >
    <span class="relative flex h-2 w-2">
      <span
        v-if="statusConfig.pulse"
        :class="['absolute inline-flex h-full w-full rounded-full opacity-75 animate-ping', statusConfig.dotClass]"
      />
      <span :class="['relative inline-flex rounded-full h-2 w-2', statusConfig.dotClass]" />
    </span>
    {{ statusConfig.label }}
  </span>
</template>
