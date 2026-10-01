<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { ParsedInput } from '@/api/types'

const props = defineProps<{
  parsedInput?: ParsedInput | null
  rawInput?: string
}>()

const { t } = useI18n()
const showRaw = ref(false)

const city = computed(() => (props.parsedInput?.city || '').trim())
const summary = computed(() => (props.parsedInput?.summary || '').trim())
const spots = computed(() => props.parsedInput?.places ?? [])
const meals = computed(() => props.parsedInput?.meals ?? [])

const quotes = computed(() => {
  const out: string[] = []
  for (const p of spots.value) {
    if (p.quote && !out.includes(p.quote)) out.push(p.quote)
  }
  for (const m of meals.value) {
    if (m.quote && !out.includes(m.quote)) out.push(m.quote)
  }
  return out
})

const hasContent = computed(
  () => Boolean(city.value || summary.value || spots.value.length || meals.value.length),
)
</script>

<template>
  <section
    v-if="hasContent"
    class="rounded-2xl border border-surface-100 bg-white p-4 shadow-sm dark:border-surface-700 dark:bg-surface-800"
  >
    <div class="mb-3 flex items-center justify-between gap-2">
      <h3 class="flex items-center gap-2 text-sm font-bold text-surface-900 dark:text-white">
        <span class="flex h-6 w-6 items-center justify-center rounded-lg bg-brand-50 dark:bg-brand-900/40">
          <i class="ri-sparkling-2-fill text-xs text-brand-600 dark:text-brand-400" />
        </span>
        {{ t('parseSummary.title') }}
      </h3>
      <button
        v-if="rawInput"
        type="button"
        class="text-xs font-medium text-brand-600 hover:text-brand-700 dark:text-brand-400"
        @click="showRaw = !showRaw"
      >
        {{ t('parseSummary.rawTitle') }}
      </button>
    </div>

    <!-- 识别城市 / 城市缺失提示（决策⑤） -->
    <div
      v-if="city"
      class="mb-3 inline-flex items-center gap-1.5 rounded-lg bg-emerald-50 px-2.5 py-1 text-xs font-semibold text-emerald-700 dark:bg-emerald-900/30 dark:text-emerald-400"
    >
      <i class="ri-map-pin-2-fill" />
      {{ t('parseSummary.cityLabel') }}: {{ city }}
    </div>
    <div
      v-else
      class="mb-3 flex items-start gap-1.5 rounded-lg bg-amber-50 px-2.5 py-2 text-xs leading-relaxed text-amber-700 dark:bg-amber-900/30 dark:text-amber-400"
    >
      <i class="ri-alert-line mt-0.5 shrink-0" />
      <span>{{ t('parseSummary.cityFallback') }}</span>
    </div>

    <!-- 解析摘要 -->
    <p v-if="summary" class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
      {{ summary }}
    </p>

    <!-- 景点/用餐 -->
    <div class="flex flex-wrap gap-1.5">
      <span
        v-if="spots.length"
        class="inline-flex items-center gap-1 rounded-md bg-blue-50 px-2 py-0.5 text-xs font-medium text-blue-700 dark:bg-blue-900/30 dark:text-blue-400"
      >
        <i class="ri-footprint-line" />
        {{ t('parseSummary.spots', { count: spots.length }) }}
      </span>
      <span
        v-for="p in spots"
        :key="'p-' + p.name"
        :title="p.quote ? `${t('parseSummary.quoteTip')}: ${p.quote}` : p.name"
        class="inline-flex cursor-help items-center rounded-md bg-surface-100 px-2 py-0.5 text-xs text-surface-600 dark:bg-surface-700 dark:text-surface-300"
      >
        {{ p.name }}
      </span>
      <span
        v-if="meals.length"
        class="inline-flex items-center gap-1 rounded-md bg-orange-50 px-2 py-0.5 text-xs font-medium text-orange-700 dark:bg-orange-900/30 dark:text-orange-400"
      >
        <i class="ri-restaurant-line" />
        {{ t('parseSummary.meals', { count: meals.length }) }}
      </span>
      <span
        v-for="m in meals"
        :key="'m-' + m.type + (m.preference || '')"
        class="inline-flex items-center rounded-md bg-surface-100 px-2 py-0.5 text-xs text-surface-600 dark:bg-surface-700 dark:text-surface-300"
      >
        {{ m.type }}{{ m.preference ? ` · ${m.preference}` : '' }}
      </span>
    </div>

    <!-- 原始描述 -->
    <div v-if="showRaw && rawInput" class="mt-3 border-t border-surface-100 pt-3 dark:border-surface-700">
      <p class="mb-1 text-[10px] font-medium uppercase tracking-wider text-surface-400">
        {{ t('parseSummary.rawTitle') }}
      </p>
      <p class="whitespace-pre-wrap text-xs leading-relaxed text-surface-500 dark:text-surface-400">
        {{ rawInput }}
      </p>
    </div>
  </section>
</template>
