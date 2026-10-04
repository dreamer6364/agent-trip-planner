<script setup lang="ts">
import { ref, reactive, watch, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { CreateTripRequest } from '@/api/types'

const { t } = useI18n()

interface TripFormData {
  title: string
  rawInput: string
  timeStart: string
  timeEnd: string
  transportMode: string
  pace: string
  /** 用户可选目的城市（v1.15.0） */
  city?: string
}

const props = defineProps<{
  initialData?: Partial<TripFormData>
  loading?: boolean
}>()

const emit = defineEmits<{
  submit: [data: CreateTripRequest]
  draft: [data: CreateTripRequest]
}>()

const form = reactive<TripFormData>({
  title: '',
  rawInput: '',
  timeStart: '',
  timeEnd: '',
  transportMode: 'mixed',
  pace: 'moderate',
  city: '',
  ...props.initialData,
})

const isInputFocused = ref(false)

/** 活动频率：hours 为每日游览时长（不含用餐与交通），visits 为每日游览景点数 */
const paceOptions = computed(() => [
  { value: 'compact', label: t('tripCreate.pace.compact'), hours: t('tripCreate.pace.compactHours'), visits: t('tripCreate.pace.compactVisits'), icon: 'M13 10V3L4 14h7v7l9-11h-7z' },
  { value: 'moderate', label: t('tripCreate.pace.moderate'), hours: t('tripCreate.pace.moderateHours'), visits: t('tripCreate.pace.moderateVisits'), icon: 'M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2' },
  { value: 'relaxed', label: t('tripCreate.pace.relaxed'), hours: t('tripCreate.pace.relaxedHours'), visits: t('tripCreate.pace.relaxedVisits'), icon: 'M4 6h16M4 12h16M4 18h7' },
])

const transportModes = computed(() => [
  { value: 'mixed', label: t('trip.mixed'), icon: 'M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15' },
  { value: 'walk', label: t('trip.walk'), icon: 'M13 7a2 2 0 100-4 2 2 0 000 4zm-3 4l-2 6h3l1-4 2 2v4h2v-5l-2-2 1-3h-2l-1 1-1-3h2l1 2z' },
  { value: 'transit', label: t('trip.transit'), icon: 'M8 17h.01M16 17h.01M3 11l1.5-5A2 2 0 016.4 4h11.2a2 2 0 011.9 1.4L21 11M3 11h18M3 11v6a1 1 0 001 1h1a1 1 0 001-1v-1h12v1a1 1 0 001 1h1a1 1 0 001-1v-6' },
  { value: 'drive', label: t('trip.drive'), icon: 'M9 17a1 1 0 100-2 1 1 0 000 2zm6 0a1 1 0 100-2 1 1 0 000 2zM5 17H3v-4l2-5h10l2 5v4h-2m-10 0h10M5 8V6a1 1 0 011-1h8a1 1 0 011 1v2' },
  { value: 'bike', label: t('trip.bike'), icon: 'M5 17h2m10 0h2M7 17a3 3 0 110-6 3 3 0 010 6zm10 0a3 3 0 110-6 3 3 0 010 6zM5 11l3-6h4l2 6' },
])

watch(() => props.initialData, (val) => {
  if (val) {
    Object.assign(form, val)
  }
}, { deep: true })

function buildPayload(): CreateTripRequest | null {
  if (!form.rawInput.trim() || !form.timeStart || !form.timeEnd) return null
  const payload: CreateTripRequest = {
    title: form.title || t('tripForm.defaultTitle'),
    rawInput: form.rawInput,
    timeStart: form.timeStart,
    timeEnd: form.timeEnd,
    transportMode: form.transportMode,
    pace: form.pace || 'moderate',
  }
  const city = (form.city || '').trim()
  if (city) {
    payload.city = city
  }
  return payload
}

function handleSubmit() {
  const payload = buildPayload()
  if (!payload) return
  emit('submit', payload)
}

function handleSaveDraft() {
  const payload = buildPayload()
  if (!payload) return
  emit('draft', payload)
}

function setToday() {
  const today = new Date()
  today.setHours(8, 0, 0, 0)
  form.timeStart = today.toISOString().slice(0, 16)
}

function setTomorrow() {
  const tomorrow = new Date()
  tomorrow.setDate(tomorrow.getDate() + 1)
  tomorrow.setHours(20, 0, 0, 0)
  form.timeEnd = tomorrow.toISOString().slice(0, 16)
}
</script>

<template>
  <form @submit.prevent="handleSubmit" class="space-y-6">
    <div class="space-y-2">
      <label class="block text-sm font-semibold text-surface-700 dark:text-surface-300">
        {{ t('tripForm.descriptionLabel') }}
      </label>
      <div class="relative">
        <div
          :class="[
            'absolute -inset-0.5 rounded-2xl bg-gradient-to-r from-brand-500 via-accent-500 to-brand-500 bg-[length:200%_200%] transition-all duration-500',
            isInputFocused ? 'opacity-100 animate-gradient' : 'opacity-0'
          ]"
        />
        <textarea
          v-model="form.rawInput"
          rows="4"
          :placeholder="t('tripForm.descriptionPlaceholder')"
          class="relative w-full rounded-xl border border-surface-200 dark:border-surface-600 bg-white dark:bg-surface-800 px-4 py-3 text-surface-900 dark:text-white placeholder:text-surface-400 focus:outline-none focus:ring-0 resize-none transition-colors duration-200"
          @focus="isInputFocused = true"
          @blur="isInputFocused = false"
        />
        <div class="absolute bottom-3 right-3 flex items-center gap-1 text-xs text-surface-400">
          <svg class="w-4 h-4 text-brand-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9.663 17h4.673M12 3v1m6.364 1.636l-.707.707M21 12h-1M4 12H3m3.343-5.657l-.707-.707m2.828 9.9a5 5 0 117.072 0l-.548.547A3.374 3.374 0 0014 18.469V19a2 2 0 11-4 0v-.531c0-.895-.356-1.754-.988-2.386l-.548-.547z" />
          </svg>
          <span>{{ t('tripForm.aiParse') }}</span>
        </div>
      </div>
    </div>

    <div class="space-y-2">
      <label class="block text-sm font-semibold text-surface-700 dark:text-surface-300">
        {{ t('trip.transportMode') }}
      </label>
      <div class="grid grid-cols-5 gap-2">
        <button
          v-for="mode in transportModes"
          :key="mode.value"
          type="button"
          :class="[
            'flex flex-col items-center gap-1.5 p-3 rounded-xl border-2 transition-all duration-200',
            form.transportMode === mode.value
              ? 'border-brand-500 bg-brand-50 dark:bg-brand-900/30 text-brand-700 dark:text-brand-300 shadow-glow'
              : 'border-surface-200 dark:border-surface-600 bg-white dark:bg-surface-800 text-surface-600 dark:text-surface-400 hover:border-surface-300 dark:hover:border-surface-500'
          ]"
          @click="form.transportMode = mode.value"
        >
          <svg class="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" :d="mode.icon" />
          </svg>
          <span class="text-xs font-medium">{{ mode.label }}</span>
        </button>
      </div>
    </div>

    <div class="space-y-2">
      <label class="block text-sm font-semibold text-surface-700 dark:text-surface-300">
        {{ t('tripCreate.pace.label') }}
      </label>
      <div class="grid grid-cols-3 gap-2">
        <button
          v-for="option in paceOptions"
          :key="option.value"
          type="button"
          :class="[
            'flex flex-col items-start gap-1 p-3 rounded-xl border-2 text-left transition-all duration-200',
            form.pace === option.value
              ? 'border-brand-500 bg-brand-50 dark:bg-brand-900/30 text-brand-700 dark:text-brand-300 shadow-glow'
              : 'border-surface-200 dark:border-surface-600 bg-white dark:bg-surface-800 text-surface-600 dark:text-surface-400 hover:border-surface-300 dark:hover:border-surface-500'
          ]"
          @click="form.pace = option.value"
        >
          <span class="flex items-center gap-1.5">
            <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" :d="option.icon" />
            </svg>
            <span class="text-sm font-semibold">{{ option.label }}</span>
          </span>
          <span class="text-xs font-medium">{{ option.hours }}</span>
          <span class="text-xs text-surface-400 dark:text-surface-500">{{ option.visits }}</span>
        </button>
      </div>
      <p class="text-xs text-surface-400">{{ t('tripCreate.pace.hint') }}</p>
    </div>

    <div class="grid grid-cols-2 gap-4">
      <div class="space-y-2">
        <label class="block text-sm font-semibold text-surface-700 dark:text-surface-300">
          {{ t('tripForm.startTime') }}
        </label>
        <input
          v-model="form.timeStart"
          type="datetime-local"
          class="w-full rounded-xl border border-surface-200 dark:border-surface-600 bg-white dark:bg-surface-800 px-4 py-2.5 text-surface-900 dark:text-white focus:outline-none focus:ring-2 focus:ring-brand-500 focus:border-transparent transition-all"
        />
      </div>
      <div class="space-y-2">
        <label class="block text-sm font-semibold text-surface-700 dark:text-surface-300">
          {{ t('tripForm.endTime') }}
        </label>
        <input
          v-model="form.timeEnd"
          type="datetime-local"
          class="w-full rounded-xl border border-surface-200 dark:border-surface-600 bg-white dark:bg-surface-800 px-4 py-2.5 text-surface-900 dark:text-white focus:outline-none focus:ring-2 focus:ring-brand-500 focus:border-transparent transition-all"
        />
      </div>
    </div>

    <div class="grid grid-cols-2 gap-4">
      <div class="space-y-2">
        <label class="block text-sm font-semibold text-surface-700 dark:text-surface-300">
          {{ t('trip.title') }}
        </label>
        <input
          v-model="form.title"
          type="text"
          :placeholder="t('tripForm.titlePlaceholder')"
          class="w-full rounded-xl border border-surface-200 dark:border-surface-600 bg-white dark:bg-surface-800 px-4 py-2.5 text-surface-900 dark:text-white placeholder:text-surface-400 focus:outline-none focus:ring-2 focus:ring-brand-500 focus:border-transparent transition-all"
        />
      </div>
      <div class="space-y-2">
        <label class="block text-sm font-semibold text-surface-700 dark:text-surface-300">
          {{ t('tripCreate.cityLabel') }}
        </label>
        <input
          v-model="form.city"
          type="text"
          :placeholder="t('tripCreate.cityPlaceholder')"
          class="w-full rounded-xl border border-surface-200 dark:border-surface-600 bg-white dark:bg-surface-800 px-4 py-2.5 text-surface-900 dark:text-white placeholder:text-surface-400 focus:outline-none focus:ring-2 focus:ring-brand-500 focus:border-transparent transition-all"
        />
      </div>
    </div>

    <div class="flex flex-col sm:flex-row gap-3">
      <button
        type="submit"
        :disabled="loading || !form.rawInput.trim() || !form.timeStart || !form.timeEnd"
        :class="[
          'relative flex-1 py-3 px-6 rounded-xl font-semibold text-white transition-all duration-300 overflow-hidden',
          loading || !form.rawInput.trim() || !form.timeStart || !form.timeEnd
            ? 'bg-surface-300 dark:bg-surface-600 cursor-not-allowed'
            : 'bg-gradient-to-r from-brand-600 to-accent-600 hover:from-brand-700 hover:to-accent-700 shadow-lg shadow-brand-500/25 hover:shadow-xl hover:shadow-brand-500/30 active:scale-[0.98]'
        ]"
      >
        <span v-if="loading" class="absolute inset-0 flex items-center justify-center bg-brand-600/80">
          <svg class="animate-spin h-5 w-5 text-white" fill="none" viewBox="0 0 24 24">
            <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4" />
            <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
          </svg>
          <span class="ml-2">{{ t('tripForm.planning') }}</span>
        </span>
        <span v-else class="flex items-center justify-center gap-2">
          <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 10V3L4 14h7v7l9-11h-7z" />
          </svg>
          {{ t('tripForm.startPlan') }}
        </span>
      </button>

      <button
        type="button"
        :disabled="loading || !form.rawInput.trim() || !form.timeStart || !form.timeEnd"
        :class="[
          'flex-1 py-3 px-6 rounded-xl font-semibold transition-all duration-300',
          loading || !form.rawInput.trim() || !form.timeStart || !form.timeEnd
            ? 'bg-surface-100 dark:bg-surface-800 text-surface-400 cursor-not-allowed'
            : 'bg-surface-100 dark:bg-surface-800 text-surface-700 dark:text-surface-300 hover:bg-surface-200 dark:hover:bg-surface-700 active:scale-[0.98]'
        ]"
        @click="handleSaveDraft"
      >
        <span class="flex items-center justify-center gap-2">
          <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 7H5a2 2 0 00-2 2v9a2 2 0 002 2h14a2 2 0 002-2V9a2 2 0 00-2-2h-3m-1 4l-3 3m0 0l-3-3m3 3V4" />
          </svg>
          {{ t('tripForm.saveDraft') }}
        </span>
      </button>
    </div>
  </form>
</template>
