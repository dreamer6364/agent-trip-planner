<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { usePlanningStore } from '@/stores/planning'
import { useTripStore } from '@/stores/trip'
import { useToast } from '@/composables/useToast'
import { planApi } from '@/api/plan'
import { tripApi } from '@/api/trip'
import type { PlanTaskProgress } from '@/api/types'
import AiParseSummaryCard from '@/components/trip/AiParseSummaryCard.vue'

const route = useRoute()
const router = useRouter()
const planningStore = usePlanningStore()
const tripStore = useTripStore()
const toast = useToast()
const { t } = useI18n()

const tripId = computed(() => route.params.id as string)
const forceReplan = computed(() => route.query.force === '1' || route.query.force === 'true')
/** 换版规划：主题不变、排除已用 POI，生成内容不同的新版本 */
const variantReplan = computed(() => route.query.variant === '1' || route.query.variant === 'true')

const taskProgress = ref<PlanTaskProgress | null>(null)
const pollingTimer = ref<ReturnType<typeof setInterval> | null>(null)
const wsRef = ref<WebSocket | null>(null)
const autoNavigateTimer = ref<ReturnType<typeof setTimeout> | null>(null)
const taskId = ref<string>('')
const taskStatus = ref<'pending' | 'running' | 'completed' | 'failed'>('pending')
const status = computed(() => taskStatus.value)
const progress = ref(0)
const stage = ref('')
const stageMessage = ref('')
const errorMessage = ref('')
const resultVersionId = ref('')
const useWebSocket = ref(false)

const wsBaseUrl = computed(() => {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${protocol}//${window.location.host}`
})

async function initPlanning() {
  await tripStore.fetchTrip(tripId.value)

  const trip = tripStore.currentTrip
  if (!trip) {
    toast.error(t('planningPage.tripNotFound'))
    router.push('/dashboard')
    return
  }

  // 非强制/非换版重规划时，已完成行程直接展示完成态
  if (!forceReplan.value && !variantReplan.value && (trip.status === 'planned' || trip.status === 'completed') && trip.latestVersion) {
    taskStatus.value = 'completed'
    progress.value = 100
    stage.value = 'SAVE_RESULT'
    resultVersionId.value = trip.latestVersion.id
    return
  }

  // 规划中：轮询行程状态（同步规划无 taskId）
  if (trip.status === 'planning') {
    taskStatus.value = 'running'
    progress.value = 30
    stage.value = 'SOLVE'
    stageMessage.value = t('planningPage.planningNow')
    startTripStatusPolling()
    return
  }

  // 失败或强制/换版重规划：触发新规划
  if (trip.status === 'failed' || forceReplan.value || variantReplan.value) {
    await triggerNewPlan()
    return
  }

  try {
    const tasks = await planApi.getTripTasks(tripId.value)
    const latestTask = Array.isArray(tasks) && tasks.length > 0
      ? tasks.sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())[0]
      : null

    if (latestTask) {
      taskId.value = latestTask.taskId
      taskProgress.value = latestTask
      taskStatus.value = latestTask.status
      progress.value = latestTask.progress
      stage.value = latestTask.stage || ''
      stageMessage.value = latestTask.message || ''
      resultVersionId.value = latestTask.resultVersionId || ''

      if (latestTask.status === 'completed' || latestTask.status === 'failed') {
        return
      }

      startMonitoring()
    } else {
      await triggerNewPlan()
    }
  } catch {
    await triggerNewPlan()
  }
}

async function triggerNewPlan() {
  taskStatus.value = 'running'
  progress.value = 5
  stage.value = 'PARSE_INPUT'
  stageMessage.value = t('planningPage.starting')
  errorMessage.value = ''

  try {
    await tripApi.triggerPlan(tripId.value, undefined, variantReplan.value)
    toast.info(variantReplan.value ? t('planningPage.variantStarted') : t('planningPage.planDoneLoading'))

    await tripStore.fetchTrip(tripId.value)
    await applyTripStatus()
  } catch (err: unknown) {
    // 超时或网络错误：后端可能仍在同步规划，改为轮询行程状态
    const msg = err instanceof Error ? err.message : ''
    if (msg.includes('timeout') || msg.includes('Timeout')) {
      stageMessage.value = t('planningPage.slowContinue')
      startTripStatusPolling()
      return
    }
    taskStatus.value = 'failed'
    errorMessage.value = t('planningPage.startFailed')
  }
}

