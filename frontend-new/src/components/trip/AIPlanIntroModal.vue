<script setup lang="ts">
import { ref, computed, watch, onUnmounted } from 'vue'
import UIModal from '@/components/ui/UIModal.vue'

interface Props {
  open: boolean
  loading?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  loading: false,
})

const emit = defineEmits<{
  close: []
  confirm: []
}>()

const stages = [
  { title: '解析需求', desc: '提取城市、天数与旅行偏好', icon: 'ri-brain-line' },
  { title: '智能选点', desc: '筛选景点与餐厅，安排每日节奏', icon: 'ri-map-pin-2-line' },
  { title: '路线规划', desc: '基于高德地图计算真实交通距离与时长', icon: 'ri-route-line' },
  { title: '生成行程', desc: '输出完整行程与每日路线', icon: 'ri-file-text-line' },
]

const stageIdx = ref(0)
const elapsed = ref(0)
let stageTimer: number | null = null
let elapsedTimer: number | null = null

function stopTimers() {
  if (stageTimer !== null) {
    clearInterval(stageTimer)
    stageTimer = null
  }
  if (elapsedTimer !== null) {
    clearInterval(elapsedTimer)
    elapsedTimer = null
  }
}

watch(
  () => props.loading,
  (loading) => {
    if (loading) {
      stopTimers()
      stageIdx.value = 0
      elapsed.value = 0
      stageTimer = window.setInterval(() => {
        if (stageIdx.value < stages.length - 1) stageIdx.value++
      }, 8000)
      elapsedTimer = window.setInterval(() => {
        elapsed.value++
      }, 1000)
    } else {
      stopTimers()
    }
  },
  { immediate: true }
)

onUnmounted(stopTimers)

const elapsedText = computed(() => {
  const s = elapsed.value
  if (s < 60) return `已用时 ${s} 秒`
  return `已用时 ${Math.floor(s / 60)} 分 ${s % 60} 秒`
})

function handleClose() {
  if (props.loading) return
  emit('close')
}
</script>

