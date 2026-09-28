<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useTripStore } from '@/stores/trip'
import { useAuthStore } from '@/stores/auth'
import { tripApi } from '@/api/trip'
import type { Trip } from '@/api/types'
import TripCard from '@/components/trip/TripCard.vue'
import TripFilters from '@/components/trip/TripFilters.vue'

const router = useRouter()
const tripStore = useTripStore()
const authStore = useAuthStore()

const isLoading = ref(true)
const currentPage = ref(1)
const pageSize = ref(9)
const selectedStatus = ref('')
const searchQuery = ref('')

const publicTrips = ref<Trip[]>([])
const publicTotal = ref(0)
const publicLoading = ref(false)

const userName = computed(() => authStore.userName || '旅行者')
const trips = computed(() => tripStore.trips)
const totalCount = computed(() => tripStore.total)
const planningTrip = computed(() => tripStore.trips.find(trip => trip.status === 'planning'))
const recentTrip = computed(() => tripStore.trips[0])

const quickActions = computed(() => [
  {
    key: 'create',
    title: '新建行程',
    description: '一句话描述，AI 立即生成完整行程',
    icon: 'ri-add-circle-line',
    accent: 'from-brand-500 via-brand-500 to-cyan-400',
    chip: '立即开始',
    route: '/trips/create',
    enabled: true,
  },
  {
    key: 'continue',
    title: '继续行程',
    description: planningTrip.value?.title || (recentTrip.value?.title ? `打开「${recentTrip.value.title}」` : '暂无进行中的行程'),
    icon: planningTrip.value ? 'ri-loader-4-line' : 'ri-route-line',
    accent: 'from-indigo-500 via-blue-500 to-sky-400',
    chip: planningTrip.value ? '规划中' : (recentTrip.value ? '最近打开' : '去创建'),
    route: planningTrip.value
      ? `/trips/${planningTrip.value.id}/planning`
      : (recentTrip.value ? `/trips/${recentTrip.value.id}` : '/trips/create'),
    enabled: true,
  },
  {
    key: 'public',
    title: '公开行程',
    description: publicTotal.value > 0
      ? `发现 ${publicTotal.value} 条社区行程`
      : '浏览其他旅行者的灵感行程',
    icon: 'ri-compass-3-line',
    accent: 'from-accent-500 via-purple-500 to-pink-500',
    chip: publicTotal.value > 0 ? '去发现' : '探索中',
    route: '/explore',
    enabled: true,
  },
])

const totalPages = computed(() => Math.ceil(totalCount.value / pageSize.value))

const fetchTrips = async () => {
  isLoading.value = true
  try {
    await tripStore.fetchTrips({
      page: currentPage.value,
      size: pageSize.value,
      status: selectedStatus.value && selectedStatus.value !== 'all' ? selectedStatus.value : undefined,
      keyword: searchQuery.value || undefined
    })
  } catch (error) {
    console.error('Failed to fetch trips:', error)
  } finally {
    isLoading.value = false
  }
}

async function fetchPublicPreview() {
  publicLoading.value = true
  try {
    const data = await tripApi.getPublicTrips({ page: 1, size: 3 })
    publicTrips.value = data.items || []
    publicTotal.value = data.total || 0
  } catch {
    publicTrips.value = []
    publicTotal.value = 0
  } finally {
    publicLoading.value = false
  }
}

const handlePageChange = (page: number) => {
  currentPage.value = page
  fetchTrips()
}

const handleFilterChange = (filters: { status?: string; search?: string }) => {
  if (filters.status !== undefined) selectedStatus.value = filters.status
  if (filters.search !== undefined) searchQuery.value = filters.search
  currentPage.value = 1
  fetchTrips()
}

const navigateToTrip = (tripId: string) => {
  router.push(`/trips/${tripId}`)
}

function onTripDeleted() {
  if (trips.value.length === 0 && currentPage.value > 1) {
    currentPage.value -= 1
  }
  fetchTrips()
  fetchPublicPreview()
}

onMounted(() => {
  fetchTrips()
  fetchPublicPreview()
})
</script>