async function applyTripStatus() {
  const st = tripStore.currentTrip?.status
  if (st === 'completed' || st === 'planned') {
    handleProgressMessage({
      type: 'COMPLETED',
      taskId: taskId.value || 'sync',
      tripId: tripId.value,
      percent: 100,
      stage: 'SAVE_RESULT',
      message: t('planning.completed'),
      resultVersionId: tripStore.currentTrip?.latestVersion?.id,
      timestamp: new Date().toISOString(),
    })
  } else if (st === 'failed') {
    handleProgressMessage({
      type: 'FAILED',
      taskId: taskId.value || 'sync',
      tripId: tripId.value,
      percent: progress.value,
      stage: stage.value,
      message: t('planning.failed'),
      error: t('planningPage.errorGeneric'),
      timestamp: new Date().toISOString(),
    })
  } else if (st === 'planning') {
    taskStatus.value = 'running'
    progress.value = Math.min(90, progress.value + 15)
    // 进度过 75% 时示意推进到餐次校验阶段（1.31.0 CHECK_MEALS）
    stage.value = progress.value >= 75 ? 'CHECK_MEALS' : 'SOLVE'
    stageMessage.value = t('planningPage.planningNow')
    startTripStatusPolling()
  }
}

function startTripStatusPolling() {
  if (pollingTimer.value) return

  pollingTimer.value = setInterval(async () => {
    try {
      await tripStore.fetchTrip(tripId.value)
      const st = tripStore.currentTrip?.status
      if (st === 'completed' || st === 'planned' || st === 'failed') {
        stopMonitoring()
        await applyTripStatus()
      } else {
        progress.value = Math.min(95, progress.value + 3)
        // 进度过 75% 且尚未进入收尾阶段时，示意推进到餐次校验（1.31.0 CHECK_MEALS）
        if (progress.value >= 75 && getCurrentStageIndex() < getStageIndex('CHECK_MEALS')) {
          stage.value = 'CHECK_MEALS'
        }
      }
    } catch { /* ignore polling error */ }
  }, 2000)
}

function startMonitoring() {
  try {
    const wsUrl = `${wsBaseUrl.value}/ws/planning/${taskId.value}`
    const ws = new WebSocket(wsUrl)

    ws.onopen = () => {
      useWebSocket.value = true
      if (pollingTimer.value) {
        clearInterval(pollingTimer.value)
        pollingTimer.value = null
      }
    }

    ws.onmessage = (event) => {
      try {
        const msg = JSON.parse(event.data)
        handleProgressMessage(msg)
      } catch { /* ignore parse error */ }
    }

    ws.onerror = () => {
      useWebSocket.value = false
      startPolling()
    }

    ws.onclose = () => {
      if (taskStatus.value !== 'completed' && taskStatus.value !== 'failed') {
        useWebSocket.value = false
        startPolling()
      }
    }

    wsRef.value = ws
  } catch {
    useWebSocket.value = false
    startPolling()
  }
}

function startPolling() {
  if (pollingTimer.value) return

  pollingTimer.value = setInterval(async () => {
    // 无 taskId 时改为轮询行程状态（同步规划场景）
    if (!taskId.value) {
      try {
        await tripStore.fetchTrip(tripId.value)
        const st = tripStore.currentTrip?.status
        if (st === 'completed' || st === 'planned' || st === 'failed') {
          stopMonitoring()
          await applyTripStatus()
        }
      } catch { /* ignore */ }
      return
    }
    try {
      const result = await planApi.getTaskProgress(taskId.value)
      handleProgressMessage({
        type: result.status === 'completed' ? 'COMPLETED' : result.status === 'failed' ? 'FAILED' : 'PROGRESS',
        taskId: result.taskId,
        tripId: result.tripId,
        versionId: result.versionId,
        percent: result.progress,
        stage: result.stage,
        message: result.message,
        resultVersionId: result.resultVersionId,
        error: result.errorMessage,
        timestamp: new Date().toISOString(),
      })
    } catch { /* ignore polling error */ }
  }, 2000)
}

