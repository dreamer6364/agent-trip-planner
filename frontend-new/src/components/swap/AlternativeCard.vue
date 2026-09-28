<script setup lang="ts">
import { computed } from 'vue'
import type { ActivityAlternative } from '@/api/types'

interface Props {
  alternative: ActivityAlternative
}

const props = defineProps<Props>()

const emit = defineEmits<{
  select: [alternative: ActivityAlternative]
}>()

const typeConfig: Record<string, { icon: string; color: string; bg: string }> = {
  attraction: { icon: 'ri-landscape-line', color: 'text-brand-600', bg: 'bg-brand-50' },
  restaurant: { icon: 'ri-restaurant-line', color: 'text-orange-600', bg: 'bg-orange-50' },
  hotel: { icon: 'ri-hotel-line', color: 'text-purple-600', bg: 'bg-purple-50' },
  shopping: { icon: 'ri-shopping-bag-line', color: 'text-pink-600', bg: 'bg-pink-50' },
  entertainment: { icon: 'ri-gamepad-line', color: 'text-green-600', bg: 'bg-green-50' },
  transport: { icon: 'ri-bus-line', color: 'text-blue-600', bg: 'bg-blue-50' },
  default: { icon: 'ri-map-pin-line', color: 'text-surface-600', bg: 'bg-surface-50' },
}

const currentType = computed(() => {
  return typeConfig[props.alternative.type] || typeConfig.default
})

const fullStars = computed(() => {
  if (!props.alternative.rating) return 0
  return Math.floor(props.alternative.rating)
})

const hasHalfStar = computed(() => {
  if (!props.alternative.rating) return false
  return props.alternative.rating % 1 >= 0.5
})

const emptyStars = computed(() => {
  if (!props.alternative.rating) return 5
  const remaining = 5 - fullStars.value - (hasHalfStar.value ? 1 : 0)
  return Math.max(0, remaining)
})

const distanceText = computed(() => {
  const meters = props.alternative.distanceFromOriginalMeters
  if (!meters) return null
  if (meters < 1000) return `${meters}m`
  return `${(meters / 1000).toFixed(1)}km`
})

const durationText = computed(() => {
  const min = props.alternative.durationMin
  if (!min) return null
  if (min < 60) return `${min}min`
  const h = Math.floor(min / 60)
  const m = min % 60
  return m > 0 ? `${h}h ${m}min` : `${h}h`
})

const travelTimeText = computed(() => {
  const min = props.alternative.travelTimeFromOriginalMin
  if (!min) return null
  if (min < 60) return `${min}min away`
  const h = Math.floor(min / 60)
  const m = min % 60
  return m > 0 ? `${h}h ${m}min away` : `${h}h away`
})

function handleClick() {
  emit('select', props.alternative)
}
</script>

<template>
  <div
    class="group relative cursor-pointer rounded-xl border border-surface-100 bg-white p-4 transition-all duration-200 hover:-translate-y-0.5 hover:border-brand-200 hover:shadow-glow"
    role="button"
    tabindex="0"
    @click="handleClick"
    @keydown.enter="handleClick"
  >
    <!-- Top Row: Icon + Name + Rating -->
    <div class="flex items-start gap-3">
      <div
        :class="[
          'flex h-10 w-10 flex-shrink-0 items-center justify-center rounded-xl transition-transform duration-200 group-hover:scale-110',
          currentType.bg,
        ]"
      >
        <i :class="[currentType.icon, currentType.color, 'text-xl']" />
      </div>
      <div class="min-w-0 flex-1">
        <div class="flex items-start justify-between gap-2">
          <p class="text-sm font-bold text-surface-900 group-hover:text-brand-700">
            {{ alternative.name }}
          </p>
          <div
            v-if="alternative.rating"
            class="flex flex-shrink-0 items-center gap-0.5"
          >
            <span
              v-for="i in fullStars"
              :key="'full-' + i"
              class="text-xs text-amber-400"
            >★</span>
            <span
              v-if="hasHalfStar"
              class="text-xs text-amber-400"
            >★</span>
            <span
              v-for="i in emptyStars"
              :key="'empty-' + i"
              class="text-xs text-surface-300"
            >☆</span>
            <span class="ml-1 text-xs font-medium text-surface-500">{{ alternative.rating }}</span>
          </div>
        </div>
        <div class="mt-1 flex flex-wrap items-center gap-2">
          <span
            :class="[
              'inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium',
              currentType.bg,
              currentType.color,
            ]"
          >
            <i :class="currentType.icon" />
            {{ alternative.type }}
          </span>
          <span v-if="durationText" class="inline-flex items-center gap-1 rounded-full bg-surface-100 px-2 py-0.5 text-xs font-medium text-surface-600">
            <i class="ri-time-line" />
            {{ durationText }}
          </span>
        </div>
      </div>
    </div>

    <!-- Distance & Travel Time -->
    <div
      v-if="distanceText || travelTimeText"
      class="mt-2.5 flex items-center gap-3 border-t border-surface-50 pt-2.5"
    >
      <span v-if="distanceText" class="flex items-center gap-1 text-xs text-surface-500">
        <i class="ri-route-line text-brand-400" />
        {{ distanceText }}
      </span>
      <span v-if="travelTimeText" class="flex items-center gap-1 text-xs text-surface-500">
        <i class="ri-walk-line text-success-500" />
        {{ travelTimeText }}
      </span>
    </div>

    <!-- Reason -->
    <p
      v-if="alternative.reason"
      class="mt-2.5 text-xs italic leading-relaxed text-surface-500"
    >
      "{{ alternative.reason }}"
    </p>

    <!-- Tags -->
    <div v-if="alternative.tags && alternative.tags.length > 0" class="mt-2.5 flex flex-wrap gap-1.5">
      <span
        v-for="tag in alternative.tags.slice(0, 3)"
        :key="tag"
        class="rounded-md bg-surface-50 px-1.5 py-0.5 text-[10px] font-medium text-surface-500"
      >
        {{ tag }}
      </span>
    </div>

    <!-- Select Button -->
    <div class="mt-3 flex justify-end">
      <button
        class="inline-flex items-center gap-1.5 rounded-lg bg-gradient-to-r from-brand-500 to-accent-500 px-3.5 py-1.5 text-xs font-semibold text-white shadow-sm transition-all duration-200 hover:from-brand-600 hover:to-accent-600 hover:shadow-md active:scale-95"
        @click.stop="handleClick"
      >
        <i class="ri-arrow-right-line" />
        Select
      </button>
    </div>
  </div>
</template>
