<script setup lang="ts">
import { ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { tripApi } from '@/api/trip'
import { useToast } from '@/composables/useToast'
import { downloadFile } from '@/utils/download'

const props = defineProps<{
  tripId: string
}>()

const emit = defineEmits<{
  close: []
}>()

const toast = useToast()
const { t } = useI18n()
const exporting = ref(false)
const selectedFormat = ref<'json' | 'pdf' | 'ics'>('pdf')
const includeMap = ref(true)
const includeStats = ref(true)

const formats = computed(() => [
  { value: 'json' as const, label: 'JSON', icon: '{ }', desc: t('export.descJson') },
  { value: 'pdf' as const, label: 'PDF', icon: '📄', desc: t('export.descPdf') },
  { value: 'ics' as const, label: 'ICS', icon: '📅', desc: t('export.descIcs') },
])

async function handleExport() {
  exporting.value = true
  try {
    const result = await tripApi.exportTrip(props.tripId, {
      format: selectedFormat.value,
      includeMap: includeMap.value,
      includeStats: includeStats.value,
    })

    if (!result.downloadUrl) {
      toast.error(t('export.failNoUrl'))
      return
    }
    await downloadFile(result.downloadUrl, result.filename)
    toast.success(t('export.exported', { name: result.filename }))
    emit('close')
  } catch (error: any) {
    toast.error(error?.message || t('export.failed'))
  } finally {
    exporting.value = false
  }
}
</script>

<template>
  <div class="fixed inset-0 z-50 flex items-center justify-center">
    <div class="absolute inset-0 bg-black/40 backdrop-blur-sm" @click="emit('close')" />
    <div class="relative z-10 w-full max-w-md rounded-2xl bg-white p-6 shadow-panel dark:bg-surface-800 animate-bounce-in">
      <div class="mb-6 flex items-center justify-between">
        <h3 class="text-lg font-bold text-surface-900 dark:text-white">{{ t('export.title') }}</h3>
        <button
          class="flex h-8 w-8 items-center justify-center rounded-lg text-surface-400 transition-colors hover:bg-surface-100 hover:text-surface-600 dark:hover:bg-surface-700"
          @click="emit('close')"
        >
          <svg class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
            <path stroke-linecap="round" stroke-linejoin="round" d="M6 18L18 6M6 6l12 12" />
          </svg>
        </button>
      </div>

      <div class="space-y-4">
        <div>
          <label class="mb-2 block text-sm font-medium text-surface-700 dark:text-surface-300">{{ t('export.formatLabel') }}</label>
          <div class="grid grid-cols-3 gap-2">
            <button
              v-for="fmt in formats"
              :key="fmt.value"
              :class="[
                'rounded-xl border-2 p-3 text-center transition-all',
                selectedFormat === fmt.value
                  ? 'border-brand-500 bg-brand-50 dark:bg-brand-900/20'
                  : 'border-surface-200 hover:border-surface-300 dark:border-surface-600',
              ]"
              @click="selectedFormat = fmt.value"
            >
              <div class="mb-1 text-2xl">{{ fmt.icon }}</div>
              <div class="text-sm font-semibold text-surface-800 dark:text-white">{{ fmt.label }}</div>
              <div class="mt-0.5 text-[10px] text-surface-400">{{ fmt.desc }}</div>
            </button>
          </div>
        </div>

        <div v-if="selectedFormat !== 'json'" class="space-y-3">
          <label class="flex items-center gap-3">
            <input
              v-model="includeMap"
              type="checkbox"
              class="h-4 w-4 rounded border-surface-300 text-brand-500 focus:ring-brand-500"
            />
            <span class="text-sm text-surface-700 dark:text-surface-300">{{ t('export.includeMap') }}</span>
          </label>
          <label class="flex items-center gap-3">
            <input
              v-model="includeStats"
              type="checkbox"
              class="h-4 w-4 rounded border-surface-300 text-brand-500 focus:ring-brand-500"
            />
            <span class="text-sm text-surface-700 dark:text-surface-300">{{ t('export.includeStats') }}</span>
          </label>
        </div>

        <button
          :disabled="exporting"
          class="btn-primary w-full"
          @click="handleExport"
        >
          <svg v-if="exporting" class="h-4 w-4 animate-spin" fill="none" viewBox="0 0 24 24">
            <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4" />
            <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
          </svg>
          {{ exporting ? t('export.exporting') : t('export.exportAction', { format: selectedFormat.toUpperCase() }) }}
        </button>
      </div>
    </div>
  </div>
</template>