function handleProgressMessage(msg: {
  type: string
  taskId: string
  tripId: string
  versionId?: string
  percent: number
  stage?: string
  message?: string
  resultVersionId?: string
  error?: string
  timestamp: string
}) {
  progress.value = msg.percent
  if (msg.stage) stage.value = msg.stage
  if (msg.message) stageMessage.value = msg.message

  planningStore.updateFromProgress({
    percent: msg.percent,
    stage: msg.stage,
    message: msg.message,
  })

  if (msg.type === 'COMPLETED') {
    taskStatus.value = 'completed'
    progress.value = 100
    resultVersionId.value = msg.resultVersionId || ''
    stopMonitoring()
    tripStore.fetchTrip(tripId.value)

    autoNavigateTimer.value = setTimeout(() => {
      router.push(`/trips/${tripId.value}`)
    }, 3000)
  } else if (msg.type === 'FAILED') {
    taskStatus.value = 'failed'
    errorMessage.value = msg.error || t('planningPage.errorGeneric')
    stopMonitoring()
  } else if (msg.type === 'PROGRESS') {
    taskStatus.value = 'running'
  }
}

function stopMonitoring() {
  if (pollingTimer.value) {
    clearInterval(pollingTimer.value)
    pollingTimer.value = null
  }
  if (wsRef.value) {
    wsRef.value.close()
    wsRef.value = null
  }
}

async function handleRetry() {
  taskStatus.value = 'pending'
  progress.value = 0
  stage.value = ''
  stageMessage.value = ''
  errorMessage.value = ''
  resultVersionId.value = ''
  taskId.value = ''
  planningStore.reset()

  await triggerNewPlan()
}

const STAGE_KEYS = [
  'PARSE_INPUT',
  'GEOCODE',
  'BUILD_MODEL',
  'SOLVE',
  'ROUTE',
  'VERIFY_CITY',
  'CHECK_MEALS',
  'SAVE_RESULT',
] as const

const STAGE_ALIASES: Record<string, string> = {
  PARSE_INPUT: 'PARSE_INPUT',
  LLM_PARSE: 'PARSE_INPUT',
  GEOCODE: 'GEOCODE',
  BUILD_MATRIX: 'GEOCODE',
  BUILD_MODEL: 'BUILD_MODEL',
  LLM_PLAN: 'BUILD_MODEL',
  SOLVE: 'SOLVE',
  ROUTE: 'ROUTE',
  CONVERT: 'ROUTE',
  VERIFY_CITY: 'VERIFY_CITY',
  CHECK_MEALS: 'CHECK_MEALS',
  check_meals: 'CHECK_MEALS',
  SAVE_RESULT: 'SAVE_RESULT',
  PERSIST: 'SAVE_RESULT',
  COMPLETED: 'SAVE_RESULT',
}

function normalizeStage(key: string): string {
  return STAGE_ALIASES[key] || key
}

function getStageIndex(key: string) {
  return STAGE_KEYS.indexOf(normalizeStage(key) as typeof STAGE_KEYS[number])
}

function getCurrentStageIndex() {
  return getStageIndex(stage.value)
}

function getStageColor(sKey: string, sLabel: string): string {
  const idx = getStageIndex(sKey)
  const currentIdx = getCurrentStageIndex()
  const st = status.value
  if (st === 'completed') return 'border-success-400 bg-success-50 text-success-600 dark:bg-success-900/30 dark:text-success-400'
  if (st === 'failed' && idx === currentIdx) return 'border-danger-400 bg-danger-50 text-danger-600 dark:bg-danger-900/30 dark:text-danger-400'
  if (idx < currentIdx) return 'border-success-400 bg-success-50 text-success-600 dark:bg-success-900/30 dark:text-success-400'
  if (idx === currentIdx && st === 'running') return 'border-brand-400 bg-brand-50 text-brand-600 shadow-lg shadow-brand-500/30 dark:bg-brand-900/30 dark:text-brand-400'
  return 'border-surface-200 bg-white text-surface-400 dark:border-surface-600 dark:bg-surface-800 dark:text-surface-500'
}

function getStageLabelColor(sKey: string): string {
  const idx = getStageIndex(sKey)
  const currentIdx = getCurrentStageIndex()
  const st = status.value
  if (st === 'completed') return 'text-success-600 dark:text-success-400'
  if (st === 'failed' && idx === currentIdx) return 'text-danger-600 dark:text-danger-400'
  if (idx < currentIdx) return 'text-success-600 dark:text-success-400'
  if (idx === currentIdx && st === 'running') return 'text-brand-600 dark:text-brand-400'
  return 'text-surface-400 dark:text-surface-500'
}

