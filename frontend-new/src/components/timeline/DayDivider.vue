<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'

const { t, locale } = useI18n()

const props = defineProps<{
  dayNumber: number
  dateStr: string
}>()

const isVisible = ref(false)

onMounted(() => {
  requestAnimationFrame(() => {
    isVisible.value = true
  })
})

const formattedDate = computed(() => {
  if (!props.dateStr) return ''
  const date = new Date(props.dateStr)
  return date.toLocaleDateString(locale.value, {
    month: 'long',
    day: 'numeric',
    weekday: 'short',
  })
})
</script>

<template>
  <div class="relative py-8 flex items-center justify-center">
    <div class="absolute inset-x-0 top-1/2 h-px">
      <div
        :class="[
          'h-full bg-gradient-to-r from-transparent via-surface-300 to-transparent dark:via-surface-600 transition-all duration-700 ease-out',
          isVisible ? 'opacity-100 scale-x-100' : 'opacity-0 scale-x-0'
        ]"
      />
    </div>

    <div
      :class="[
        'relative z-10 flex items-center gap-3 px-5 py-2.5 rounded-full bg-white dark:bg-surface-800 border border-surface-200 dark:border-surface-700 shadow-sm transition-all duration-500 ease-out',
        isVisible ? 'opacity-100 translate-y-0' : 'opacity-0 translate-y-2'
      ]"
    >
      <div class="flex items-center justify-center w-8 h-8 rounded-lg bg-gradient-to-br from-brand-500 to-accent-500 text-white text-sm font-bold shadow-glow">
        D{{ dayNumber }}
      </div>
      <div class="flex flex-col">
        <span class="text-sm font-semibold text-surface-900 dark:text-white">
          {{ t('timeline.day', { n: dayNumber }) }}
        </span>
        <span class="text-xs text-surface-500 dark:text-surface-400">
          {{ formattedDate }}
        </span>
      </div>
    </div>
  </div>
</template>
