<script setup lang="ts">
import { computed } from 'vue'
import { formatDurationText, TRANSPORT_LABELS } from '@/utils/activity'
import { openNavigation, type NavPoint } from '@/utils/navigation'

const props = defineProps<{
  mode: string
  durationMin: number
  distanceMeters?: number
  fromName?: string
  toName?: string
  /** 导航起终点（含坐标；缺坐标时 util 降级 POI 名称导航），缺省不渲染为可点击 */
  from?: NavPoint
  to?: NavPoint
}>()

const modeConfig = computed(() => {
  const configs: Record<string, { icon: string; label: string; color: string; ring: string }> = {
    walk: {
      icon: 'M13 7a2 2 0 100-4 2 2 0 000 4zm-3 4l-2 6h3l1-4 2 2v4h2v-5l-2-2 1-3h-2l-1 1-1-3h2l1 2z',
      label: '步行',
      color: 'text-green-700 dark:text-green-400',
      ring: 'bg-green-50 dark:bg-green-900/30 border-green-200 dark:border-green-800',
    },
    transit: {
      icon: 'M8 17h.01M16 17h.01M3 11l1.5-5A2 2 0 016.4 4h11.2a2 2 0 011.9 1.4L21 11M3 11h18M3 11v6a1 1 0 001 1h1a1 1 0 001-1v-1h12v1a1 1 0 001 1h1a1 1 0 001-1v-6',
      label: '公交/地铁',
      color: 'text-blue-700 dark:text-blue-400',
      ring: 'bg-blue-50 dark:bg-blue-900/30 border-blue-200 dark:border-blue-800',
    },
    drive: {
      icon: 'M9 17a1 1 0 100-2 1 1 0 000 2zm6 0a1 1 0 100-2 1 1 0 000 2zM5 17H3v-4l2-5h10l2 5v4h-2m-10 0h10M5 8V6a1 1 0 011-1h8a1 1 0 011 1v2',
      label: '驾车',
      color: 'text-orange-700 dark:text-orange-400',
      ring: 'bg-orange-50 dark:bg-orange-900/30 border-orange-200 dark:border-orange-800',
    },
    bike: {
      icon: 'M5 17h2m10 0h2M7 17a3 3 0 110-6 3 3 0 010 6zm10 0a3 3 0 110-6 3 3 0 010 6zM5 11l3-6h4l2 6',
      label: '骑行',
      color: 'text-purple-700 dark:text-purple-400',
      ring: 'bg-purple-50 dark:bg-purple-900/30 border-purple-200 dark:border-purple-800',
    },
  }
  const key = props.mode && configs[props.mode] ? props.mode : 'walk'
  return configs[key]
})

const modeLabel = computed(() => TRANSPORT_LABELS[props.mode] || modeConfig.value.label)
const durationText = computed(() => formatDurationText(props.durationMin))

const canNav = computed(() => !!props.from && !!props.to)

function navigate() {
  if (!props.from || !props.to) return
  openNavigation(props.from, props.to, props.mode)
}

const distanceText = computed(() => {
  const m = Number(props.distanceMeters ?? 0) || 0
  if (m <= 0) return ''
  if (m < 1000) return `${Math.round(m)}m`
  return `${(m / 1000).toFixed(1)}km`
})
</script>

<template>
  <div class="relative flex items-center py-2 pl-8">
    <div class="absolute left-3 top-0 bottom-0 flex flex-col items-center">
      <div class="w-px flex-1 border-l border-dashed border-surface-300 dark:border-surface-600" />
    </div>

    <button
      v-if="canNav"
      type="button"
      class="relative z-10 ml-2 flex items-center gap-2 rounded-full border px-3 py-1.5 shadow-sm transition-all hover:-translate-y-0.5 hover:shadow-md focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-400 cursor-pointer"
      :class="modeConfig.ring"
      title="点击导航此路段"
      @click="navigate"
    >
      <span class="flex h-6 w-6 items-center justify-center rounded-full bg-white/80 dark:bg-surface-900/40" :class="modeConfig.color">
        <svg class="h-3.5 w-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" :d="modeConfig.icon" />
        </svg>
      </span>
      <span class="text-xs font-bold" :class="modeConfig.color">
        {{ modeLabel }}
      </span>
      <span class="text-xs font-semibold tabular-nums text-surface-700 dark:text-surface-300">
        {{ durationText }}
      </span>
      <span v-if="distanceText" class="text-xs text-surface-500 dark:text-surface-400">
        · {{ distanceText }}
      </span>
      <svg class="h-3 w-3 opacity-60" fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10 6H6a2 2 0 00-2 2v10a2 2 0 002 2h10a2 2 0 002-2v-4M14 4h6m0 0v6m0-6L10 14" />
      </svg>
    </button>
    <div
      v-else
      class="relative z-10 ml-2 flex items-center gap-2 rounded-full border px-3 py-1.5 shadow-sm"
      :class="modeConfig.ring"
    >
      <div class="flex h-6 w-6 items-center justify-center rounded-full bg-white/80 dark:bg-surface-900/40" :class="modeConfig.color">
        <svg class="h-3.5 w-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" :d="modeConfig.icon" />
        </svg>
      </div>
      <span class="text-xs font-bold" :class="modeConfig.color">
        {{ modeLabel }}
      </span>
      <span class="text-xs font-semibold tabular-nums text-surface-700 dark:text-surface-300">
        {{ durationText }}
      </span>
      <span v-if="distanceText" class="text-xs text-surface-500 dark:text-surface-400">
        · {{ distanceText }}
      </span>
    </div>
  </div>
</template>
