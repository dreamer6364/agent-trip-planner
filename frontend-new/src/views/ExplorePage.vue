<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { tripApi } from '@/api/trip'
import type { Trip } from '@/api/types'
import TripCard from '@/components/trip/TripCard.vue'

const router = useRouter()
const { t } = useI18n()

const trips = ref<Trip[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(9)
const loading = ref(false)
const error = ref(false)
const keyword = ref('')

let debounceTimer: ReturnType<typeof setTimeout> | undefined

const totalPages = () => Math.max(1, Math.ceil(total.value / size.value))

async function load() {
  loading.value = true
  error.value = false
  try {
    const kw = keyword.value.trim()
    const data = await tripApi.getPublicTrips({
      page: page.value,
      size: size.value,
      ...(kw ? { keyword: kw } : {}),
    })
    trips.value = data.items || []
    total.value = data.total || 0
  } catch {
    error.value = true
    trips.value = []
  } finally {
    loading.value = false
  }
}

function searchNow() {
  if (debounceTimer) clearTimeout(debounceTimer)
  page.value = 1
  load()
}

function onSearchInput() {
  if (debounceTimer) clearTimeout(debounceTimer)
  debounceTimer = setTimeout(searchNow, 300)
}

function clearSearch() {
  keyword.value = ''
  searchNow()
}

function goToPage(p: number) {
  if (p < 1 || p > totalPages()) return
  page.value = p
  load()
}

function openTrip(id: string) {
  router.push(`/trips/${id}`)
}

onMounted(load)
</script>

<template>
  <div class="min-h-screen bg-gradient-to-br from-slate-50 via-white to-purple-50 dark:from-surface-900 dark:via-surface-900 dark:to-surface-800">
    <div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
      <div class="mb-8">
        <p class="text-sm font-medium text-accent-600 dark:text-accent-400 mb-1">{{ t('explore.kicker') }}</p>
        <h1 class="text-3xl sm:text-4xl font-bold text-surface-900 dark:text-white mb-2">{{ t('dashboard.publicTrips') }}</h1>
        <p class="text-surface-600 dark:text-surface-300">
          {{ t('explore.subtitle') }}
          <span v-if="total > 0" class="text-surface-400"> · {{ t('common.totalCount', { n: total }) }}</span>
        </p>
      </div>

      <div class="mb-6 flex flex-col sm:flex-row sm:items-center gap-3">
        <div class="relative w-full sm:max-w-md">
          <div class="absolute inset-y-0 left-0 pl-3.5 flex items-center pointer-events-none">
            <svg class="w-5 h-5 text-surface-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
            </svg>
          </div>
          <input
            v-model="keyword"
            type="text"
            :placeholder="t('explore.searchPlaceholder')"
            class="w-full pl-10 pr-10 py-2.5 rounded-xl border border-surface-200 dark:border-surface-600 bg-white dark:bg-surface-800 text-surface-900 dark:text-white placeholder:text-surface-400 focus:outline-none focus:ring-2 focus:ring-brand-500 focus:border-transparent transition-all"
            @input="onSearchInput"
            @keyup.enter="searchNow"
          />
          <button
            v-if="keyword"
            class="absolute inset-y-0 right-0 pr-3.5 flex items-center"
            :aria-label="t('explore.clearSearch')"
            @click="clearSearch"
          >
            <svg class="w-4 h-4 text-surface-400 hover:text-surface-600 dark:hover:text-surface-300 transition-colors" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>
        <p class="text-xs text-surface-400 dark:text-surface-500">{{ t('explore.searchHint') }}</p>
      </div>

      <div v-if="loading" class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
        <div
          v-for="i in 6"
          :key="'sk-' + i"
          class="bg-white dark:bg-surface-800 rounded-2xl shadow-sm overflow-hidden animate-pulse"
        >
          <div class="h-44 bg-surface-200 dark:bg-surface-700"></div>
          <div class="p-4 space-y-3">
            <div class="h-6 bg-surface-200 dark:bg-surface-700 rounded w-3/4"></div>
            <div class="h-4 bg-surface-200 dark:bg-surface-700 rounded w-1/2"></div>
            <div class="h-4 bg-surface-200 dark:bg-surface-700 rounded w-2/3"></div>
          </div>
        </div>
      </div>

      <div
        v-else-if="error"
        class="rounded-3xl border border-warning-200 bg-warning-50 dark:border-warning-800 dark:bg-warning-900/20 px-6 py-10 text-center"
      >
        <p class="text-warning-700 dark:text-warning-400 mb-4">{{ t('explore.loadError') }}</p>
        <button class="btn-secondary text-sm" @click="load">{{ t('common.retry') }}</button>
      </div>

      <div v-else-if="trips.length > 0" class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
        <TripCard
          v-for="trip in trips"
          :key="trip.id"
          :trip="trip"
          @open="openTrip"
        />
      </div>

      <div
        v-else
        class="flex flex-col items-center justify-center py-20 text-center rounded-3xl bg-white/70 dark:bg-surface-800/70 border border-surface-100 dark:border-surface-700"
      >
        <div class="w-28 h-28 mb-5 rounded-full bg-gradient-to-br from-accent-100 to-brand-100 flex items-center justify-center">
          <i class="ri-compass-3-line text-5xl text-accent-400"></i>
        </div>
        <template v-if="keyword.trim()">
          <h3 class="text-xl font-semibold text-surface-700 dark:text-surface-200 mb-2">{{ t('explore.noResultsTitle', { keyword: keyword.trim() }) }}</h3>
          <p class="text-surface-500 dark:text-surface-400 mb-6 max-w-md">{{ t('explore.noResultsDesc') }}</p>
          <button class="btn-secondary" @click="clearSearch">
            <i class="ri-close-line" />
            {{ t('explore.clearSearch') }}
          </button>
        </template>
        <template v-else>
          <h3 class="text-xl font-semibold text-surface-700 dark:text-surface-200 mb-2">{{ t('explore.emptyTitle') }}</h3>
          <p class="text-surface-500 dark:text-surface-400 mb-6 max-w-md">{{ t('explore.emptyDesc') }}</p>
          <button class="btn-primary" @click="router.push('/dashboard')">
            <i class="ri-route-line" />
            {{ t('explore.backToMyTrips') }}
          </button>
        </template>
      </div>

      <div
        v-if="!loading && !error && total > size"
        class="flex justify-center mt-10"
      >
        <nav class="flex items-center space-x-2">
          <button
            class="px-4 py-2 rounded-lg border border-surface-200 dark:border-surface-600 text-surface-600 dark:text-surface-300 hover:bg-surface-50 dark:hover:bg-surface-700 disabled:opacity-50 transition-colors"
            :disabled="page === 1"
            @click="goToPage(page - 1)"
          >
            <i class="ri-arrow-left-s-line"></i>
          </button>
          <button
            v-for="p in totalPages()"
            :key="p"
            class="w-10 h-10 rounded-lg font-medium transition-all"
            :class="p === page
              ? 'bg-gradient-to-r from-accent-500 to-brand-500 text-white shadow-lg'
              : 'bg-white dark:bg-surface-800 text-surface-600 dark:text-surface-300 border border-surface-200 dark:border-surface-600 hover:bg-surface-50 dark:hover:bg-surface-700'"
            @click="goToPage(p)"
          >
            {{ p }}
          </button>
          <button
            class="px-4 py-2 rounded-lg border border-surface-200 dark:border-surface-600 text-surface-600 dark:text-surface-300 hover:bg-surface-50 dark:hover:bg-surface-700 disabled:opacity-50 transition-colors"
            :disabled="page >= totalPages()"
            @click="goToPage(page + 1)"
          >
            <i class="ri-arrow-right-s-line"></i>
          </button>
        </nav>
      </div>
    </div>
  </div>
</template>