function getLineColor(sKey: string): string {
  const idx2 = getStageIndex(sKey)
  const currentIdx = getCurrentStageIndex()
  if (status.value === 'completed') return 'bg-success-400'
  if (idx2 < currentIdx) return 'bg-success-400'
  return 'bg-surface-200 dark:bg-surface-600'
}

function isStageComplete(sKey: string, currentStatus: string): boolean {
  return currentStatus === 'completed' || getStageIndex(sKey) < getCurrentStageIndex()
}

function handleViewTrip() {
  if (autoNavigateTimer.value) {
    clearTimeout(autoNavigateTimer.value)
    autoNavigateTimer.value = null
  }
  router.push(`/trips/${tripId.value}`)
}

function handleBackToDashboard() {
  if (autoNavigateTimer.value) {
    clearTimeout(autoNavigateTimer.value)
    autoNavigateTimer.value = null
  }
  router.push('/dashboard')
}

watch(
  () => taskStatus.value,
  (status) => {
    if (status === 'failed') {
      toast.error(errorMessage.value || t('planning.failed'))
    } else if (status === 'completed') {
      toast.success(t('planningPage.planSuccessToast'))
    }
  }
)

onMounted(() => {
  initPlanning()
})

onUnmounted(() => {
  stopMonitoring()
  planningStore.reset()
  if (autoNavigateTimer.value) {
    clearTimeout(autoNavigateTimer.value)
  }
})
</script>