<template>
  <UIModal :open="open" size="md" @close="handleClose">
    <template #header>
      <div class="flex items-center gap-3">
        <div
          class="flex h-10 w-10 items-center justify-center rounded-xl bg-gradient-to-br from-brand-500 to-accent-500 shadow-lg shadow-brand-500/25"
        >
          <i class="ri-sparkling-line text-lg text-white"></i>
        </div>
        <div>
          <h3 class="text-lg font-semibold text-surface-900 dark:text-white">
            {{ loading ? 'AI 正在规划你的行程' : '开始 AI 智能规划？' }}
          </h3>
          <p class="text-xs text-surface-400">
            {{ loading ? '通常需要 30 秒 ~ 2 分钟' : '提交后 AI 将自动完成以下工作' }}
          </p>
        </div>
      </div>
    </template>

    <!-- Intro -->
    <div v-if="!loading" class="space-y-4">
      <ol class="space-y-3">
        <li
          v-for="(stage, index) in stages"
          :key="stage.title"
          class="flex items-start gap-3 rounded-xl border border-surface-100 dark:border-surface-700 bg-surface-50 dark:bg-surface-800/60 px-4 py-3"
        >
          <div
            class="flex h-7 w-7 flex-shrink-0 items-center justify-center rounded-full bg-brand-100 dark:bg-brand-900/40 text-sm font-semibold text-brand-600 dark:text-brand-300"
          >
            {{ index + 1 }}
          </div>
          <div class="min-w-0">
            <p class="text-sm font-medium text-surface-800 dark:text-surface-100">
              {{ stage.title }}
            </p>
            <p class="text-xs text-surface-500 dark:text-surface-400 mt-0.5">{{ stage.desc }}</p>
          </div>
        </li>
      </ol>

      <div class="flex items-start gap-2 rounded-xl bg-brand-50 dark:bg-brand-900/30 px-4 py-3">
        <i class="ri-time-line mt-0.5 text-brand-500"></i>
        <p class="text-xs leading-relaxed text-brand-700 dark:text-brand-300">
          预计耗时 <span class="font-semibold">30 秒 ~ 2 分钟</span>。规划期间请勿关闭页面，完成后将自动进入进度页查看结果。
        </p>
      </div>
    </div>

    <!-- Running -->
    <div v-else class="space-y-4">
      <div class="flex items-center justify-center gap-3 py-1">
        <svg class="h-6 w-6 animate-spin text-brand-500" fill="none" viewBox="0 0 24 24">
          <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4" />
          <path
            class="opacity-75"
            fill="currentColor"
            d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"
          />
        </svg>
        <span class="text-sm font-medium text-surface-700 dark:text-surface-200">
          正在生成你的行程 · {{ elapsedText }}
        </span>
      </div>

      <ol class="space-y-2">
        <li
          v-for="(stage, index) in stages"
          :key="stage.title"
          class="flex items-center gap-3 rounded-xl px-4 py-2.5 transition-colors duration-300"
          :class="
            index === stageIdx
              ? 'bg-brand-50 dark:bg-brand-900/30 ring-1 ring-brand-200 dark:ring-brand-700'
              : index < stageIdx
                ? 'bg-surface-50 dark:bg-surface-800/60'
                : 'opacity-50'
          "
        >
          <!-- done -->
          <span
            v-if="index < stageIdx"
            class="flex h-6 w-6 flex-shrink-0 items-center justify-center rounded-full bg-success-500 text-white"
          >
            <i class="ri-check-line text-sm"></i>
          </span>
          <!-- current -->
          <span
            v-else-if="index === stageIdx"
            class="flex h-6 w-6 flex-shrink-0 items-center justify-center rounded-full bg-brand-500 text-white"
          >
            <svg class="h-3.5 w-3.5 animate-spin" fill="none" viewBox="0 0 24 24">
              <circle
                class="opacity-25"
                cx="12"
                cy="12"
                r="10"
                stroke="currentColor"
                stroke-width="4"
              />
              <path
                class="opacity-75"
                fill="currentColor"
                d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"
              />
            </svg>
          </span>
          <!-- pending -->
          <span
            v-else
            class="h-6 w-6 flex-shrink-0 rounded-full border-2 border-surface-300 dark:border-surface-600"
          />
          <div class="min-w-0 flex items-center gap-2">
            <i
              :class="[stage.icon, index <= stageIdx ? 'text-brand-500' : 'text-surface-400']"
            ></i>
            <span
              class="text-sm"
              :class="
                index < stageIdx
                  ? 'text-surface-600 dark:text-surface-300'
                  : index === stageIdx
                    ? 'font-medium text-surface-900 dark:text-white'
                    : 'text-surface-400'
              "
            >
              {{ stage.title }}
            </span>
            <span class="hidden sm:inline text-xs text-surface-400 truncate">{{ stage.desc }}</span>
          </div>
        </li>
      </ol>

      <div class="flex items-start gap-2 rounded-xl bg-warning-50 dark:bg-warning-900/20 px-4 py-3">
        <i class="ri-error-warning-line mt-0.5 text-warning-500"></i>
        <p class="text-xs leading-relaxed text-warning-700 dark:text-warning-300">
          规划期间请勿关闭或刷新页面，否则可能中断生成。
        </p>
      </div>
    </div>

    <template #footer>
      <template v-if="!loading">
        <button
          class="btn-ghost px-5 py-2.5 text-sm"
          @click="emit('close')"
        >
          取消
        </button>
        <button
          class="btn-primary px-5 py-2.5 text-sm"
          @click="emit('confirm')"
        >
          <i class="ri-sparkling-line"></i>
          开始规划
        </button>
      </template>
      <span v-else class="text-xs text-surface-400 mr-auto">
        <i class="ri-lock-line mr-1"></i>规划中不可关闭
      </span>
    </template>
  </UIModal>
</template>
