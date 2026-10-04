<script setup lang="ts">
import { computed } from 'vue'
import dayjs from 'dayjs'
import { useI18n } from 'vue-i18n'
import type { Trip, Activity } from '@/api/types'
import { normalizeActivities, formatTimePoint, formatDurationText, TRANSPORT_LABELS, resolveVersionStats } from '@/utils/activity'
import { openNavigation } from '@/utils/navigation'

interface Props {
  trip: Trip
}

const props = defineProps<Props>()

const { t } = useI18n()

const tripData = computed(() => props.trip)
const activities = computed(() => normalizeActivities(props.trip.latestVersion?.activities || []))

const formattedDateRange = computed(() => {
  const start = dayjs(props.trip.timeStart)
  const end = dayjs(props.trip.timeEnd)
  const startFull = t('shareView.dateFull', { y: start.year(), m: start.month() + 1, d: start.date() })
  if (start.isSame(end, 'day')) {
    return startFull
  }
  const endShort = t('shareView.dateShort', { m: end.month() + 1, d: end.date() })
  return `${startFull} - ${endShort}`
})

const stats = computed(() =>
  resolveVersionStats(props.trip.latestVersion?.stats, activities.value),
)

function formatTime(isoString?: string): string {
  return formatTimePoint(isoString) || '--:--'
}

/**
 * 到下一站的展示交通段（v1.31.0）：
 * - 休息行 → null（休息不展示距离/时间）
 * - 休息前行 → 聚合「休息前 → 休息后」直达段（真实路程由 rest 节点携带）
 * - 无下一站/无行程 → null（与旧版 travelDurationMin>0 门控一致，不估算）
 */
function travelLeg(index: number): { mode: string; minutes: number } | null {
  const list = activities.value
  const cur = list[index]
  if (!cur || cur.activityType === 'rest') return null
  let minutes = Number(cur.travelDurationMin ?? 0) || 0
  let mode = cur.transportMode || ''
  let j = index + 1
  while (j < list.length && list[j].activityType === 'rest') {
    const r = list[j]
    const m = Number(r.travelDurationMin ?? 0) || 0
    minutes += m
    if (m > 0) mode = r.transportMode || mode
    j += 1
  }
  if (j >= list.length) return null
  if (minutes <= 0) return null
  return { mode, minutes }
}

function hasLeg(index: number): boolean {
  return travelLeg(index) !== null
}

function legModeLabel(index: number): string {
  const leg = travelLeg(index)
  if (!leg) return ''
  return TRANSPORT_LABELS[leg.mode] || leg.mode
}

function legDurationText(index: number): string {
  const leg = travelLeg(index)
  return leg ? formatDurationText(leg.minutes) : ''
}

/** 下一站目标下标：跳过 rest 链后的第一个同天非 rest 活动；跨天/无目标返回 -1 */
function navTargetIndex(index: number): number {
  const list = activities.value
  const cur = list[index]
  if (!cur) return -1
  for (let j = index + 1; j < list.length; j++) {
    const n = list[j]
    if (Number(n.day || 1) !== Number(cur.day || 1)) return -1
    if (n.activityType !== 'rest') return j
  }
  return -1
}

/** 同天下一段交通可导航（v1.22.1 分享页与时间轴一致；v1.31.0 跳过 rest 链） */
function canNavNext(index: number): boolean {
  return navTargetIndex(index) >= 0
}

function navNext(index: number) {
  const list = activities.value
  const cur = list[index]
  const ti = navTargetIndex(index)
  if (!cur || ti < 0) return
  const target = list[ti]
  const leg = travelLeg(index)
  openNavigation(
    { lat: cur.lat, lng: cur.lng, name: cur.poiName },
    { lat: target.lat, lng: target.lng, name: target.poiName },
    (leg && leg.mode) || cur.transportMode,
  )
}

function getActivityTypeIcon(type: string): string {
  const iconMap: Record<string, string> = {
    ATTRACTION: '🏛️',
    RESTAURANT: '🍽️',
    HOTEL: '🏨',
    SHOPPING: '🛍️',
    ENTERTAINMENT: '🎭',
    TRANSPORT: '🚗',
    WALKING: '🚶',
    MEAL: '🍜',
    BREAK: '☕',
    rest: '☕',
  }
  return iconMap[type] || '📍'
}

