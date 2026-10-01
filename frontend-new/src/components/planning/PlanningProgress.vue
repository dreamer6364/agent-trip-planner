<script setup lang="ts">
import { computed, watch, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import StageIndicator from './StageIndicator.vue'

interface Props {
  progress: number
  stage: string
  status: 'pending' | 'running' | 'completed' | 'failed'
  message: string
}

const props = withDefaults(defineProps<Props>(), {
  progress: 0,
  stage: '',
  status: 'pending',
  message: '',
})

const emit = defineEmits<{
  retry: []
}>()

const { t } = useI18n()

const showConfetti = ref(false)

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
  SAVE_RESULT: 'SAVE_RESULT',
  PERSIST: 'SAVE_RESULT',
  COMPLETED: 'SAVE_RESULT',
}

function normalizeStage(key: string): string {
  return STAGE_ALIASES[key] || key
}

const stages = computed(() => [
  { key: 'PARSE_INPUT', label: t('planning.stages.parse'), icon: '📝' },
  { key: 'GEOCODE', label: t('planning.stages.geocode'), icon: '🌍' },
  { key: 'BUILD_MODEL', label: t('planning.stages.model'), icon: '🧩' },
  { key: 'SOLVE', label: t('planning.stages.solve'), icon: '⚙️' },
  { key: 'ROUTE', label: t('planning.stages.route'), icon: '🗺️' },
  { key: 'VERIFY_CITY', label: t('planning.stages.verifyCity'), icon: '📍' },
  { key: 'SAVE_RESULT', label: t('planning.stages.persist'), icon: '💾' },
])

const stageIndex = computed(() => {
  const idx = stages.value.findIndex(s => s.key === normalizeStage(props.stage))
  return idx >= 0 ? idx : 0
})

const stageDescriptions = computed<Record<string, string>>(() => ({
  PARSE_INPUT: t('planningProgress.descriptions.parseInput'),
  GEOCODE: t('planningProgress.descriptions.geocode'),
  BUILD_MODEL: t('planningProgress.descriptions.model'),
  SOLVE: t('planningProgress.descriptions.solve'),
  ROUTE: t('planningProgress.descriptions.route'),
  VERIFY_CITY: t('planningProgress.descriptions.verifyCity'),
  SAVE_RESULT: t('planningProgress.descriptions.saveResult'),
}))

const currentDescription = computed(() => {
  if (props.status === 'completed') return t('planningProgress.completedDesc')
  if (props.status === 'failed') return props.message || t('planningProgress.failedFallback')
  return stageDescriptions.value[normalizeStage(props.stage)] || t('planningProgress.preparing')
})

const stageStatuses = computed(() => {
  const currentIdx = stageIndex.value
  return stages.value.map((s, i) => {
    if (props.status === 'completed') return 'completed'
    if (props.status === 'failed' && i === currentIdx) return 'failed'
    if (i < currentIdx) return 'completed'
    if (i === currentIdx && props.status === 'running') return 'active'
    return 'pending'
  })
})

const progressWidth = computed(() => `${Math.min(100, Math.max(0, props.progress))}%`)

watch(
  () => props.status,
  (val) => {
    if (val === 'completed') {
      showConfetti.value = true
      setTimeout(() => {
        showConfetti.value = false
      }, 3000)
    }
  }
)
</script>

