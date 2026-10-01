<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { SolverMeta } from '@/api/types'

interface Props {
  stats: SolverMeta
}

const props = defineProps<Props>()

const { t } = useI18n()

const statItems = computed(() => [
  {
    label: t('planningStats.algorithm'),
    value: props.stats.solverStatus || 'OR-Tools',
    icon: '🧮',
    color: 'text-brand-600 bg-brand-50',
  },
  {
    label: t('planningStats.solveTime'),
    value: props.stats.solveTimeMs != null
      ? props.stats.solveTimeMs < 1000
        ? `${props.stats.solveTimeMs}ms`
        : `${(props.stats.solveTimeMs / 1000).toFixed(2)}s`
      : '--',
    icon: '⏱️',
    color: 'text-accent-600 bg-accent-50',
  },
  {
    label: t('planningStats.iterations'),
    value: props.stats.iterations != null ? props.stats.iterations.toLocaleString() : '--',
    icon: '🔄',
    color: 'text-success-600 bg-success-50',
  },
  {
    label: t('planningStats.objective'),
    value: props.stats.objectiveValue != null ? props.stats.objectiveValue.toFixed(2) : '--',
    icon: '📊',
    color: 'text-warning-600 bg-warning-50',
  },
])
</script>

<template>
  <div class="card-base p-5">
    <h3 class="text-sm font-semibold text-surface-700 mb-4 flex items-center gap-2">
      <svg class="h-4 w-4 text-brand-500" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
        <path stroke-linecap="round" stroke-linejoin="round" d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z" />
      </svg>
      {{ t('planningStats.title') }}
    </h3>
    <div class="grid grid-cols-2 gap-3">
      <div
        v-for="item in statItems"
        :key="item.label"
        class="flex items-center gap-3 rounded-xl p-3 bg-surface-50 transition-colors hover:bg-surface-100"
      >
        <div
          :class="[
            'flex h-9 w-9 items-center justify-center rounded-lg text-sm',
            item.color,
          ]"
        >
          {{ item.icon }}
        </div>
        <div class="min-w-0">
          <p class="text-xs text-surface-500 truncate">{{ item.label }}</p>
          <p class="text-sm font-semibold text-surface-900 truncate">{{ item.value }}</p>
        </div>
      </div>
    </div>
  </div>
</template>
