<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { Activity } from '@/api/types'
import { getTypeTheme, priorityLabel, statusLabel } from '@/utils/mapTheme'

const { t, locale } = useI18n()

interface Props {
  activity: Activity | null
  visible: boolean
}

const props = defineProps<Props>()

const emit = defineEmits<{
  close: []
  focusTimeline: []
}>()

const currentType = computed(() => getTypeTheme(props.activity?.activityType))

const timeRange = computed(() => {
  if (!props.activity) return ''
  const start = props.activity.scheduledStart
  const end = props.activity.scheduledEnd
  if (!start || !end) return ''

  const formatTime = (iso: string) => {
    const d = new Date(iso)
    if (Number.isNaN(d.getTime())) {
      const m = String(iso).match(/^(\d{1,2}:\d{2})/)
      return m ? m[1] : String(iso)
    }
    return d.toLocaleTimeString(locale.value, { hour: '2-digit', minute: '2-digit', hour12: false })
  }

  return `${formatTime(start)} - ${formatTime(end)}`
})

const durationText = computed(() => {
  if (!props.activity?.durationMin) return ''
  const min = props.activity.durationMin
  if (min < 60) return t('map.durationMinutes', { n: min })
  const h = Math.floor(min / 60)
  const m = min % 60
  return m > 0 ? t('map.durationHoursMinutes', { h, m }) : t('map.durationHours', { h })
})

const ratingText = computed(() => {
  const r = Number(props.activity?.rating)
  return !Number.isNaN(r) && r > 0 ? `★ ${r.toFixed(1)}` : ''
})

const costText = computed(() => {
  const c = String(props.activity?.cost ?? '').trim()
  if (!c || c === '0') return ''
  return c.includes('¥') || c.includes('元') ? c : `¥${c}`
})
</script>

