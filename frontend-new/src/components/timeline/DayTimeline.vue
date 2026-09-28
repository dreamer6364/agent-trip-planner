<script setup lang="ts">
import { computed } from 'vue'
import type { Activity } from '@/api/types'
import { effectiveTravelMinutes } from '@/utils/activity'
import type { NavPoint } from '@/utils/navigation'
import ActivityCard from './ActivityCard.vue'
import TransitConnector from './TransitConnector.vue'

interface DayItem {
  dayNumber: number
  dateStr: string
  items: Activity[]
}

const props = defineProps<{
  day: DayItem
  /** 当前在地图上聚焦的活动 id 或 seq（用于卡片高亮） */
  activeSeq?: number | string | null
}>()

const emit = defineEmits<{
  swap: [activity: Activity, dayNumber: number]
  dragStart: [index: number]
  dragEnd: [index: number]
  drop: [fromIndex: number, toIndex: number, dayNumber: number]
  focus: [activity: Activity]
}>()

const formattedDate = computed(() => {
  if (!props.day.dateStr) return ''
  const date = new Date(props.day.dateStr)
  return date.toLocaleDateString('zh-CN', {
    year: 'numeric',
    month: 'long',
    day: 'numeric',
    weekday: 'long',
  })
})

const activityCount = computed(() => props.day.items.length)

function handleSwap(activity: Activity) {
  emit('swap', activity, props.day.dayNumber)
}

function handleDragStart(index: number) {
  emit('dragStart', index)
}

function handleDragEnd(index: number) {
  emit('dragEnd', index)
}

function handleDrop(fromIndex: number, toIndex: number) {
  emit('drop', fromIndex, toIndex, props.day.dayNumber)
}

function handleFocus(activity: Activity) {
  emit('focus', activity)
}

/** 到下一站的交通分钟数：真实值优先，为 0 时按坐标估算，避免展示「0分钟」 */
function travelToNext(activity: Activity, index: number): number {
  return effectiveTravelMinutes(activity, props.day.items[index + 1])
}

/**
 * 导航端点：活动有坐标直接用；无坐标（如 rest 原地休息）沿时间轴就近吸附到
 * 最近的有坐标活动——起点向前吸附（休息发生在前一活动处），终点向后吸附
 * （休息节点接管的路程通向后一有坐标活动）。
 */
function navPointOf(index: number, dir: -1 | 1): NavPoint {
  const items = props.day.items
  const src = items[index]
  if (!src) return { name: '' }
  for (let i = index; i >= 0 && i < items.length; i += dir) {
    const a = items[i]
    const lat = Number(a.lat)
    const lng = Number(a.lng)
    if (Number.isFinite(lat) && Number.isFinite(lng) && (lat || lng)) {
      return { lat, lng, name: a.poiName }
    }
  }
  return { name: src.poiName }
}

function isActive(activity: Activity) {
  if (props.activeSeq == null) return false
  return String(activity.id ?? activity.seq) === String(props.activeSeq)
}
</script>

<template>
  <div class="relative animate-fade-in">
    <div class="sticky top-0 z-10 bg-surface-50/80 dark:bg-surface-900/80 backdrop-blur-md border-b border-surface-100 dark:border-surface-800">
      <div class="flex items-center justify-between px-4 py-3">
        <div class="flex items-center gap-3">
          <div class="flex items-center justify-center w-10 h-10 rounded-xl bg-gradient-to-br from-brand-500 to-accent-500 text-white font-bold text-sm shadow-glow">
            D{{ day.dayNumber }}
          </div>
          <div>
            <h3 class="text-sm font-bold text-surface-900 dark:text-white">
              第 {{ day.dayNumber }} 天
            </h3>
            <p class="text-xs text-surface-500 dark:text-surface-400">
              {{ formattedDate }}
            </p>
          </div>
        </div>
        <span class="inline-flex items-center px-2.5 py-1 rounded-full bg-brand-50 dark:bg-brand-900/30 text-brand-700 dark:text-brand-300 text-xs font-medium">
          {{ activityCount }} 个活动
        </span>
      </div>
    </div>

    <div class="relative pl-8 pr-4 py-4">
      <div class="absolute left-11 top-0 bottom-0 w-0.5 bg-gradient-to-b from-brand-200 via-surface-200 to-transparent dark:from-brand-800 dark:via-surface-700" />

      <div class="space-y-0">
        <template v-for="(activity, index) in day.items" :key="activity.id || index">
          <div
            class="relative"
            :draggable="activity.activityType !== 'rest'"
            @dragstart="activity.activityType !== 'rest' && handleDragStart(index)"
            @dragend="handleDragEnd(index)"
            @dragover.prevent
            @drop.prevent="handleDrop($event.dataTransfer?.getData('text/plain') as unknown as number, index)"
          >
            <div class="absolute left-[-1.75rem] top-4 w-3 h-3 rounded-full bg-white dark:bg-surface-800 border-2 border-brand-400 dark:border-brand-500 z-10" />

            <ActivityCard
              :activity="activity"
              :day-number="day.dayNumber"
              :active="isActive(activity)"
              :travel-min="travelToNext(activity, index)"
              @swap="handleSwap"
              @focus="handleFocus"
            />
          </div>

          <TransitConnector
            v-if="index < day.items.length - 1"
            :mode="activity.transportMode || 'walk'"
            :duration-min="travelToNext(activity, index)"
            :distance-meters="Number(activity.travelDistanceMeters ?? 0)"
            :from-name="activity.poiName"
            :to-name="day.items[index + 1]?.poiName"
            :from="navPointOf(index, -1)"
            :to="navPointOf(index + 1, 1)"
          />
        </template>
      </div>
    </div>
  </div>
</template>
