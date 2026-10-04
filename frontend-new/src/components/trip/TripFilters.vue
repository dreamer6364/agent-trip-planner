<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()

const props = defineProps<{
  modelValue?: string
  statusFilter?: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
  'update:statusFilter': [value: string]
}>()

const searchValue = computed({
  get: () => props.modelValue ?? '',
  set: (val) => emit('update:modelValue', val),
})

const currentStatus = computed({
  get: () => props.statusFilter ?? 'all',
  set: (val) => emit('update:statusFilter', val),
})

const statusOptions = computed(() => [
  { value: 'all', label: t('dashboard.filterAll'), color: 'bg-surface-100 text-surface-700 dark:bg-surface-700 dark:text-surface-300', activeColor: 'bg-brand-500 text-white shadow-glow' },
  { value: 'draft', label: t('dashboard.filterDraft'), color: 'bg-surface-100 text-surface-600 dark:bg-surface-700 dark:text-surface-400', activeColor: 'bg-surface-600 text-white' },
  { value: 'completed', label: t('dashboard.filterCompleted'), color: 'bg-success-50 text-success-600 dark:bg-success-900/30 dark:text-success-400', activeColor: 'bg-success-500 text-white shadow-lg shadow-success-500/25' },
])
</script>

<template>
  <div class="flex flex-col sm:flex-row items-start sm:items-center gap-4">
    <div class="relative flex-1 w-full sm:max-w-md">
      <div class="absolute inset-y-0 left-0 pl-3.5 flex items-center pointer-events-none">
        <svg class="w-5 h-5 text-surface-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
        </svg>
      </div>
      <input
        v-model="searchValue"
        type="text"
        :placeholder="t('dashboard.search')"
        class="w-full pl-10 pr-4 py-2.5 rounded-xl border border-surface-200 dark:border-surface-600 bg-white dark:bg-surface-800 text-surface-900 dark:text-white placeholder:text-surface-400 focus:outline-none focus:ring-2 focus:ring-brand-500 focus:border-transparent transition-all"
      />
      <button
        v-if="searchValue"
        class="absolute inset-y-0 right-0 pr-3.5 flex items-center"
        @click="searchValue = ''"
      >
        <svg class="w-4 h-4 text-surface-400 hover:text-surface-600 dark:hover:text-surface-300 transition-colors" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
        </svg>
      </button>
    </div>

    <div class="flex items-center gap-1.5 p-1 rounded-xl bg-surface-100 dark:bg-surface-800">
      <button
        v-for="option in statusOptions"
        :key="option.value"
        :class="[
          'px-3 py-1.5 rounded-lg text-xs font-medium transition-all duration-200',
          currentStatus === option.value
            ? option.activeColor
            : option.color + ' hover:opacity-80'
        ]"
        @click="currentStatus = option.value"
      >
        {{ option.label }}
      </button>
    </div>
  </div>
</template>