<template>
  <div class="relative min-h-screen overflow-hidden bg-surface-50 dark:bg-surface-900">
    <!-- Animated Background -->
    <div class="pointer-events-none absolute inset-0 overflow-hidden">
      <div
        class="absolute -top-1/2 -left-1/4 h-[800px] w-[800px] rounded-full bg-brand-200/20 blur-3xl animate-float-slow dark:bg-brand-800/10"
      />
      <div
        class="absolute -bottom-1/2 -right-1/4 h-[800px] w-[800px] rounded-full bg-accent-200/20 blur-3xl animate-float-slow-reverse dark:bg-accent-800/10"
      />
      <div
        class="absolute top-1/3 left-1/2 h-[600px] w-[600px] -translate-x-1/2 rounded-full bg-success-100/20 blur-3xl animate-float-medium dark:bg-success-800/5"
      />
    </div>

    <!-- Back Button -->
    <div class="absolute left-4 top-4 z-20">
      <button
        class="flex h-10 w-10 items-center justify-center rounded-xl bg-white/80 text-surface-500 shadow-sm backdrop-blur-sm transition-all hover:bg-white hover:text-surface-700 hover:shadow-md dark:bg-surface-800/80 dark:text-surface-400 dark:hover:bg-surface-700"
        :title="t('common.back')"
        @click="handleBackToDashboard"
      >
        <svg class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
          <path stroke-linecap="round" stroke-linejoin="round" d="M15 19l-7-7 7-7" />
        </svg>
      </button>
    </div>

    <!-- Planning Content -->
    <div class="relative z-10 flex min-h-screen items-center justify-center p-4">
      <div class="w-full max-w-lg">
        <div class="glass rounded-3xl p-8 shadow-panel">
          <div class="text-center">
            <!-- Status Icon -->
            <div class="relative mb-6 inline-flex items-center justify-center">
              <!-- Completed -->
              <div
                v-if="status === 'completed'"
                class="flex h-20 w-20 items-center justify-center rounded-full bg-success-100 animate-bounce-in dark:bg-success-900/30"
              >
                <svg class="h-10 w-10 text-success-600 dark:text-success-400" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2.5">
                  <path stroke-linecap="round" stroke-linejoin="round" d="M5 13l4 4L19 7" />
                </svg>
              </div>

              <!-- Failed -->
              <div
                v-else-if="status === 'failed'"
                class="flex h-20 w-20 items-center justify-center rounded-full bg-danger-100 animate-bounce-in dark:bg-danger-900/30"
              >
                <svg class="h-10 w-10 text-danger-600 dark:text-danger-400" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2.5">
                  <path stroke-linecap="round" stroke-linejoin="round" d="M6 18L18 6M6 6l12 12" />
                </svg>
              </div>

              <!-- Running / Pending -->
              <div
                v-else
                class="flex h-20 w-20 items-center justify-center rounded-full bg-brand-100 dark:bg-brand-900/30"
              >
                <svg
                  class="h-10 w-10 text-brand-600 dark:text-brand-400 animate-spin"
                  style="animation-duration: 3s;"
                  fill="none"
                  viewBox="0 0 24 24"
                >
                  <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="2" stroke-dasharray="56.5" stroke-dashoffset="14" stroke-linecap="round" />
                  <path d="M12 3a9 9 0 019 9" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" />
                </svg>
              </div>

              <!-- Pulse Ring -->
              <div
                v-if="status === 'running' || status === 'pending'"
                class="absolute inset-0 rounded-full border-2 border-brand-400/30 animate-ping"
                style="animation-duration: 2s;"
              />
            </div>

            <!-- Title -->
            <h2
              :class="[
                'mb-2 text-3xl font-bold',
                status === 'completed' ? 'gradient-text' : '',
                status === 'failed' ? 'text-danger-600 dark:text-danger-400' : '',
                status === 'running' || status === 'pending' ? 'text-surface-900 dark:text-white' : '',
              ]"
            >
              <template v-if="status === 'completed'">{{ t('planningPage.completedTitle') }}</template>
              <template v-else-if="status === 'failed'">{{ t('planning.failed') }}</template>
              <template v-else>{{ t('planning.title') }}</template>
            </h2>

            <!-- Description -->
            <p class="mb-8 text-surface-500 dark:text-surface-400">
              <template v-if="status === 'completed'">
                {{ t('planningPage.completedDesc') }}
              </template>
              <template v-else-if="status === 'failed'">
                {{ errorMessage || t('planningPage.errorRetry') }}
              </template>
              <template v-else>
                {{ stageMessage || t('planningPage.preparing') }}
              </template>
            </p>

            <!-- AI 解析摘要（含城市缺失黄条提示，v1.15.0） -->
            <AiParseSummaryCard
              v-if="tripStore.currentTrip?.parsedInput || tripStore.currentTrip?.rawInput"
              class="mb-6 text-left"
              :parsed-input="tripStore.currentTrip?.parsedInput"
              :raw-input="tripStore.currentTrip?.rawInput"
            />

            <!-- Progress Bar -->
            <div
              v-if="status !== 'completed' && status !== 'failed'"
              class="mb-8"
            >
              <div class="relative h-3 w-full overflow-hidden rounded-full bg-surface-100 dark:bg-surface-700">
                <div
                  class="absolute inset-y-0 left-0 rounded-full progress-bar-animate"
                  :style="{ width: `${Math.min(100, Math.max(0, progress))}%` }"
                />
                <div
                  class="absolute inset-0 rounded-full opacity-30 striped-pattern"
                  :style="{ width: `${Math.min(100, Math.max(0, progress))}%` }"
                />
              </div>
              <div class="mt-3 text-4xl font-extrabold gradient-text">
                {{ Math.round(progress) }}%
              </div>
            </div>

            <!-- Stage Indicators -->
            <div
              v-if="status === 'running' || status === 'completed'"
              class="mb-8 flex items-center justify-center gap-1 sm:gap-2"
            >
              <div
                v-for="(s, idx) in [
                  { key: 'PARSE_INPUT', label: t('planning.stages.parse'), icon: '📝' },
                  { key: 'GEOCODE', label: t('planning.stages.geocode'), icon: '🌍' },
                  { key: 'BUILD_MODEL', label: t('planning.stages.model'), icon: '🧩' },
                  { key: 'SOLVE', label: t('planning.stages.solve'), icon: '⚙️' },
                  { key: 'ROUTE', label: t('planning.stages.route'), icon: '🗺️' },
                  { key: 'VERIFY_CITY', label: t('planning.stages.verifyCity'), icon: '📍' },
                  { key: 'CHECK_MEALS', label: t('planning.stages.checkMeals'), icon: '🍽️' },
                  { key: 'SAVE_RESULT', label: t('planning.stages.persist'), icon: '💾' },
                ]"
                :key="s.key"
                class="relative flex flex-col items-center"
              >
                <div class="flex items-center">
                  <div
                    :class="[
                      'relative z-10 flex h-9 w-9 items-center justify-center rounded-full border-2 text-xs font-bold transition-all duration-500 sm:h-10 sm:w-10 sm:text-sm',
                      getStageColor(s.key, s.label),
                    ]"
                    :style="(status === 'running' && s.key === stage) ? 'animation: pulse-ring 2s ease-in-out infinite' : ''"
                  >
                    <span v-if="isStageComplete(s.key, status)">✓</span>
                    <span v-else-if="(status as string) === 'failed' && s.key === stage">✕</span>
                    <span v-else>{{ s.icon }}</span>
                    <span
                      v-if="status === 'running' && s.key === stage"
                      class="absolute inset-0 animate-ping rounded-full border-2 border-brand-400 opacity-30"
                    />
                  </div>
                </div>
                <span
                  :class="[
                    'mt-1.5 hidden text-center text-[10px] font-medium transition-colors duration-300 sm:block sm:text-xs',
                    getStageLabelColor(s.key),
                  ]"
                >{{ s.label }}</span>
                <div
                  v-if="idx < 6"
                  :class="[
                    'absolute top-4 left-1/2 h-0.5 w-full -translate-y-1/2 sm:top-5',
                    getLineColor(s.key),
                  ]"
                />
              </div>
            </div>

            <!-- Completed: View Trip Button -->
            <div v-if="status === 'completed'" class="space-y-3">
              <button
                class="btn-primary w-full"
                @click="handleViewTrip"
              >
                <svg class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                  <path stroke-linecap="round" stroke-linejoin="round" d="M9 5l7 7-7 7" />
                </svg>
                {{ t('planningPage.viewTrip') }}
              </button>
              <p class="text-xs text-surface-400 dark:text-surface-500">
                {{ t('planningPage.autoRedirect', { seconds: 3 }) }}
              </p>
            </div>

            <!-- Failed: Retry & Back Buttons -->
            <div v-if="status === 'failed'" class="flex flex-col gap-3 sm:flex-row">
              <button
                class="btn-secondary flex-1"
                @click="handleBackToDashboard"
              >
                <svg class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                  <path stroke-linecap="round" stroke-linejoin="round" d="M10 19l-7-7m0 0l7-7m-7 7h18" />
                </svg>
                {{ t('common.back') }}
              </button>
              <button
                class="btn-primary flex-1"
                @click="handleRetry"
              >
                <svg class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                  <path stroke-linecap="round" stroke-linejoin="round" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
                </svg>
                {{ t('planning.retry') }}
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
@keyframes float-slow {
  0%, 100% { transform: translate(0, 0) scale(1); }
  33% { transform: translate(30px, -30px) scale(1.05); }
  66% { transform: translate(-20px, 20px) scale(0.95); }
}

