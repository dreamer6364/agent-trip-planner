<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { Activity } from '@/api/types'
import { legToNext } from '@/utils/activity'
import type { NavPoint } from '@/utils/navigation'
import ActivityCard from './ActivityCard.vue'
import TransitConnector from './TransitConnector.vue'

const { t, locale } = useI18n()

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
  return date.toLocaleDateString(locale.value, {
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

/**
 * 各活动到「下一站」的交通段（v1.31.0）：
 * - 休息卡为 null → 卡片不展示、连接件隐藏
 * - 休息前地点聚合「休息前 → 休息后」直达段（真实路程由 rest 节点携带）
 */
const legs = computed(() => props.day.items.map((_, i) => legToNext(props.day.items, i)))

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
              {{ t('timeline.day', { n: day.dayNumber }) }}
            </h3>
            <p class="text-xs text-surface-500 dark:text-surface-400">
              {{ formattedDate }}
            </p>
          </div>
        </div>
        <span class="inline-flex items-center px-2.5 py-1 rounded-full bg-brand-50 dark:bg-brand-900/30 text-brand-700 dark:text-brand-300 text-xs font-medium">
          {{ activityCount }} {{ t('trip.activities') }}
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
              :leg="legs[index]"
              @swap="handleSwap"
              @focus="handleFocus"
            />
          </div>

          <!-- 连接件：休息卡不渲染；休息前地点渲染直达「休息后地点」的聚合段（v1.31.0） -->
          <TransitConnector
            v-if="index < day.items.length - 1 && legs[index]"
            :mode="legs[index]!.mode || activity.transportMode || 'walk'"
            :duration-min="legs[index]!.minutes"
            :distance-meters="legs[index]!.distanceMeters"
            :from-name="activity.poiName"
            :to-name="day.items[legs[index]!.toIndex]?.poiName"
            :from="navPointOf(index, -1)"
            :to="navPointOf(legs[index]!.toIndex, 1)"
          />
        </template>
      </div>
    </div>
  </div>
</template>
