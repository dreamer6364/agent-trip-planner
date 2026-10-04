<script setup lang="ts">
import { ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { Activity } from '@/api/types'
import { formatTimePoint, formatDurationText, TRANSPORT_LABELS } from '@/utils/activity'
import type { TravelLeg } from '@/utils/activity'

const { t } = useI18n()

const props = defineProps<{
  activity: Activity
  dayNumber: number
  /** 是否为当前在地图上聚焦的节点（高亮描边） */
  active?: boolean
  /** 到下一站的交通分钟数（旧传参模式；传入 leg 时优先使用 leg） */
  travelMin?: number
  /**
   * 到下一站的交通段（v1.31.0，由时间轴按 rest 链聚合计算）：
   * null=不展示交通信息（休息卡/链尾）；undefined=未启用 leg 模式（回退 travelMin 传参）
   */
  leg?: TravelLeg | null
}>()

const emit = defineEmits<{
  swap: [activity: Activity]
  focus: [activity: Activity]
}>()

const isHovered = ref(false)

const typeConfig = computed(() => {
  const configs: Record<string, { icon: string; label: string; color: string; bg: string; accentBar: string }> = {
    visit: {
      icon: 'M17.657 16.657L13.414 20.9a1.998 1.998 0 01-2.827 0l-4.244-4.243a8 8 0 1111.314 0z',
      label: t('activity.attraction'),
      color: 'text-blue-600 dark:text-blue-400',
      bg: 'bg-blue-50 dark:bg-blue-900/20',
      accentBar: 'bg-blue-500',
    },
    meal: {
      icon: 'M12 8c-1.657 0-3 .895-3 2s1.343 2 3 2 3 .895 3 2-1.343 2-3 2m0-8c1.11 0 2.08.402 2.599 1M12 8V7m0 1v8m0 0v1m0-1c-1.11 0-2.08-.402-2.599-1M21 12a9 9 0 11-18 0 9 9 0 0118 0z',
      label: t('activity.meal'),
      color: 'text-orange-600 dark:text-orange-400',
      bg: 'bg-orange-50 dark:bg-orange-900/20',
      accentBar: 'bg-orange-500',
    },
    transit: {
      icon: 'M8 17h.01M16 17h.01M3 11l1.5-5A2 2 0 016.4 4h11.2a2 2 0 011.9 1.4L21 11M3 11h18M3 11v6a1 1 0 001 1h1a1 1 0 001-1v-1h12v1a1 1 0 001 1h1a1 1 0 001-1v-6',
      label: t('activity.transit'),
      color: 'text-surface-600 dark:text-surface-400',
      bg: 'bg-surface-100 dark:bg-surface-700/50',
      accentBar: 'bg-surface-400',
    },
    museum: {
      icon: 'M19 21V5a2 2 0 00-2-2H7a2 2 0 00-2 2v16m14 0h2m-2 0h-5m-9 0H3m2 0h5M9 7h1m-1 4h1m4-4h1m-1 4h1m-5 10v-5a1 1 0 011-1h2a1 1 0 011 1v5m-4 0h4',
      label: t('activity.museumLabel'),
      color: 'text-purple-600 dark:text-purple-400',
      bg: 'bg-purple-50 dark:bg-purple-900/20',
      accentBar: 'bg-purple-500',
    },
    park: {
      icon: 'M5 3v4M3 5h4M6 17v4m-2-2h4m5-16l2.286 6.857L21 12l-5.714 2.143L13 21l-2.286-6.857L5 12l5.714-2.143L13 3z',
      label: t('activity.park'),
      color: 'text-green-600 dark:text-green-400',
      bg: 'bg-green-50 dark:bg-green-900/20',
      accentBar: 'bg-green-500',
    },
    temple: {
      icon: 'M3 21h18M3 10h18M3 7l9-4 9 4M4 10h16v11H4V10z',
      label: t('activity.temple'),
      color: 'text-amber-600 dark:text-amber-400',
      bg: 'bg-amber-50 dark:bg-amber-900/20',
      accentBar: 'bg-amber-500',
    },
    shopping: {
      icon: 'M16 11V7a4 4 0 00-8 0v4M5 9h14l1 12H4L5 9z',
      label: t('activity.shopping'),
      color: 'text-pink-600 dark:text-pink-400',
      bg: 'bg-pink-50 dark:bg-pink-900/20',
      accentBar: 'bg-pink-500',
    },
    rest: {
      icon: 'M18 8h1a4 4 0 010 8h-1m-6-8v10m0-10v10M6 8H5a4 4 0 000 8h1m12-4a4 4 0 11-8 0 4 4 0 018 0z',
      label: t('activity.rest'),
      color: 'text-teal-600 dark:text-teal-400',
      bg: 'bg-teal-50 dark:bg-teal-900/20',
      accentBar: 'bg-teal-500',
    },
  }
  return configs[props.activity.activityType] ?? configs.visit
})

const priorityConfig = computed(() => {
  const configs: Record<string, { label: string; dotClass: string; textClass: string }> = {
    must: { label: t('activity.priority.must'), dotClass: 'bg-danger-500', textClass: 'text-danger-600 dark:text-danger-400' },
    recommended: { label: t('activity.priority.recommended'), dotClass: 'bg-blue-500', textClass: 'text-blue-600 dark:text-blue-400' },
    optional: { label: t('activity.priority.optional'), dotClass: 'bg-surface-400', textClass: 'text-surface-500 dark:text-surface-400' },
  }
  return configs[props.activity.priority] ?? configs.optional
})

const timeRange = computed(() => {
  const start = formatTimePoint(props.activity.scheduledStart)
  const end = formatTimePoint(props.activity.scheduledEnd)
  if (!start && !end) return ''
  if (start && end) return `${start} – ${end}`
  return start || end
})

const durationText = computed(() => formatDurationText(props.activity.durationMin))

/** 组装「至下一站」提示文案（模式 · 分钟 · 距离）；模式缺失或 mixed 不展示 */
function buildTransportHint(mode: string | undefined, travel: number, km: number): string {
  if (!mode || mode === 'mixed') return ''
  const label = TRANSPORT_LABELS[mode] || mode
  const dist = km > 0 ? ` · ${km < 10 ? km.toFixed(1) : Math.round(km)}km` : ''
  const info = travel > 0
    ? `${label} ${formatDurationText(travel)}${dist}`
    : `${label}${dist}`
  return t('timeline.toNext', { info })
}

const transportHint = computed(() => {
  // 休息卡：不展示任何距离/时间说明（v1.31.0；直达信息由休息前地点展示）
  if (props.activity.activityType === 'rest') return ''
  if (props.leg !== undefined) {
    if (!props.leg) return ''
    return buildTransportHint(props.leg.mode, props.leg.minutes, props.leg.distanceKm)
  }
  // 旧传参模式（未启用 leg 的调用方）
  return buildTransportHint(
    props.activity.transportMode,
    Number(props.travelMin ?? props.activity.travelDurationMin ?? 0) || 0,
    Number(props.activity.travelDistanceKm ?? 0) || 0,
  )
})

/** 评分/人均（餐厅推荐增强 v1.15.0） */
const metaBits = computed(() => {
  const bits: string[] = []
  const rating = Number(props.activity.rating)
  if (!Number.isNaN(rating) && rating > 0) {
    bits.push(`★ ${rating.toFixed(1)}`)
  }
  const cost = String(props.activity.cost ?? '').trim()
  if (cost && cost !== '0' && cost !== '0.0') {
    bits.push(cost.includes('¥') ? cost : t('activity.perPerson', { cost }))
  }
  return bits
})

function handleSwap() {
  emit('swap', props.activity)
}

function handleFocus() {
  emit('focus', props.activity)
}
</script>

<template>
  <div
    :id="`activity-${activity.id ?? activity.seq}`"
    :class="[
      'relative group rounded-xl bg-white dark:bg-surface-800 border shadow-sm hover:shadow-card-hover transition-all duration-300 hover:scale-[1.01] overflow-hidden cursor-pointer',
      active
        ? 'border-brand-400 ring-2 ring-brand-500 ring-offset-2 ring-offset-surface-50 dark:ring-offset-surface-900'
        : 'border-surface-100 dark:border-surface-700 hover:border-brand-300 dark:hover:border-brand-500',
    ]"
    :title="t('timeline.focusInMap')"
    @mouseenter="isHovered = true"
    @mouseleave="isHovered = false"
    @click="handleFocus"
  >
    <div :class="['absolute left-0 top-0 bottom-0 w-1', typeConfig.accentBar]" />

    <div class="pl-5 pr-4 py-4">
      <div class="flex items-start justify-between gap-3">
        <div class="flex-1 min-w-0">
          <!-- 时间点 + 类型 -->
          <div class="mb-2 flex flex-wrap items-center gap-2">
            <span
              v-if="timeRange"
              class="inline-flex items-center gap-1 rounded-lg bg-surface-900 px-2.5 py-1 text-xs font-bold tabular-nums text-white shadow-sm dark:bg-white dark:text-surface-900"
            >
              <svg class="h-3.5 w-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                <path stroke-linecap="round" stroke-linejoin="round" d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" />
              </svg>
              {{ timeRange }}
            </span>
            <span :class="['inline-flex items-center gap-1 px-2 py-0.5 rounded-md text-xs font-medium', typeConfig.bg, typeConfig.color]">
              <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" :d="typeConfig.icon" />
              </svg>
              {{ typeConfig.label }}
            </span>
          </div>

          <!-- 景点名称（醒目） -->
          <h4 class="mb-1.5 text-lg font-extrabold leading-snug tracking-wide text-surface-900 dark:text-white sm:text-xl">
            {{ activity.poiName }}
          </h4>

          <p v-if="activity.slogan" class="mb-2 text-sm font-medium text-brand-600 dark:text-brand-400 line-clamp-1">
            {{ activity.slogan }}
          </p>

          <!-- 评分 · 人均 · 地址（v1.15.0） -->
          <div
            v-if="metaBits.length || activity.poiAddress"
            class="mb-2 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-surface-500 dark:text-surface-400"
          >
            <span
              v-for="bit in metaBits"
              :key="bit"
              class="inline-flex items-center gap-1 font-medium text-amber-600 dark:text-amber-400"
            >
              <svg v-if="bit.startsWith('★')" class="h-3.5 w-3.5" fill="currentColor" viewBox="0 0 20 20">
                <path d="M9.049 2.927c.3-.921 1.603-.921 1.902 0l1.519 4.674a1 1 0 00.95.69h4.915c.969 0 1.371 1.24.588 1.81l-3.976 2.888a1 1 0 00-.363 1.118l1.518 4.674c.3.922-.755 1.688-1.538 1.118l-3.976-2.888a1 1 0 00-1.176 0l-3.976 2.888c-.783.57-1.838-.196-1.538-1.118l1.518-4.674a1 1 0 00-.363-1.118L1.077 10.1c-.783-.57-.38-1.81.588-1.81h4.914a1 1 0 00.951-.69l1.519-4.674z" />
              </svg>
              {{ bit }}
            </span>
            <span v-if="activity.poiAddress" class="line-clamp-1 max-w-full">{{ activity.poiAddress }}</span>
          </div>

          <p v-if="activity.notes" class="text-xs text-surface-500 dark:text-surface-400 line-clamp-2 leading-relaxed">
            {{ activity.notes }}
          </p>

          <p v-if="transportHint" class="mt-2 inline-flex items-center gap-1 text-xs font-medium text-emerald-700 dark:text-emerald-400">
            <svg class="h-3.5 w-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
              <path stroke-linecap="round" stroke-linejoin="round" d="M13 7h6m0 0l-3-3m3 3l-3 3m0 11H7m0 0l3-3m-3 3l3 3" />
            </svg>
            {{ transportHint }}
          </p>
        </div>

        <div class="flex flex-col items-end gap-2 shrink-0">
          <span class="inline-flex items-center px-2.5 py-1 rounded-lg bg-brand-50 dark:bg-brand-900/30 text-brand-700 dark:text-brand-300 text-xs font-bold tabular-nums">
            {{ durationText }}
          </span>
          <span class="inline-flex items-center gap-1">
            <span :class="['w-2 h-2 rounded-full', priorityConfig.dotClass]" />
            <span :class="['text-xs font-medium', priorityConfig.textClass]">{{ priorityConfig.label }}</span>
          </span>
        </div>
      </div>
    </div>

    <div
      v-show="isHovered || active"
      class="absolute bottom-3 right-3 flex items-center gap-2"
    >
      <button
        type="button"
        class="flex h-7 w-7 items-center justify-center rounded-full bg-white text-brand-600 shadow-md ring-1 ring-brand-200 hover:bg-brand-500 hover:text-white transition-colors dark:bg-surface-700 dark:text-brand-300 dark:ring-surface-500 dark:hover:bg-brand-500 dark:hover:text-white"
        :title="t('timeline.viewOnMap')"
        @click.stop="handleFocus"
      >
        <svg class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
          <path stroke-linecap="round" stroke-linejoin="round" d="M17.657 16.657L13.414 20.9a1.998 1.998 0 01-2.827 0l-4.244-4.243a8 8 0 1111.314 0z" />
          <path stroke-linecap="round" stroke-linejoin="round" d="M15 11a3 3 0 11-6 0 3 3 0 016 0z" />
        </svg>
      </button>
      <button
        v-if="activity.activityType !== 'rest'"
        type="button"
        class="flex h-7 w-7 items-center justify-center rounded-full bg-brand-500 text-white shadow-glow hover:bg-brand-600 transition-colors animate-bounce-in"
        :title="t('timeline.swapPosition')"
        @click.stop="handleSwap"
      >
        <svg class="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M7 16V4m0 0L3 8m4-4l4 4m6 0v12m0 0l4-4m-4 4l-4-4" />
        </svg>
      </button>
    </div>
  </div>
</template>
