<script setup lang="ts">
import { computed } from 'vue'

interface Props {
  seq: number
  type: string
  name: string
  selected?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  selected: false,
})

const emit = defineEmits<{
  click: []
}>()

const typeColors: Record<string, { fill: string; glow: string }> = {
  attraction: { fill: '#3b6cf7', glow: 'rgba(59, 108, 247, 0.4)' },
  restaurant: { fill: '#f97316', glow: 'rgba(249, 115, 22, 0.4)' },
  hotel: { fill: '#a855f7', glow: 'rgba(168, 85, 247, 0.4)' },
  shopping: { fill: '#ec4899', glow: 'rgba(236, 72, 153, 0.4)' },
  entertainment: { fill: '#22c55e', glow: 'rgba(34, 197, 94, 0.4)' },
  transport: { fill: '#3b82f6', glow: 'rgba(59, 130, 246, 0.4)' },
  default: { fill: '#6b778c', glow: 'rgba(107, 119, 140, 0.4)' },
}

const color = computed(() => {
  return typeColors[props.type] || typeColors.default
})
</script>

<template>
  <div
    class="marker-wrapper group cursor-pointer"
    @click="emit('click')"
  >
    <!-- Pulse ring for selected -->
    <div
      v-if="selected"
      class="absolute inset-0 animate-ping rounded-full opacity-75"
      :style="{ background: color.glow }"
    />

    <!-- Marker circle -->
    <div
      class="relative flex h-8 w-8 items-center justify-center rounded-full text-xs font-bold text-white shadow-lg transition-transform duration-200 group-hover:scale-110"
      :style="{
        background: color.fill,
        boxShadow: selected ? `0 0 0 4px ${color.glow}, 0 2px 8px rgba(0,0,0,0.2)` : '0 2px 8px rgba(0,0,0,0.2)',
      }"
    >
      {{ seq }}
    </div>

    <!-- Tooltip on hover -->
    <div class="pointer-events-none absolute bottom-full left-1/2 mb-2 -translate-x-1/2 opacity-0 transition-opacity duration-150 group-hover:opacity-100">
      <div class="whitespace-nowrap rounded-lg bg-surface-900 px-2.5 py-1.5 text-xs font-medium text-white shadow-lg">
        {{ name }}
        <div class="absolute left-1/2 top-full -translate-x-1/2 border-4 border-transparent border-t-surface-900" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.marker-wrapper {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
}

.animate-ping {
  animation: ping 1.5s cubic-bezier(0, 0, 0.2, 1) infinite;
}

@keyframes ping {
  75%,
  100% {
    transform: translate(-50%, -50%) scale(2);
    opacity: 0;
  }
}
</style>
