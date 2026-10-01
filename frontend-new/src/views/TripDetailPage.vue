<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useTripStore } from '@/stores/trip'
import { useToast } from '@/composables/useToast'
import { tripApi } from '@/api/trip'
import type { Activity, TripVersion, ActivityAlternative, VersionStats } from '@/api/types'
import { normalizeActivities, formatTimePoint, resolveVersionStats } from '@/utils/activity'

import TripStatusBadge from '@/components/trip/TripStatusBadge.vue'
import AiParseSummaryCard from '@/components/trip/AiParseSummaryCard.vue'
import TripStats from '@/components/trip/TripStats.vue'
import DayTimeline from '@/components/timeline/DayTimeline.vue'
import DayDivider from '@/components/timeline/DayDivider.vue'
import TripMap from '@/components/map/TripMap.vue'
import SwapPanel from '@/components/swap/SwapPanel.vue'
import ShareDialog from '@/components/share/ShareDialog.vue'
import ExportPanel from '@/components/trip/ExportPanel.vue'
import VersionCompareView from '@/components/version/VersionCompareView.vue'
import UIDropdown from '@/components/ui/UIDropdown.vue'

const route = useRoute()
const router = useRouter()
const tripStore = useTripStore()
const toast = useToast()
const { t } = useI18n()

const tripId = computed(() => route.params.id as string)

const activeTab = ref<'timeline' | 'map' | 'compare'>('timeline')
const isEditingTitle = ref(false)
const editableTitle = ref('')
const titleInputRef = ref<HTMLInputElement | null>(null)
const showShareDialog = ref(false)
const showExportPanel = ref(false)
const showMobileMap = ref(false)
const showMoreMenu = ref(false)
const deleteConfirmOpen = ref(false)

const versions = ref<TripVersion[]>([])
const selectedVersionId = ref<string>('')
const compareLeftId = ref<string>('')
const compareRightId = ref<string>('')

const swapActivity = ref<Activity | null>(null)
const swapOpen = ref(false)

function scrollToActivity(activity: Activity) {
  activeTab.value = 'timeline'
  showMobileMap.value = false
  const id = activity.id ?? activity.seq
  nextTick(() => {
    const el = document.getElementById(`activity-${id}`)
    if (el) {
      el.scrollIntoView({ behavior: 'smooth', block: 'center' })
      el.classList.add('ring-2', 'ring-brand-500', 'ring-offset-2')
      setTimeout(() => {
        el.classList.remove('ring-2', 'ring-brand-500', 'ring-offset-2')
      }, 1600)
    }
  })
}

const dragFromIndex = ref<number | null>(null)
const dragDayNumber = ref<number | null>(null)

/** 时间线中当前聚焦（地图定位）的活动 */
const focusActivity = ref<Activity | null>(null)

function handleFocusActivity(activity: Activity) {
  focusActivity.value = activity
  // 对比视图没有地图，切回时间线视图（右侧地图）
  if (activeTab.value === 'compare') activeTab.value = 'timeline'
  // 移动端地图默认隐藏，自动展开浮层地图
  if (typeof window !== 'undefined' && window.matchMedia('(max-width: 639px)').matches) {
    showMobileMap.value = true
  }
}

const trip = computed(() => tripStore.currentTrip)
const currentVersion = computed(() => tripStore.currentVersion)
const tripCity = computed(() => trip.value?.city || '')

const activities = computed(() => {
  if (!currentVersion.value) return []
  return normalizeActivities(currentVersion.value.activities).sort((a, b) => a.seq - b.seq)
})

const routes = computed(() => currentVersion.value?.routes || [])

