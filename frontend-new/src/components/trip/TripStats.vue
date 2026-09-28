<script setup lang="ts">
import type { VersionStats } from '@/api/types'

defineProps<{
  stats: Partial<VersionStats>
}>()

function formatDuration(minutes?: number): string {
  if (!minutes) return '0h'
  const h = Math.floor(minutes / 60)
  const m = minutes % 60
  if (h === 0) return `${m}m`
  if (m === 0) return `${h}h`
  return `${h}h ${m}m`
}

const statItems = (stats: Partial<VersionStats>) => [
  {
    label: '总时长',
    value: formatDuration(stats.totalDurationMin),
    icon: 'M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z',
    color: 'text-brand-600 dark:text-brand-400',
    bg: 'bg-brand-50 dark:bg-brand-900/20',
  },
  {
    label: '景点',
    value: stats.placeCount ? Object.values(stats.placeCount).reduce((a, b) => a + b, 0) : 0,
    icon: 'M17.657 16.657L13.414 20.9a1.998 1.998 0 01-2.827 0l-4.244-4.243a8 8 0 1111.314 0z',
    color: 'text-accent-600 dark:text-accent-400',
    bg: 'bg-accent-50 dark:bg-accent-900/20',
  },
  {
    label: '交通',
    value: formatDuration(stats.transitDurationMin),
    icon: 'M8 17h.01M16 17h.01M3 11l1.5-5A2 2 0 016.4 4h11.2a2 2 0 011.9 1.4L21 11M3 11h18',
    color: 'text-surface-600 dark:text-surface-400',
    bg: 'bg-surface-100 dark:bg-surface-700/50',
  },
  {
    label: '游览',
    value: formatDuration(stats.visitDurationMin),
    icon: 'M15 12a3 3 0 11-6 0 3 3 0 016 0z M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z',
    color: 'text-success-600 dark:text-success-400',
    bg: 'bg-success-50 dark:bg-success-900/20',
  },
]
</script>

<template>
  <div class="grid grid-cols-2 lg:grid-cols-4 gap-3">
    <div
      v-for="(item, index) in statItems(stats)"
      :key="index"
      class="flex items-center gap-3 p-3 rounded-xl bg-white dark:bg-surface-800 border border-surface-100 dark:border-surface-700 shadow-sm"
    >
      <div :class="['flex items-center justify-center w-10 h-10 rounded-lg', item.bg]">
        <svg :class="['w-5 h-5', item.color]" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" :d="item.icon" />
        </svg>
      </div>
      <div>
        <p class="text-xs text-surface-500 dark:text-surface-400">{{ item.label }}</p>
        <p class="text-lg font-bold text-surface-900 dark:text-white">{{ item.value }}</p>
      </div>
    </div>
  </div>
</template>
