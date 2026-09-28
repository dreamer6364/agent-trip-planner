<script setup lang="ts">
import { computed } from 'vue'

interface Props {
  label: string
  status: 'pending' | 'active' | 'completed' | 'failed'
  icon: string
  isLast?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  isLast: false,
})

const circleClasses = computed(() => {
  const base = 'relative z-10 flex h-12 w-12 items-center justify-center rounded-full border-2 text-lg font-bold transition-all duration-500'
  const stateMap: Record<string, string> = {
    pending: 'border-surface-200 bg-white text-surface-400 dark:border-surface-600 dark:bg-surface-800 dark:text-surface-500',
    active: 'border-brand-400 bg-brand-50 text-brand-600 shadow-lg shadow-brand-500/30 dark:bg-brand-900/30 dark:text-brand-400',
    completed: 'border-success-400 bg-success-50 text-success-600 dark:bg-success-900/30 dark:text-success-400',
    failed: 'border-danger-400 bg-danger-50 text-danger-600 dark:bg-danger-900/30 dark:text-danger-400',
  }
  return `${base} ${stateMap[props.status]}`
})

const iconContent = computed(() => {
  if (props.status === 'completed') return '✓'
  if (props.status === 'failed') return '✕'
  return props.icon
})

const labelClasses = computed(() => {
  const base = 'mt-2 text-xs font-medium text-center transition-colors duration-300'
  const stateMap: Record<string, string> = {
    pending: 'text-surface-400 dark:text-surface-500',
    active: 'text-brand-600 dark:text-brand-400',
    completed: 'text-success-600 dark:text-success-400',
    failed: 'text-danger-600 dark:text-danger-400',
  }
  return `${base} ${stateMap[props.status]}`
})

const lineClasses = computed(() => {
  const base = 'absolute top-6 left-1/2 h-0.5 w-full -translate-y-1/2'
  const stateMap: Record<string, string> = {
    pending: 'bg-surface-200 dark:bg-surface-600',
    active: 'bg-gradient-to-r from-success-400 to-surface-200',
    completed: 'bg-success-400',
    failed: 'bg-danger-300',
  }
  return `${base} ${stateMap[props.status]}`
})
</script>

<template>
  <div class="flex flex-col items-center relative">
    <div class="flex items-center">
      <div
        :class="circleClasses"
        :style="status === 'active' ? 'animation: pulse-ring 2s ease-in-out infinite' : ''"
      >
        <span class="text-sm">{{ iconContent }}</span>
        <span
          v-if="status === 'active'"
          class="absolute inset-0 rounded-full border-2 border-brand-400 animate-ping opacity-30"
        />
      </div>
    </div>
    <span :class="labelClasses">{{ label }}</span>
    <div
      v-if="!isLast"
      :class="lineClasses"
    />
  </div>
</template>

<style scoped>
@keyframes pulse-ring {
  0%, 100% {
    box-shadow: 0 0 0 0 rgba(59, 108, 247, 0.4);
  }
  50% {
    box-shadow: 0 0 0 12px rgba(59, 108, 247, 0);
  }
}
</style>