const days = computed(() => {
  const actList = activities.value
  if (actList.length === 0) return []

  const dayMap = new Map<number, { dayNumber: number; dateStr: string; items: Activity[] }>()
  const tripStart = trip.value?.timeStart ? new Date(trip.value.timeStart) : new Date()

  const ensureDay = (dayNum: number) => {
    if (!dayMap.has(dayNum)) {
      const dayDate = new Date(tripStart.getFullYear(), tripStart.getMonth(), tripStart.getDate())
      dayDate.setDate(dayDate.getDate() + dayNum - 1)
      dayMap.set(dayNum, {
        dayNumber: dayNum,
        dateStr: dayDate.toISOString(),
        items: [],
      })
    }
    return dayMap.get(dayNum)!
  }

  actList.forEach((activity) => {
    let dayNum = Number(activity.day) || 0
    if (!dayNum) {
      if (activity.scheduledStart) {
        const raw = String(activity.scheduledStart)
        if (/^\d{1,2}:\d{2}/.test(raw) && !raw.includes('T') && !raw.includes('-')) {
          dayNum = 1
        } else {
          const actDate = new Date(raw)
          const diffMs = actDate.getTime() - tripStart.getTime()
          dayNum = Math.max(1, Math.floor(diffMs / (1000 * 60 * 60 * 24)) + 1)
        }
      } else {
        dayNum = 1
      }
    }
    ensureDay(dayNum).items.push(activity)
  })

  return Array.from(dayMap.values())
    .sort((a, b) => a.dayNumber - b.dayNumber)
    .map((d) => ({
      ...d,
      items: [...d.items].sort((a, b) => {
        const ta = formatTimePoint(a.scheduledStart)
        const tb = formatTimePoint(b.scheduledStart)
        if (ta && tb) return ta.localeCompare(tb)
        return (a.seq || 0) - (b.seq || 0)
      }),
    }))
})

const stats = computed<Partial<VersionStats>>(() =>
  resolveVersionStats(currentVersion.value?.stats, activities.value),
)

const versionOptions = computed(() =>
  versions.value.map((v) => {
    const date = new Date(v.createdAt).toLocaleDateString('zh-CN', { month: 'numeric', day: 'numeric' })
    const count = normalizeActivities(v.activities).length
    const current = v.id === currentVersion.value?.id ? t('tripDetail.versionCurrent') : ''
    return {
      value: v.id,
      label: t('tripDetail.versionOption', { version: v.versionNum, date, count, current }),
    }
  })
)

const moreMenuItems = computed(() => [
  {
    label: t('tripDetail.replan'),
    icon: '🤖',
    action: () => navigateToPlanning(true),
  },
  {
    label: t('tripDetail.duplicate'),
    icon: '📋',
    action: handleDuplicate,
  },
  {
    label: t('tripDetail.archive'),
    icon: '📦',
    action: handleArchive,
  },
  {
    label: t('trip.delete'),
    icon: '🗑️',
    danger: true,
    action: () => { deleteConfirmOpen.value = true; showMoreMenu.value = false },
  },
])

interface DayItem {
  dayNumber: number
  dateStr: string
  items: Activity[]
}

interface SwapActivity {
  id: string
  name: string
  type: string
  seq: number
  city?: string
}

function mapActivityForSwap(activity: Activity): SwapActivity {
  return {
    id: activity.id || '',
    name: activity.poiName,
    type: activity.activityType,
    seq: activity.seq,
    city: tripStore.currentTrip?.city ?? undefined,
  }
}

async function loadVersions() {
  try {
    const result = await tripApi.getVersions(tripId.value)
    versions.value = Array.isArray(result) ? result : []
    if (versions.value.length > 0 && !selectedVersionId.value) {
      selectedVersionId.value = currentVersion.value?.id || versions.value[0].id
    }
    if (versions.value.length >= 2) {
      compareLeftId.value = versions.value[versions.value.length - 2].id
      compareRightId.value = versions.value[versions.value.length - 1].id
    }
  } catch {
    versions.value = []
  }
}

async function handleVersionSwitch(versionId: string) {
  selectedVersionId.value = versionId
  const version = versions.value.find((v) => v.id === versionId)
  if (version) {
    store.currentVersion = version
  } else {
    try {
      const tripData = await tripApi.get(tripId.value)
      const found = tripData.latestVersion
      if (found && found.id === versionId) {
        store.currentVersion = found
      }
    } catch { /* ignore */ }
  }
}

const store = tripStore

function startEditTitle() {
  if (!trip.value) return
  editableTitle.value = trip.value.title
  isEditingTitle.value = true
  nextTick(() => {
    titleInputRef.value?.focus()
    titleInputRef.value?.select()
  })
}