<template>
  <div class="min-h-screen flex items-center justify-center p-4 bg-gradient-to-br from-brand-50 via-white to-accent-50">
    <div class="relative w-full max-w-lg">
      <div
        v-if="showConfetti"
        class="absolute inset-0 pointer-events-none z-50 overflow-hidden"
      >
        <div
          v-for="i in 30"
          :key="i"
          class="confetti-piece"
          :style="{
            left: `${Math.random() * 100}%`,
            animationDelay: `${Math.random() * 2}s`,
            animationDuration: `${1.5 + Math.random() * 2}s`,
            backgroundColor: ['#3b6cf7', '#d946ef', '#22c55e', '#f59e0b', '#ef4444'][Math.floor(Math.random() * 5)],
            width: `${6 + Math.random() * 6}px`,
            height: `${6 + Math.random() * 6}px`,
          }"
        />
      </div>

      <div class="glass rounded-3xl p-8 shadow-panel">
        <div class="text-center">
          <div class="relative inline-flex items-center justify-center mb-6">
            <div
              v-if="status === 'completed'"
              class="h-20 w-20 rounded-full bg-success-100 flex items-center justify-center animate-bounce-in"
            >
              <svg class="h-10 w-10 text-success-600" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2.5">
                <path stroke-linecap="round" stroke-linejoin="round" d="M5 13l4 4L19 7" />
              </svg>
            </div>
            <div
              v-else-if="status === 'failed'"
              class="h-20 w-20 rounded-full bg-danger-100 flex items-center justify-center animate-bounce-in"
            >
              <svg class="h-10 w-10 text-danger-600" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2.5">
                <path stroke-linecap="round" stroke-linejoin="round" d="M6 18L18 6M6 6l12 12" />
              </svg>
            </div>
            <div
              v-else
              class="h-20 w-20 rounded-full bg-brand-100 flex items-center justify-center"
            >
              <svg
                class="h-10 w-10 text-brand-600 animate-spin"
                style="animation-duration: 3s;"
                fill="none"
                viewBox="0 0 24 24"
              >
                <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="2" stroke-dasharray="56.5" stroke-dashoffset="14" stroke-linecap="round" />
                <path d="M12 3a9 9 0 019 9" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" />
              </svg>
            </div>
            <div
              v-if="status === 'running'"
              class="absolute inset-0 rounded-full border-2 border-brand-400/30 animate-ping"
              style="animation-duration: 2s;"
            />
          </div>

          <h2
            :class="[
              'text-3xl font-bold mb-2',
              status === 'completed' ? 'gradient-text' : '',
              status === 'failed' ? 'text-danger-600' : '',
              status === 'running' ? 'text-surface-900' : '',
            ]"
          >
            <template v-if="status === 'completed'">{{ t('planning.completed') }}</template>
            <template v-else-if="status === 'failed'">{{ t('planning.failed') }}</template>
            <template v-else>{{ t('planning.title') }}</template>
          </h2>

          <p class="text-surface-500 mb-8">{{ currentDescription }}</p>

          <div
            v-if="status !== 'completed' && status !== 'failed'"
            class="mb-8"
          >
            <div class="relative h-3 w-full rounded-full bg-surface-100 overflow-hidden">
              <div
                class="absolute inset-y-0 left-0 rounded-full progress-bar-animate"
                :style="{ width: progressWidth }"
              />
              <div
                class="absolute inset-0 rounded-full opacity-30 striped-pattern"
                :style="{ width: progressWidth }"
              />
            </div>
            <div class="mt-3 text-4xl font-extrabold gradient-text">
              {{ Math.round(progress) }}%
            </div>
          </div>

          <div
            v-if="status === 'running' || status === 'completed'"
            class="flex items-center justify-center gap-1 sm:gap-2 mb-6"
          >
            <div
              v-for="(s, idx) in stages"
              :key="s.key"
              class="flex flex-col items-center relative"
            >
              <div class="flex items-center">
                <div
                  :class="[
                    'relative z-10 flex h-9 w-9 sm:h-10 sm:w-10 items-center justify-center rounded-full border-2 text-xs sm:text-sm font-bold transition-all duration-500',
                    stageStatuses[idx] === 'pending' && 'border-surface-200 bg-white text-surface-400',
                    stageStatuses[idx] === 'active' && 'border-brand-400 bg-brand-50 text-brand-600 shadow-lg shadow-brand-500/30',
                    stageStatuses[idx] === 'completed' && 'border-success-400 bg-success-50 text-success-600',
                    stageStatuses[idx] === 'failed' && 'border-danger-400 bg-danger-50 text-danger-600',
                  ]"
                  :style="stageStatuses[idx] === 'active' ? 'animation: pulse-ring 2s ease-in-out infinite' : ''"
                >
                  <span v-if="stageStatuses[idx] === 'completed'">✓</span>
                  <span v-else-if="stageStatuses[idx] === 'failed'">✕</span>
                  <span v-else>{{ s.icon }}</span>
                  <span
                    v-if="stageStatuses[idx] === 'active'"
                    class="absolute inset-0 rounded-full border-2 border-brand-400 animate-ping opacity-30"
                  />
                </div>
              </div>
              <span
                :class="[
                  'mt-1.5 text-[10px] sm:text-xs font-medium text-center transition-colors duration-300 hidden sm:block',
                  stageStatuses[idx] === 'pending' && 'text-surface-400',
                  stageStatuses[idx] === 'active' && 'text-brand-600',
                  stageStatuses[idx] === 'completed' && 'text-success-600',
                  stageStatuses[idx] === 'failed' && 'text-danger-600',
                ]"
              >{{ s.label }}</span>
              <div
                v-if="idx < stages.length - 1"
                :class="[
                  'absolute top-4 sm:top-5 left-1/2 h-0.5 w-full -translate-y-1/2',
                  stageStatuses[idx] === 'completed' ? 'bg-success-400' : 'bg-surface-200',
                ]"
              />
            </div>
          </div>

          <button
            v-if="status === 'failed'"
            class="btn-primary"
            @click="emit('retry')"
          >
            <svg class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
              <path stroke-linecap="round" stroke-linejoin="round" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
            </svg>
            {{ t('planningProgress.replan') }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
@keyframes pulse-ring {
  0%, 100% { box-shadow: 0 0 0 0 rgba(59, 108, 247, 0.4); }
  50% { box-shadow: 0 0 0 10px rgba(59, 108, 247, 0); }
}

@keyframes confetti-fall {
  0% { transform: translateY(-10vh) rotate(0deg); opacity: 1; }
  100% { transform: translateY(100vh) rotate(720deg); opacity: 0; }
}

.confetti-piece {
  position: absolute;
  top: -10px;
  border-radius: 2px;
  animation: confetti-fall 3s ease-in forwards;
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
