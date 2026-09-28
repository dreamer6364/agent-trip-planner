<script setup lang="ts">
import { computed } from 'vue'

interface Props {
  mode: string
  distance?: number
  duration?: number
}

const props = withDefaults(defineProps<Props>(), {
  distance: 0,
  duration: 0,
})

const modeConfig: Record<string, { icon: string; color: string; label: string }> = {
  walking: { icon: 'ri-walk-line', color: '#22c55e', label: 'Walking' },
  transit: { icon: 'ri-bus-line', color: '#3b82f6', label: 'Transit' },
  driving: { icon: 'ri-car-line', color: '#f97316', label: 'Driving' },
  cycling: { icon: 'ri-bike-line', color: '#a855f7', label: 'Cycling' },
}

const config = computed(() => {
  return modeConfig[props.mode] || modeConfig.walking
})

const distanceText = computed(() => {
  if (!props.distance) return ''
  if (props.distance < 1000) return `${props.distance}m`
  return `${(props.distance / 1000).toFixed(1)}km`
})

const durationText = computed(() => {
  if (!props.duration) return ''
  if (props.duration < 60) return `${props.duration}min`
  const h = Math.floor(props.duration / 60)
  const m = props.duration % 60
  return m > 0 ? `${h}h ${m}min` : `${h}h`
})
</script>

<template>
  <div class="route-line group pointer-events-auto absolute left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2">
    <!-- Animated dash line -->
    <svg width="120" height="4" class="overflow-visible">
      <line
        x1="0"
        y1="2"
        x2="120"
        y2="2"
        :stroke="config.color"
        stroke-width="3"
        stroke-linecap="round"
        stroke-dasharray="8 4"
        class="animate-dash"
      />
    </svg>

    <!-- Travel mode icon at midpoint -->
    <div
      class="absolute left-1/2 top-1/2 flex -translate-x-1/2 -translate-y-1/2 items-center gap-1 rounded-full px-2 py-0.5 shadow-sm transition-transform duration-200 group-hover:scale-110"
      :style="{ background: config.color + '20', color: config.color }"
    >
      <i :class="[config.icon, 'text-xs']" />
      <span v-if="durationText" class="text-[10px] font-semibold">{{ durationText }}</span>
    </div>

    <!-- Hover tooltip -->
    <div class="pointer-events-none absolute left-1/2 top-full mt-1 -translate-x-1/2 opacity-0 transition-opacity duration-150 group-hover:opacity-100">
      <div class="whitespace-nowrap rounded-lg bg-surface-900 px-2.5 py-1.5 text-[10px] font-medium text-white shadow-lg">
        <div class="flex items-center gap-1.5">
          <i :class="config.icon" />
          <span>{{ config.label }}</span>
          <span v-if="distanceText" class="text-surface-300">·</span>
          <span v-if="distanceText">{{ distanceText }}</span>
          <span v-if="durationText" class="text-surface-300">·</span>
          <span v-if="durationText">{{ durationText }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.animate-dash {
  animation: dashFlow 1.5s linear infinite;
}

@keyframes dashFlow {
  0% {
    stroke-dashoffset: 24;
  }
  100% {
    stroke-dashoffset: 0;
  }
}
</style>