const activityTypeKeys: Record<string, string> = {
  ATTRACTION: 'shareView.type.attraction',
  RESTAURANT: 'shareView.type.restaurant',
  HOTEL: 'activity.stay',
  SHOPPING: 'activity.shopping',
  ENTERTAINMENT: 'shareView.type.entertainment',
  TRANSPORT: 'activity.transit',
  WALKING: 'trip.walk',
  MEAL: 'activity.meal',
  BREAK: 'shareView.type.break',
  rest: 'shareView.type.break',
}

function getActivityTypeLabel(type: string): string {
  return t(activityTypeKeys[type] || 'shareView.type.activity')
}
</script>

<template>
  <div class="min-h-screen bg-surface-50">
    <div class="max-w-3xl mx-auto px-4 py-8">
      <div class="card-base p-6 sm:p-8 mb-6">
        <div class="flex items-start justify-between mb-4">
          <div>
            <h1 class="text-2xl sm:text-3xl font-bold text-surface-900 mb-2">
              {{ tripData.title }}
            </h1>
            <p class="text-sm text-surface-500">{{ formattedDateRange }}</p>
          </div>
          <span class="inline-flex items-center gap-1.5 rounded-full bg-brand-100 px-3 py-1 text-xs font-medium text-brand-700">
            <svg class="h-3 w-3" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
              <path stroke-linecap="round" stroke-linejoin="round" d="M3.055 11H5a2 2 0 012 2v1a2 2 0 002 2 2 2 0 012 2v2.945M8 3.935V5.5A2.5 2.5 0 0010.5 8h.5a2 2 0 012 2 2 2 0 104 0 2 2 0 012-2h1.064M15 20.488V18a2 2 0 012-2h3.064" />
            </svg>
            {{ t('share.public') }}
          </span>
        </div>

        <div class="rounded-xl bg-surface-50 p-4 mb-6">
          <p class="text-xs font-medium text-surface-500 mb-1">{{ t('shareView.description') }}</p>
          <p class="text-sm text-surface-700 leading-relaxed">{{ tripData.rawInput }}</p>
        </div>

        <div v-if="stats" class="grid grid-cols-2 sm:grid-cols-4 gap-3">
          <div class="rounded-xl bg-brand-50 p-3 text-center">
            <p class="text-lg font-bold text-brand-600">
              {{ stats.placeCount ? Object.values(stats.placeCount).reduce((a, b) => a + b, 0) : 0 }}
            </p>
            <p class="text-xs text-surface-500">{{ t('shareView.spotCount') }}</p>
          </div>
          <div class="rounded-xl bg-accent-50 p-3 text-center">
            <p class="text-lg font-bold text-accent-600">
              {{ stats.totalDurationMin ? Math.round(stats.totalDurationMin / 60 * 10) / 10 : 0 }}h
            </p>
            <p class="text-xs text-surface-500">{{ t('shareView.totalDuration') }}</p>
          </div>
          <div class="rounded-xl bg-success-50 p-3 text-center">
            <p class="text-lg font-bold text-success-600">
              {{ stats.visitDurationMin ? Math.round(stats.visitDurationMin / 60 * 10) / 10 : 0 }}h
            </p>
            <p class="text-xs text-surface-500">{{ t('shareView.visitDuration') }}</p>
          </div>
          <div class="rounded-xl bg-warning-50 p-3 text-center">
            <p class="text-lg font-bold text-warning-600">
              {{ stats.transitDurationMin ? Math.round(stats.transitDurationMin / 60 * 10) / 10 : 0 }}h
            </p>
            <p class="text-xs text-surface-500">{{ t('shareView.transitDuration') }}</p>
          </div>
        </div>
      </div>

      <div class="card-base p-6 sm:p-8">
        <h2 class="text-lg font-semibold text-surface-900 mb-6 flex items-center gap-2">
          <svg class="h-5 w-5 text-brand-500" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
            <path stroke-linecap="round" stroke-linejoin="round" d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" />
          </svg>
          {{ t('shareView.timeline') }}
        </h2>

        <div v-if="activities.length === 0" class="text-center py-12">
          <svg class="h-12 w-12 text-surface-300 mx-auto mb-3" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.5">
            <path stroke-linecap="round" stroke-linejoin="round" d="M9 20l-5.447-2.724A1 1 0 013 16.382V5.618a1 1 0 011.447-.894L9 7m0 13l6-3m-6 3V7m6 10l4.553 2.276A1 1 0 0021 18.382V7.618a1 1 0 00-.553-.894L15 4m0 13V4m0 0L9 7" />
          </svg>
          <p class="text-surface-500">{{ t('shareView.empty') }}</p>
        </div>

        <div v-else class="relative">
          <div class="absolute left-5 top-0 bottom-0 w-0.5 bg-surface-200" />

          <div
            v-for="(activity, index) in activities"
            :key="activity.id || index"
            class="relative flex gap-4 mb-6 last:mb-0"
          >
            <div class="relative z-10 flex h-10 w-10 items-center justify-center rounded-full bg-white border-2 border-surface-200 text-sm shadow-sm shrink-0">
              {{ index + 1 }}
            </div>

            <div class="flex-1 min-w-0 rounded-xl border border-surface-100 bg-white p-4 shadow-sm hover:shadow-md transition-shadow">
              <div class="flex items-start justify-between mb-2">
                <div class="flex items-center gap-2">
                  <span class="text-lg">{{ getActivityTypeIcon(activity.activityType) }}</span>
                  <div>
                    <h3 class="text-sm font-semibold text-surface-900">{{ activity.poiName }}</h3>
                    <p class="text-xs text-surface-500">{{ getActivityTypeLabel(activity.activityType) }}</p>
                  </div>
                </div>
                <span
                  :class="[
                    'inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium',
                    activity.priority === 'HIGH' && 'bg-danger-100 text-danger-700',
                    activity.priority === 'MEDIUM' && 'bg-warning-100 text-warning-700',
                    activity.priority === 'LOW' && 'bg-success-100 text-success-700',
                  ]"
                >
                  {{ activity.priority === 'HIGH' ? t('shareView.priority.must') : activity.priority === 'MEDIUM' ? t('shareView.priority.recommended') : t('shareView.priority.optional') }}
                </span>
              </div>

              <div class="flex items-center gap-4 text-xs text-surface-500">
                <span v-if="activity.scheduledStart" class="flex items-center gap-1">
                  <svg class="h-3.5 w-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                    <path stroke-linecap="round" stroke-linejoin="round" d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" />
                  </svg>
                  {{ formatTime(activity.scheduledStart) }}<template v-if="activity.scheduledEnd"> – {{ formatTime(activity.scheduledEnd) }}</template>
                </span>
                <span class="flex items-center gap-1">
                  <svg class="h-3.5 w-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                    <path stroke-linecap="round" stroke-linejoin="round" d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" />
                  </svg>
                  {{ formatDurationText(activity.durationMin) }}
                </span>
                <button
                  v-if="hasLeg(index) && canNavNext(index)"
                  type="button"
                  class="flex items-center gap-1 cursor-pointer rounded-full px-1.5 -mx-1.5 py-0.5 transition-colors hover:bg-brand-50 hover:text-brand-700 focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-400"
                  :title="t('shareView.navTitle')"
                  @click="navNext(index)"
                >
                  <svg class="h-3.5 w-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                    <path stroke-linecap="round" stroke-linejoin="round" d="M8 7h12m0 0l-4-4m4 4l-4 4m0 6H4m0 0l4 4m-4-4l4-4" />
                  </svg>
                  {{ legModeLabel(index) }} · {{ legDurationText(index) }}
                </button>
                <span
                  v-else-if="hasLeg(index)"
                  class="flex items-center gap-1"
                >
                  <svg class="h-3.5 w-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                    <path stroke-linecap="round" stroke-linejoin="round" d="M8 7h12m0 0l-4-4m4 4l-4 4m0 6H4m0 0l4 4m-4-4l4-4" />
                  </svg>
                  {{ legModeLabel(index) }} · {{ legDurationText(index) }}
                </span>
              </div>

              <p v-if="activity.notes" class="mt-2 text-xs text-surface-600 leading-relaxed">
                {{ activity.notes }}
              </p>
              <p v-if="activity.slogan" class="mt-1 text-xs text-brand-600 italic">
                "{{ activity.slogan }}"
              </p>
            </div>
          </div>
        </div>
      </div>

      <div class="mt-8 text-center">
        <p class="text-xs text-surface-400 flex items-center justify-center gap-1.5">
          <svg class="h-3.5 w-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
            <path stroke-linecap="round" stroke-linejoin="round" d="M13 10V3L4 14h7v7l9-11h-7z" />
          </svg>
          Shared via TripForge
        </p>
      </div>
    </div>
  </div>
</template>