async function saveTitle() {
  if (!trip.value) return
  const newTitle = editableTitle.value.trim()
  if (!newTitle || newTitle === trip.value.title) {
    isEditingTitle.value = false
    return
  }
  try {
    await tripStore.updateTrip(tripId.value, { title: newTitle })
    toast.success(t('tripDetail.titleUpdated'))
  } catch {
    toast.error(t('tripDetail.titleUpdateFailed'))
  }
  isEditingTitle.value = false
}

async function handleExport() {
  showExportPanel.value = true
}

async function handleDuplicate() {
  try {
    const newTrip = await tripApi.create({
      title: t('tripDetail.duplicateTitle', { title: trip.value?.title || t('tripDetail.untitled') }),
      rawInput: trip.value?.rawInput || '',
      timeStart: trip.value?.timeStart || '',
      timeEnd: trip.value?.timeEnd || '',
      transportMode: trip.value?.transportMode,
      pace: trip.value?.pace || 'moderate',
    })
    toast.success(t('tripDetail.duplicated'))
    router.push(`/trips/${newTrip.id}`)
  } catch {
    toast.error(t('tripDetail.duplicateFailed'))
  }
  showMoreMenu.value = false
}

async function handleArchive() {
  try {
    await tripStore.updateTrip(tripId.value, { status: 'archived' })
    toast.success(t('tripDetail.archived'))
  } catch {
    toast.error(t('tripDetail.archiveFailed'))
  }
  showMoreMenu.value = false
}

async function handleDelete() {
  try {
    await tripStore.deleteTrip(tripId.value)
    toast.success(t('tripDetail.deleted'))
    router.push('/dashboard')
  } catch {
    toast.error(t('tripDetail.deleteFailed'))
  }
  deleteConfirmOpen.value = false
}

function handleSwapTrigger(activity: Activity) {
  swapActivity.value = activity
  swapOpen.value = true
}

function handleSwapResult(_alternative: ActivityAlternative) {
  swapOpen.value = false
  swapActivity.value = null
  tripStore.fetchTrip(tripId.value)
  loadVersions()
  toast.success(t('tripDetail.swapSuccess'))
}

function handleSwapClose() {
  swapOpen.value = false
  swapActivity.value = null
}

function handleDragStart(dayNumber: number, index: number) {
  dragFromIndex.value = index
  dragDayNumber.value = dayNumber
}

function handleDragEnd() {
  dragFromIndex.value = null
  dragDayNumber.value = null
}

function handleDrop(fromIndex: number, toIndex: number, dayNumber: number) {
  if (dragDayNumber.value !== dayNumber || dragFromIndex.value === null) return

  const dayData = days.value.find((d) => d.dayNumber === dayNumber)
  if (!dayData) return

  const items = [...dayData.items]
  const [moved] = items.splice(fromIndex, 1)
  items.splice(toIndex, 0, moved)

  items.forEach((item, idx) => {
    item.seq = idx + 1
  })

  if (currentVersion.value) {
    const allActivities = [...currentVersion.value.activities]
    const otherDays = allActivities.filter((a) => {
      let d = 1
      if (a.scheduledStart && trip.value?.timeStart) {
        const actDate = new Date(a.scheduledStart)
        const tripStart = new Date(trip.value.timeStart)
        d = Math.max(1, Math.floor((actDate.getTime() - tripStart.getTime()) / (1000 * 60 * 60 * 24)) + 1)
      }
      return d !== dayNumber
    })
    currentVersion.value.activities = [...otherDays, ...items]
  }

  dragFromIndex.value = null
  dragDayNumber.value = null
  toast.info(t('tripDetail.reorderSuccess'))
}

function navigateBack() {
  router.push('/dashboard')
}

function navigateToPlanning(force: boolean | MouseEvent = false) {
  const isForce = force === true
  router.push(isForce
    ? `/trips/${tripId.value}/planning?force=1`
    : `/trips/${tripId.value}/planning`)
}

/** 创建新版本：换版规划——主题不变（城市/日期/节奏/指定地点），排除已用 POI，生成内容不同的路线 */
function createVariantVersion() {
  router.push(`/trips/${tripId.value}/planning?variant=1`)
}