<template>
  <div class="min-h-screen bg-gradient-to-br from-slate-50 via-white to-blue-50 dark:from-surface-900 dark:via-surface-900 dark:to-surface-800">
    <div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
      <!-- Greeting -->
      <div class="mb-8">
        <p class="text-sm font-medium text-brand-600 dark:text-brand-400 mb-1">行程工作台</p>
        <h1 class="text-3xl sm:text-4xl font-bold mb-2 text-surface-900 dark:text-white">
          你好，{{ userName }}
          <span class="ml-1">👋</span>
        </h1>
        <p class="text-surface-600 dark:text-surface-300">今天想规划什么旅行？</p>
      </div>

      <!-- Quick Actions -->
      <div class="grid grid-cols-1 md:grid-cols-3 gap-4 sm:gap-5 mb-10">
        <button
          v-for="action in quickActions"
          :key="action.key"
          type="button"
          class="group relative overflow-hidden rounded-3xl p-6 text-left cursor-pointer transform transition-all duration-300 hover:-translate-y-1.5 hover:shadow-panel focus:outline-none focus:ring-2 focus:ring-brand-400/50"
          :class="`bg-gradient-to-br ${action.accent}`"
          @click="router.push(action.route)"
        >
          <div class="absolute inset-0 bg-white/10 opacity-0 group-hover:opacity-100 transition-opacity duration-300" />
          <div class="absolute -top-10 -right-10 w-36 h-36 rounded-full bg-white/10 group-hover:scale-125 transition-transform duration-500" />
          <div class="absolute -bottom-8 -left-6 w-24 h-24 rounded-full bg-white/10" />

          <div class="relative z-10 flex h-full flex-col justify-between min-h-[9.5rem]">
            <div class="flex items-start justify-between gap-3">
              <div class="flex h-11 w-11 items-center justify-center rounded-2xl bg-white/20 backdrop-blur-sm">
                <i :class="action.icon" class="text-2xl text-white" />
              </div>
              <span class="inline-flex items-center rounded-full bg-white/20 px-2.5 py-1 text-[11px] font-semibold text-white backdrop-blur-sm">
                {{ action.chip }}
              </span>
            </div>
            <div class="mt-5">
              <h3 class="text-lg font-bold text-white mb-1">{{ action.title }}</h3>
              <p class="text-sm text-white/85 line-clamp-2 leading-relaxed">{{ action.description }}</p>
              <div class="mt-3 flex items-center gap-1 text-xs font-semibold text-white/90">
                立即前往
                <svg class="h-3.5 w-3.5 transition-transform group-hover:translate-x-1" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                  <path stroke-linecap="round" stroke-linejoin="round" d="M9 5l7 7-7 7" />
                </svg>
              </div>
            </div>
          </div>
        </button>
      </div>

      <!-- My Trips Header -->
      <div class="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4 mb-6">
        <div>
          <h2 class="text-xl font-bold text-surface-900 dark:text-white">我的行程</h2>
          <p class="text-sm text-surface-500 dark:text-surface-400 mt-0.5" v-if="!isLoading">
            共 {{ totalCount }} 条行程
          </p>
        </div>
        <TripFilters
          :modelValue="searchQuery"
          :statusFilter="selectedStatus"
          @update:modelValue="searchQuery = $event; handleFilterChange({ search: $event })"
          @update:statusFilter="selectedStatus = $event; handleFilterChange({ status: $event })"
        />
      </div>

      <!-- Loading Skeleton -->
      <div v-if="isLoading" class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
        <div
          v-for="i in 6"
          :key="i"
          class="bg-white dark:bg-surface-800 rounded-2xl shadow-sm overflow-hidden animate-pulse"
        >
          <div class="h-44 bg-surface-200 dark:bg-surface-700"></div>
          <div class="p-4">
            <div class="h-6 bg-surface-200 dark:bg-surface-700 rounded w-3/4 mb-3"></div>
            <div class="h-4 bg-surface-200 dark:bg-surface-700 rounded w-1/2 mb-2"></div>
            <div class="h-4 bg-surface-200 dark:bg-surface-700 rounded w-2/3"></div>
          </div>
        </div>
      </div>

      <!-- Trip Grid -->
      <div
        v-else-if="trips.length > 0"
        class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6"
      >
        <TripCard
          v-for="trip in trips"
          :key="trip.id"
          :trip="trip"
          @open="navigateToTrip(trip.id)"
          @deleted="onTripDeleted"
        />
      </div>

      <!-- Empty State -->
      <div
        v-else
        class="flex flex-col items-center justify-center py-16 text-center rounded-3xl bg-white/70 dark:bg-surface-800/70 border border-surface-100 dark:border-surface-700"
      >
        <div class="w-32 h-32 mb-6 rounded-full bg-gradient-to-br from-brand-100 to-accent-100 flex items-center justify-center">
          <i class="ri-map-2-line text-5xl text-brand-400"></i>
        </div>
        <h3 class="text-xl font-semibold text-surface-700 dark:text-surface-200 mb-2">还没有行程</h3>
        <p class="text-surface-500 dark:text-surface-400 mb-6 max-w-md">开始规划你的第一段旅程吧</p>
        <button
          class="btn-primary"
          @click="router.push('/trips/create')"
        >
          <i class="ri-add-circle-line" />
          开始规划
        </button>
      </div>

      <!-- Pagination -->
      <div
        v-if="!isLoading && totalPages > 1"
        class="flex justify-center mt-10"
      >
        <nav class="flex items-center space-x-2">
          <button
            @click="handlePageChange(currentPage - 1)"
            :disabled="currentPage === 1"
            class="px-4 py-2 rounded-lg border border-surface-200 dark:border-surface-600 text-surface-600 dark:text-surface-300 hover:bg-surface-50 dark:hover:bg-surface-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors duration-200"
          >
            <i class="ri-arrow-left-s-line"></i>
          </button>
          <button
            v-for="page in totalPages"
            :key="page"
            @click="handlePageChange(page)"
            :class="[
              'w-10 h-10 rounded-lg font-medium transition-all duration-200',
              currentPage === page
                ? 'bg-gradient-to-r from-brand-500 to-accent-500 text-white shadow-lg'
                : 'bg-white dark:bg-surface-800 text-surface-600 dark:text-surface-300 hover:bg-surface-50 dark:hover:bg-surface-700 border border-surface-200 dark:border-surface-600'
            ]"
          >
            {{ page }}
          </button>
          <button
            @click="handlePageChange(currentPage + 1)"
            :disabled="currentPage === totalPages"
            class="px-4 py-2 rounded-lg border border-surface-200 dark:border-surface-600 text-surface-600 dark:text-surface-300 hover:bg-surface-50 dark:hover:bg-surface-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors duration-200"
          >
            <i class="ri-arrow-right-s-line"></i>
          </button>
        </nav>
      </div>

      <!-- Public Trips Section -->
      <section class="mt-14 mb-6">
        <div class="flex flex-col sm:flex-row sm:items-end sm:justify-between gap-3 mb-5">
          <div>
            <div class="flex items-center gap-2">
              <span class="flex h-8 w-8 items-center justify-center rounded-xl bg-accent-50 dark:bg-accent-900/30 text-accent-600 dark:text-accent-400">
                <i class="ri-compass-3-line text-lg" />
              </span>
              <h2 class="text-xl font-bold text-surface-900 dark:text-white">公开行程</h2>
            </div>
            <p class="text-sm text-surface-500 dark:text-surface-400 mt-1 ml-10">
              发现其他旅行者分享的灵感路线
              <span v-if="publicTotal > 0" class="text-surface-400"> · 共 {{ publicTotal }} 条</span>
            </p>
          </div>
          <button
            class="btn-ghost text-sm self-start sm:self-auto"
            @click="router.push('/explore')"
          >
            查看全部
            <i class="ri-arrow-right-line" />
          </button>
        </div>

        <div v-if="publicLoading" class="grid grid-cols-1 md:grid-cols-3 gap-5">
          <div
            v-for="i in 3"
            :key="'ps-' + i"
            class="rounded-2xl bg-white dark:bg-surface-800 overflow-hidden animate-pulse shadow-card"
          >
            <div class="h-36 bg-surface-200 dark:bg-surface-700"></div>
            <div class="p-4 space-y-3">
              <div class="h-5 bg-surface-200 dark:bg-surface-700 rounded w-3/4"></div>
              <div class="h-4 bg-surface-200 dark:bg-surface-700 rounded w-1/2"></div>
            </div>
          </div>
        </div>

        <div v-else-if="publicTrips.length > 0" class="grid grid-cols-1 md:grid-cols-3 gap-5">
          <TripCard
            v-for="trip in publicTrips"
            :key="'pub-' + trip.id"
            :trip="trip"
            @open="navigateToTrip(trip.id)"
          />
        </div>

        <div
          v-else
          class="rounded-3xl border border-dashed border-surface-200 dark:border-surface-600 bg-white/60 dark:bg-surface-800/60 px-6 py-10 text-center"
        >
          <i class="ri-earth-line text-4xl text-surface-300 dark:text-surface-500 mb-3 block" />
          <p class="text-surface-500 dark:text-surface-400 text-sm">暂无公开行程，快去详情页分享你的旅行吧</p>
          <button class="btn-secondary mt-4 text-sm" @click="router.push('/explore')">
            去发现
          </button>
        </div>
      </section>
    </div>
  </div>
</template>