<template>
  <Transition name="panel-slide">
    <div
      v-if="visible && activity"
      class="absolute left-0 top-0 z-20 flex h-full w-72 flex-col bg-white/95 shadow-panel backdrop-blur-xl"
    >
      <!-- Header -->
      <div class="flex items-center justify-between border-b border-surface-100 px-4 py-3">
        <div class="flex items-center gap-2">
          <div
            :class="[
              'flex h-7 w-7 items-center justify-center rounded-lg',
              currentType.fill === '#3b6cf7' ? 'bg-brand-50' : 'bg-surface-100',
            ]"
            :style="{ background: currentType.fill + '1a' }"
          >
            <i :class="[currentType.icon, 'text-base']" :style="{ color: currentType.fill }" />
          </div>
          <h3 class="text-sm font-semibold text-surface-900">{{ t('map.details') }}</h3>
        </div>
        <button
          class="flex h-7 w-7 items-center justify-center rounded-lg text-surface-400 transition-colors hover:bg-surface-100 hover:text-surface-600"
          @click="emit('close')"
        >
          <svg class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
            <path stroke-linecap="round" stroke-linejoin="round" d="M6 18L18 6M6 6l12 12" />
          </svg>
        </button>
      </div>

      <!-- Content -->
      <div class="flex-1 overflow-y-auto px-4 py-4">
        <h4 class="text-lg font-bold leading-snug text-surface-900">{{ activity.poiName }}</h4>

        <div class="mt-2 flex items-center gap-2">
          <span
            class="inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium"
            :style="{ background: currentType.fill + '1a', color: currentType.fill }"
          >
            <i :class="currentType.icon" />
            {{ currentType.label }}
          </span>
          <span class="text-xs text-surface-400">#{{ activity.seq }}</span>
        </div>

        <p
          v-if="activity.slogan"
          class="mt-3 text-sm italic leading-relaxed text-surface-500"
        >
          「{{ activity.slogan }}」
        </p>

        <div class="mt-4 space-y-3">
          <div
            v-if="timeRange"
            class="flex items-center gap-3 rounded-xl bg-surface-50 p-3"
          >
            <div class="flex h-9 w-9 flex-shrink-0 items-center justify-center rounded-lg bg-brand-100">
              <i class="ri-time-line text-brand-600" />
            </div>
            <div>
              <p class="text-[10px] font-medium tracking-wider text-surface-400">{{ t('map.schedule') }}</p>
              <p class="text-sm font-semibold text-surface-800">{{ timeRange }}</p>
            </div>
          </div>

          <div
            v-if="durationText"
            class="flex items-center gap-3 rounded-xl bg-surface-50 p-3"
          >
            <div class="flex h-9 w-9 flex-shrink-0 items-center justify-center rounded-lg bg-accent-100">
              <i class="ri-timer-line text-accent-600" />
            </div>
            <div>
              <p class="text-[10px] font-medium tracking-wider text-surface-400">{{ t('map.duration') }}</p>
              <p class="text-sm font-semibold text-surface-800">{{ durationText }}</p>
            </div>
          </div>

          <div
            v-if="activity.priority"
            class="flex items-center gap-3 rounded-xl bg-surface-50 p-3"
          >
            <div class="flex h-9 w-9 flex-shrink-0 items-center justify-center rounded-lg bg-warning-100">
              <i class="ri-flag-line text-warning-500" />
            </div>
            <div>
              <p class="text-[10px] font-medium tracking-wider text-surface-400">{{ t('map.priority') }}</p>
              <p class="text-sm font-semibold text-surface-800">{{ priorityLabel(activity.priority) }}</p>
            </div>
          </div>

          <div
            v-if="activity.status"
            class="flex items-center gap-3 rounded-xl bg-surface-50 p-3"
          >
            <div class="flex h-9 w-9 flex-shrink-0 items-center justify-center rounded-lg bg-success-100">
              <i class="ri-check-double-line text-success-600" />
            </div>
            <div>
              <p class="text-[10px] font-medium tracking-wider text-surface-400">{{ t('map.status') }}</p>
              <p class="text-sm font-semibold text-surface-800">{{ statusLabel(activity.status) }}</p>
            </div>
          </div>

          <div
            v-if="ratingText || costText"
            class="flex items-center gap-3 rounded-xl bg-surface-50 p-3"
          >
            <div class="flex h-9 w-9 flex-shrink-0 items-center justify-center rounded-lg bg-warning-100">
              <i class="ri-star-line text-warning-500" />
            </div>
            <div class="flex min-w-0 gap-4">
              <div v-if="ratingText">
                <p class="text-[10px] font-medium tracking-wider text-surface-400">{{ t('map.rating') }}</p>
                <p class="text-sm font-semibold text-surface-800">{{ ratingText }}</p>
              </div>
              <div v-if="costText">
                <p class="text-[10px] font-medium tracking-wider text-surface-400">{{ t('map.perPerson') }}</p>
                <p class="text-sm font-semibold text-surface-800">{{ costText }}</p>
              </div>
            </div>
          </div>

          <div
            v-if="activity.notes"
            class="rounded-xl bg-surface-50 p-3"
          >
            <p class="mb-1 text-[10px] font-medium tracking-wider text-surface-400">{{ t('map.notes') }}</p>
            <p class="text-sm leading-relaxed text-surface-600">{{ activity.notes }}</p>
          </div>

          <div
            v-if="(activity as any).poiAddress"
            class="flex items-start gap-3 rounded-xl bg-surface-50 p-3"
          >
            <div class="flex h-9 w-9 flex-shrink-0 items-center justify-center rounded-lg bg-danger-100">
              <i class="ri-map-pin-2-line text-danger-500" />
            </div>
            <div class="min-w-0">
              <p class="text-[10px] font-medium tracking-wider text-surface-400">{{ t('map.address') }}</p>
              <p class="break-all text-sm text-surface-600">{{ (activity as any).poiAddress }}</p>
            </div>
          </div>
        </div>
      </div>

      <!-- Footer Action -->
      <div class="border-t border-surface-100 px-4 py-3">
        <button
          class="flex w-full items-center justify-center gap-2 rounded-xl bg-gradient-to-r from-brand-500 to-accent-500 px-4 py-2.5 text-sm font-semibold text-white shadow-sm transition-all hover:from-brand-600 hover:to-accent-600 hover:shadow-md active:scale-95"
          @click="emit('focusTimeline')"
        >
          <i class="ri-list-check-2" />
          {{ t('map.viewInTimeline') }}
        </button>
      </div>
    </div>
  </Transition>
</template>

<style scoped>
.panel-slide-enter-active,
.panel-slide-leave-active {
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
}

.panel-slide-enter-from,
.panel-slide-leave-to {
  transform: translateX(-100%);
  opacity: 0;
}
</style>