function toggleMobileMap() {
  showMobileMap.value = !showMobileMap.value
}

onMounted(async () => {
  await tripStore.fetchTrip(tripId.value)
  await loadVersions()
})

onUnmounted(() => {
  tripStore.currentTrip = null
  tripStore.currentVersion = null
})
</script>

<template>
  <div class="flex h-screen flex-col bg-surface-50 dark:bg-surface-900">
    <!-- Top Header Bar -->
    <header class="relative z-30 flex items-center gap-2 border-b border-surface-200 bg-white px-3 py-2.5 shadow-sm dark:border-surface-700 dark:bg-surface-800 sm:px-4">
      <!-- Back Button -->
      <button
        class="flex h-9 w-9 items-center justify-center rounded-xl text-surface-500 transition-colors hover:bg-surface-100 hover:text-surface-700 dark:text-surface-400 dark:hover:bg-surface-700"
        :title="t('common.back')"
        @click="navigateBack"
      >
        <svg class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
          <path stroke-linecap="round" stroke-linejoin="round" d="M15 19l-7-7 7-7" />
        </svg>
      </button>

      <!-- Trip Title -->
      <div class="flex min-w-0 flex-1 items-center gap-2">
        <template v-if="isEditingTitle">
          <input
            ref="titleInputRef"
            v-model="editableTitle"
            class="min-w-0 flex-1 rounded-lg border border-brand-300 bg-white px-2 py-1 text-sm font-bold text-surface-900 outline-none ring-2 ring-brand-500/20 focus:border-brand-500 dark:border-brand-600 dark:bg-surface-700 dark:text-white"
            maxlength="200"
            @blur="saveTitle"
            @keydown.enter="saveTitle"
            @keydown.escape="isEditingTitle = false"
          />
        </template>
        <template v-else>
          <h1
            class="max-w-[200px] truncate text-sm font-bold text-surface-900 hover:text-brand-600 sm:max-w-none sm:text-base dark:text-white dark:hover:text-brand-400 cursor-pointer"
            :title="t('tripDetail.editTitleHint')"
            @click="startEditTitle"
          >
            {{ trip?.title || t('common.loading') }}
          </h1>
        </template>
        <TripStatusBadge v-if="trip" :status="trip.status" />
      </div>

      <!-- Header Actions -->
      <div class="flex items-center gap-1.5">
        <!-- Share Button -->
        <button
          class="hidden h-9 w-9 items-center justify-center rounded-xl text-surface-500 transition-colors hover:bg-surface-100 hover:text-brand-600 dark:text-surface-400 dark:hover:bg-surface-700 sm:flex"
          :title="t('trip.share')"
          @click="showShareDialog = true"
        >
          <svg class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
            <path stroke-linecap="round" stroke-linejoin="round" d="M8.684 13.342C8.886 12.938 9 12.482 9 12c0-.482-.114-.938-.316-1.342m0 2.684a3 3 0 110-2.684m0 2.684l6.632 3.316m-6.632-6l6.632-3.316m0 0a3 3 0 105.367-2.684 3 3 0 00-5.367 2.684zm0 9.316a3 3 0 105.368 2.684 3 3 0 00-5.368-2.684z" />
          </svg>
        </button>

        <!-- Export Button -->
        <button
          class="hidden h-9 w-9 items-center justify-center rounded-xl text-surface-500 transition-colors hover:bg-surface-100 hover:text-brand-600 dark:text-surface-400 dark:hover:bg-surface-700 sm:flex"
          :title="t('trip.export')"
          @click="handleExport"
        >
          <svg class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
            <path stroke-linecap="round" stroke-linejoin="round" d="M12 10v6m0 0l-3-3m3 3l3-3m2 8H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
          </svg>
        </button>

        <!-- More Menu -->
        <UIDropdown :items="moreMenuItems">
          <template #trigger>
            <button
              class="flex h-9 w-9 items-center justify-center rounded-xl text-surface-500 transition-colors hover:bg-surface-100 hover:text-surface-700 dark:text-surface-400 dark:hover:bg-surface-700"
              :title="t('tripDetail.moreActions')"
            >
              <svg class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                <path stroke-linecap="round" stroke-linejoin="round" d="M12 5v.01M12 12v.01M12 19v.01M12 6a1 1 0 110-2 1 1 0 010 2zm0 7a1 1 0 110-2 1 1 0 010 2zm0 7a1 1 0 110-2 1 1 0 010 2z" />
              </svg>
            </button>
          </template>
        </UIDropdown>
      </div>
    </header>

    <!-- View Toggle Tabs + Version Selector -->
    <div class="flex flex-wrap items-center justify-between gap-2 border-b border-surface-100 bg-white px-3 py-2 sm:px-4 dark:border-surface-700 dark:bg-surface-800">
      <!-- View Tabs -->
      <div class="flex rounded-xl bg-surface-100 p-1 dark:bg-surface-700">
        <button
          v-for="tab in [
            { key: 'timeline' as const, label: t('tripDetail.tabTimeline') },
            { key: 'map' as const, label: t('tripDetail.tabMap') },
            { key: 'compare' as const, label: t('tripDetail.tabCompare') },
          ]"
          :key="tab.key"
          :class="[
            'relative rounded-lg px-4 py-1.5 text-xs font-semibold transition-all duration-200 sm:text-sm',
            activeTab === tab.key
              ? 'bg-white text-brand-600 shadow-sm dark:bg-surface-600 dark:text-brand-400'
              : 'text-surface-500 hover:text-surface-700 dark:text-surface-400 dark:hover:text-surface-200',
          ]"
          @click="activeTab = tab.key"
        >
          {{ tab.label }}
        </button>
      </div>

      <!-- Version Selector & Actions -->
      <div class="flex items-center gap-2">
        <select
          v-if="versions.length > 0"
          :value="selectedVersionId"
          class="rounded-lg border border-surface-200 bg-white px-3 py-1.5 text-xs font-medium text-surface-700 outline-none focus:border-brand-400 focus:ring-2 focus:ring-brand-500/20 dark:border-surface-600 dark:bg-surface-700 dark:text-surface-300"
          @change="handleVersionSwitch(($event.target as HTMLSelectElement).value)"
        >
          <option
            v-for="opt in versionOptions"
            :key="opt.value"
            :value="opt.value"
          >
            {{ opt.label }}
          </option>
        </select>

        <button
          class="inline-flex items-center gap-1.5 rounded-lg bg-brand-50 px-3 py-1.5 text-xs font-semibold text-brand-600 transition-colors hover:bg-brand-100 dark:bg-brand-900/30 dark:text-brand-400 dark:hover:bg-brand-900/50"
          :title="t('tripDetail.variantHint')"
          @click="createVariantVersion"
        >
          <svg class="h-3.5 w-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
            <path stroke-linecap="round" stroke-linejoin="round" d="M12 4v16m8-8H4" />
          </svg>
          <span class="hidden sm:inline">{{ t('tripDetail.createVersion') }}</span>
        </button>
      </div>
    </div>

    <!-- Main Content Area -->
    <div class="relative flex min-h-0 flex-1 overflow-hidden">
      <!-- Timeline View -->
      <template v-if="activeTab === 'timeline'">
        <!-- Left: Timeline (60%) -->
        <div class="scrollbar-thin flex w-full flex-col overflow-y-auto sm:w-[60%]" :class="{ 'hidden sm:flex': showMobileMap }">
          <!-- AI 解析摘要（城市/摘要/景点·用餐引用，v1.15.0） -->
          <AiParseSummaryCard
            v-if="trip && (trip.parsedInput || trip.rawInput)"
            class="mx-4 mt-4"
            :parsed-input="trip.parsedInput"
            :raw-input="trip.rawInput"
          />
          <div v-if="days.length === 0" class="flex flex-1 flex-col items-center justify-center p-8">
            <div class="mb-4 flex h-20 w-20 items-center justify-center rounded-2xl bg-surface-100 dark:bg-surface-700">
              <svg class="h-10 w-10 text-surface-400" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.5">
                <path stroke-linecap="round" stroke-linejoin="round" d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2" />
              </svg>
            </div>
            <h3 class="mb-1 text-lg font-semibold text-surface-700 dark:text-surface-300">{{ t('tripDetail.emptyTitle') }}</h3>
            <p class="mb-4 text-sm text-surface-500 dark:text-surface-400">{{ t('tripDetail.emptyDesc') }}</p>
            <button class="btn-primary" @click="navigateToPlanning">{{ t('landing.hero.cta') }}</button>
          </div>

          <template v-else>
            <template v-for="(day, dayIdx) in days" :key="day.dayNumber">
              <DayDivider
                v-if="dayIdx > 0"
                :day-number="day.dayNumber"
                :date-str="day.dateStr"
              />
              <DayTimeline
                :day="day"
                :active-seq="focusActivity ? (focusActivity.id ?? focusActivity.seq) : null"
                @swap="(activity) => handleSwapTrigger(activity)"
                @focus="handleFocusActivity"
                @drag-start="(idx) => handleDragStart(day.dayNumber, idx)"
                @drag-end="handleDragEnd"
                @drop="(from, to) => handleDrop(from, to, day.dayNumber)"
              />
            </template>
          </template>
        </div>

        <!-- Right: Map (40%) - Desktop -->
        <div
          v-if="!showMobileMap"
          class="scrollbar-thin hidden w-[40%] border-l border-surface-200 bg-surface-100 sm:block dark:border-surface-700 dark:bg-surface-800"
        >
          <TripMap
            :activities="activities"
            :routes="routes"
            :city="tripCity"
            :focus-activity="focusActivity"
            @select-activity="(a) => scrollToActivity(a)"
          />
        </div>

        <!-- Mobile Map Toggle Button -->
        <button
          class="fixed bottom-24 right-4 z-30 flex h-12 w-12 items-center justify-center rounded-full bg-brand-500 text-white shadow-lg shadow-brand-500/30 transition-transform hover:scale-110 sm:hidden"
          @click="toggleMobileMap"
        >
          <svg v-if="!showMobileMap" class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
            <path stroke-linecap="round" stroke-linejoin="round" d="M9 20l-5.447-2.724A1 1 0 013 16.382V5.618a1 1 0 011.447-.894L9 7m0 13l6-3m-6 3V7m6 10l4.553 2.276A1 1 0 0021 18.382V7.618a1 1 0 00-.553-.894L15 4m0 13V4m0 0L9 7" />
          </svg>
          <svg v-else class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
            <path stroke-linecap="round" stroke-linejoin="round" d="M6 18L18 6M6 6l12 12" />
          </svg>
        </button>

        <!-- Mobile Map Overlay -->
        <Transition name="slide-up">
          <div
            v-if="showMobileMap"
            class="fixed inset-x-0 bottom-0 top-16 z-20 bg-white sm:hidden dark:bg-surface-800"
          >
            <div class="relative h-full">
              <button
                class="absolute right-3 top-3 z-30 flex h-8 w-8 items-center justify-center rounded-full bg-white/90 text-surface-600 shadow-md"
                @click="showMobileMap = false"
              >
                <svg class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                  <path stroke-linecap="round" stroke-linejoin="round" d="M6 18L18 6M6 6l12 12" />
                </svg>
              </button>
              <TripMap
                :activities="activities"
                :routes="routes"
                :city="tripCity"
                :focus-activity="focusActivity"
                @select-activity="(a) => scrollToActivity(a)"
              />
            </div>
          </div>
        </Transition>
      </template>

      <!-- Map View (full width) -->
      <div v-if="activeTab === 'map'" class="h-full w-full">
        <TripMap
          :activities="activities"
          :routes="routes"
          :city="tripCity"
          :focus-activity="focusActivity"
          @select-activity="(a) => scrollToActivity(a)"
        />
      </div>

      <!-- Compare View (对齐 diff：保留/调整/新增/移除 + 统计对比) -->
      <div v-if="activeTab === 'compare'" class="h-full w-full overflow-hidden">
        <VersionCompareView
          v-model:left="compareLeftId"
          v-model:right="compareRightId"
          :versions="versions"
          :trip-start="trip?.timeStart"
        />
      </div>
    </div>

    <!-- Bottom Bar -->
    <div class="flex items-center justify-between gap-3 border-t border-surface-200 bg-white px-3 py-2.5 sm:px-4 dark:border-surface-700 dark:bg-surface-800">
      <div class="min-w-0 flex-1 overflow-hidden">
        <TripStats :stats="stats" />
      </div>
      <button
        class="inline-flex shrink-0 items-center gap-2 rounded-xl bg-gradient-to-r from-brand-500 to-brand-600 px-4 py-2.5 text-xs font-semibold text-white shadow-lg shadow-brand-500/25 transition-all hover:from-brand-600 hover:to-brand-700 hover:shadow-xl sm:text-sm"
        @click="navigateToPlanning"
      >
        <svg class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
          <path stroke-linecap="round" stroke-linejoin="round" d="M12 4v16m8-8H4" />
        </svg>
        <span class="hidden sm:inline">{{ t('tripDetail.addActivity') }}</span>
        <span class="sm:hidden">{{ t('tripDetail.addShort') }}</span>
      </button>
    </div>

    <!-- SwapPanel -->
    <SwapPanel
      :open="swapOpen"
      :activity="swapActivity ? mapActivityForSwap(swapActivity) : null"
      :trip-id="tripId"
      @close="handleSwapClose"
      @swap="handleSwapResult"
    />

    <!-- ShareDialog -->
    <ShareDialog
      :open="showShareDialog"
      :trip-id="tripId"
      @close="showShareDialog = false"
    />

    <!-- ExportPanel -->
    <ExportPanel
      v-if="showExportPanel"
      :trip-id="tripId"
      @close="showExportPanel = false"
    />

    <!-- Delete Confirmation Modal -->
    <Teleport to="body">
      <Transition name="modal">
        <div
          v-if="deleteConfirmOpen"
          class="fixed inset-0 z-50 flex items-center justify-center p-4"
          @click.self="deleteConfirmOpen = false"
        >
          <div class="fixed inset-0 bg-black/40 backdrop-blur-sm" @click="deleteConfirmOpen = false" />
          <div class="relative z-10 w-full max-w-sm rounded-2xl bg-white p-6 shadow-2xl dark:bg-surface-800">
            <div class="mb-4 flex h-12 w-12 items-center justify-center rounded-full bg-danger-100 dark:bg-danger-900/30">
              <svg class="h-6 w-6 text-danger-600 dark:text-danger-400" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                <path stroke-linecap="round" stroke-linejoin="round" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
              </svg>
            </div>
            <h3 class="mb-2 text-lg font-bold text-surface-900 dark:text-white">{{ t('tripDetail.confirmDelete') }}</h3>
            <p class="mb-6 text-sm text-surface-500 dark:text-surface-400">{{ t('tripDetail.deleteConfirmText') }}</p>
            <div class="flex justify-end gap-3">
              <button
                class="rounded-xl border border-surface-200 bg-white px-4 py-2 text-sm font-medium text-surface-700 transition-colors hover:bg-surface-50 dark:border-surface-600 dark:bg-surface-700 dark:text-surface-300"
                @click="deleteConfirmOpen = false"
              >
                {{ t('common.cancel') }}
              </button>
              <button
                class="rounded-xl bg-danger-500 px-4 py-2 text-sm font-semibold text-white shadow-lg shadow-danger-500/25 transition-all hover:bg-danger-600"
                @click="handleDelete"
              >
                {{ t('tripDetail.confirmDelete') }}
              </button>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>

<style scoped>
.slide-up-enter-active,
.slide-up-leave-active {
  transition: transform 0.35s cubic-bezier(0.4, 0, 0.2, 1);
}
.slide-up-enter-from,
.slide-up-leave-to {
  transform: translateY(100%);
}

.modal-enter-active,
.modal-leave-active {
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
}
.modal-enter-from,
.modal-leave-to {
  opacity: 0;
}
.modal-enter-from .relative,
.modal-leave-to .relative {
  transform: scale(0.95) translateY(10px);
}

.scrollbar-thin::-webkit-scrollbar {
  width: 4px;
}
.scrollbar-thin::-webkit-scrollbar-track {
  background: transparent;
}
.scrollbar-thin::-webkit-scrollbar-thumb {
  background: rgb(209 213 219 / 0.6);
  border-radius: 9999px;
}
</style>