@keyframes float-slow-reverse {
  0%, 100% { transform: translate(0, 0) scale(1); }
  33% { transform: translate(-30px, 30px) scale(1.05); }
  66% { transform: translate(20px, -20px) scale(0.95); }
}

@keyframes float-medium {
  0%, 100% { transform: translate(-50%, 0) scale(1); }
  50% { transform: translate(-50%, -40px) scale(1.03); }
}

@keyframes pulse-ring {
  0%, 100% { box-shadow: 0 0 0 0 rgba(59, 108, 247, 0.4); }
  50% { box-shadow: 0 0 0 10px rgba(59, 108, 247, 0); }
}

.animate-float-slow {
  animation: float-slow 12s ease-in-out infinite;
}

.animate-float-slow-reverse {
  animation: float-slow-reverse 14s ease-in-out infinite;
}

.animate-float-medium {
  animation: float-medium 10s ease-in-out infinite;
}

.progress-bar-animate {
  background: linear-gradient(90deg, #3b6cf7, #d946ef, #3b6cf7);
  background-size: 200% 100%;
  animation: shimmer-progress 2s linear infinite;
  transition: width 0.5s ease-out;
}

@keyframes shimmer-progress {
  0% { background-position: 200% 0; }
  100% { background-position: -200% 0; }
}

.striped-pattern {
  background-image: repeating-linear-gradient(
    -45deg,
    transparent,
    transparent 6px,
    rgba(255, 255, 255, 0.5) 6px,
    rgba(255, 255, 255, 0.5) 12px
  );
  animation: stripe-move 1s linear infinite;
}

@keyframes stripe-move {
  0% { background-position: 0 0; }
  100% { background-position: 24px 0; }
}
</style>
