<script setup lang="ts">
import { ref, watch, onMounted, onUnmounted, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { tripApi } from '@/api/trip'
import { planApi } from '@/api/plan'
import { useToast } from '@/composables/useToast'
import AlternativeCard from './AlternativeCard.vue'
import SwapConfirm from './SwapConfirm.vue'
import type { ActivityAlternative, PoiSearchItem } from '@/api/types'

interface Activity {
  id: string
  name: string
  type: string
  seq: number
  city?: string
}

interface Props {
  open: boolean
  activity: Activity | null
  tripId: string
}

const props = defineProps<Props>()

const emit = defineEmits<{
  close: []
  swap: [alternative: ActivityAlternative]
}>()

const toast = useToast()
const { t } = useI18n()

const alternatives = ref<ActivityAlternative[]>([])
const loading = ref(false)
const error = ref('')
const showConfirm = ref(false)
const selectedAlternative = ref<ActivityAlternative | null>(null)

// ---- 搜索换入（v1.19.0）----
const searchKeyword = ref('')
const searchResults = ref<ActivityAlternative[]>([])
const searching = ref(false)
const searchDone = ref(false)
let searchTimer: ReturnType<typeof setTimeout> | undefined

const showSearchResults = computed(() => searchDone.value || searching.value)

function mapSearchItem(item: PoiSearchItem, idx: number): ActivityAlternative {
  const tags: string[] = []
  if (item.matchType && item.matchType !== '精确' && item.matchType !== '包含') {
    tags.push(item.matchType)
  }
  return {
    id: `search-${idx}-${item.name}`,
    name: item.name,
    type: item.type || 'attraction',
    reason: item.address || '',
    durationMin: item.preferredDurationMin,
    lat: item.lat,
    lng: item.lng,
    address: item.address,
    rating: item.rating,
    source: item.source || 'search',
    tags: tags.length ? tags : undefined,
  }
}

async function doSearch() {
  const kw = searchKeyword.value.trim()
  if (!kw || !props.open) return
  if (searchTimer) clearTimeout(searchTimer)
  searching.value = true
  searchDone.value = true
  try {
    const list = await planApi.poiSearch({
      keyword: kw,
      city: props.activity?.city,
      limit: 6,
    })
    searchResults.value = (list || []).map(mapSearchItem)
  } catch (e: any) {
    searchResults.value = []
    toast.error(e?.message || t('swap.searchFailed'))
  } finally {
    searching.value = false
  }
}

function onSearchInput() {
  if (searchTimer) clearTimeout(searchTimer)
  if (!searchKeyword.value.trim()) {
    searchResults.value = []
    searchDone.value = false
    return
  }
  searchTimer = setTimeout(doSearch, 500)
}

function clearSearch() {
  if (searchTimer) clearTimeout(searchTimer)
  searchKeyword.value = ''
  searchResults.value = []
  searchDone.value = false
}

const typeIconMap: Record<string, { icon: string; color: string; bg: string }> = {
  attraction: { icon: 'ri-landscape-line', color: 'text-brand-600', bg: 'bg-brand-100' },
  restaurant: { icon: 'ri-restaurant-line', color: 'text-orange-600', bg: 'bg-orange-100' },
  hotel: { icon: 'ri-hotel-line', color: 'text-purple-600', bg: 'bg-purple-100' },
  shopping: { icon: 'ri-shopping-bag-line', color: 'text-pink-600', bg: 'bg-pink-100' },
  entertainment: { icon: 'ri-gamepad-line', color: 'text-green-600', bg: 'bg-green-100' },
  transport: { icon: 'ri-bus-line', color: 'text-blue-600', bg: 'bg-blue-100' },
  default: { icon: 'ri-map-pin-line', color: 'text-surface-600', bg: 'bg-surface-100' },
}

const currentTypeStyle = computed(() => {
  if (!props.activity) return typeIconMap.default
  return typeIconMap[props.activity.type] || typeIconMap.default
})

async function fetchAlternatives() {
  if (!props.activity || !props.tripId) return
  loading.value = true
  error.value = ''
  alternatives.value = []

  try {
    const response = await tripApi.getAlternatives(props.tripId, {
      activityId: props.activity.id,
      activityName: props.activity.name,
      seq: props.activity.seq,
      city: props.activity.city,
      activityType: props.activity.type,
      limit: 10,
    })
    alternatives.value = response.alternatives || []
  } catch (e: any) {
    error.value = e?.message || 'Failed to load alternatives'
    toast.error('Failed to load alternative attractions')
  } finally {
    loading.value = false
  }
}

function handleSelectAlternative(alternative: ActivityAlternative) {
  selectedAlternative.value = alternative
  showConfirm.value = true
}

async function handleConfirmSwap(autoAdjust: boolean) {
  if (!selectedAlternative.value || !props.activity || !props.tripId) return

  try {
    await tripApi.replaceActivity(props.tripId, {
      activityId: props.activity.id,
      activityName: props.activity.name,
      seq: props.activity.seq,
      alternativeId: selectedAlternative.value.id,
      alternativeName: selectedAlternative.value.name,
      // 搜索换入：带目标坐标/地址，后端直写并重算邻接距离与后续时间
      lat: selectedAlternative.value.lat,
      lng: selectedAlternative.value.lng,
      address: selectedAlternative.value.address,
      autoAdjust,
    })

    toast.success(`Replaced "${props.activity.name}" with "${selectedAlternative.value.name}"`)
    emit('swap', selectedAlternative.value)
    showConfirm.value = false
    selectedAlternative.value = null
    emit('close')
  } catch (e: any) {
    toast.error(e?.message || 'Failed to replace activity')
  }
}

function handleConfirmClose() {
  showConfirm.value = false
  selectedAlternative.value = null
}

function handleBackdropClick() {
  emit('close')
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape' && props.open) {
    if (showConfirm.value) {
      handleConfirmClose()
    } else {
      emit('close')
    }
  }
}

watch(
  () => props.open,
  (isOpen) => {
    if (isOpen) {
      document.body.style.overflow = 'hidden'
      fetchAlternatives()
    } else {
      document.body.style.overflow = ''
      alternatives.value = []
      error.value = ''
      clearSearch()
    }
  },
)

onMounted(() => {
  document.addEventListener('keydown', onKeydown)
})

onUnmounted(() => {
  document.removeEventListener('keydown', onKeydown)
  document.body.style.overflow = ''
})
</script>

<template>
  <Teleport to="body">
    <Transition name="swap-panel">
      <div v-if="open" class="fixed inset-0 z-50 flex justify-end">
        <!-- Backdrop -->
        <div
          class="fixed inset-0 bg-black/30 backdrop-blur-sm transition-opacity duration-300"
          @click="handleBackdropClick"
        />

        <!-- Panel -->
        <div
          class="relative z-10 flex h-full w-[380px] max-w-[calc(100vw-2rem)] flex-col bg-white shadow-panel"
          @click.stop
        >
          <!-- Header -->
          <div class="flex items-center justify-between border-b border-surface-100 px-5 py-4">
            <div class="flex items-center gap-2">
              <div class="flex h-8 w-8 items-center justify-center rounded-lg bg-accent-100">
                <i class="ri-loop-right-line text-accent-600 text-lg" />
              </div>
              <h3 class="text-base font-semibold text-surface-900">{{ t('swap.title') }}</h3>
            </div>
            <button
              class="flex h-8 w-8 items-center justify-center rounded-lg text-surface-400 transition-colors hover:bg-surface-100 hover:text-surface-600"
              @click="emit('close')"
            >
              <svg class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                <path stroke-linecap="round" stroke-linejoin="round" d="M6 18L18 6M6 6l12 12" />
              </svg>
            </button>
          </div>

          <!-- Current Activity Info -->
          <div v-if="activity" class="border-b border-surface-100 bg-surface-50 px-5 py-4">
            <p class="mb-2 text-xs font-medium uppercase tracking-wider text-surface-500">{{ t('swap.current') }}</p>
            <div class="flex items-center gap-3">
              <div
                :class="[
                  'flex h-10 w-10 flex-shrink-0 items-center justify-center rounded-xl',
                  currentTypeStyle.bg,
                ]"
              >
                <i :class="[currentTypeStyle.icon, currentTypeStyle.color, 'text-xl']" />
              </div>
              <div class="min-w-0 flex-1">
                <p class="truncate text-sm font-semibold text-surface-900">{{ activity.name }}</p>
                <div class="mt-0.5 flex items-center gap-2">
                  <span class="text-xs text-surface-500">{{ activity.type }}</span>
                  <span class="text-surface-300">·</span>
                  <span class="text-xs text-surface-500">{{ t('swap.seqLabel', { n: activity.seq }) }}</span>
                </div>
              </div>
            </div>
          </div>

          <!-- Search Box（搜索换入 v1.19.0） -->
          <div class="border-b border-surface-100 px-5 py-3">
            <div class="relative">
              <i class="ri-search-line absolute left-3 top-1/2 -translate-y-1/2 text-base text-surface-400" />
              <input
                v-model="searchKeyword"
                type="text"
                :placeholder="t('swap.searchPlaceholder')"
                class="w-full rounded-lg border border-surface-200 bg-surface-50 py-2.5 pl-9 pr-9 text-sm text-surface-700 outline-none transition-colors placeholder:text-surface-400 focus:border-brand-400 focus:bg-white focus:ring-2 focus:ring-brand-500/15"
                @input="onSearchInput"
                @keydown.enter.prevent="doSearch"
              />
              <button
                v-if="searchKeyword"
                class="absolute right-2.5 top-1/2 flex h-5 w-5 -translate-y-1/2 items-center justify-center rounded-full text-surface-400 transition-colors hover:bg-surface-200 hover:text-surface-600"
                :title="t('swap.clearSearch')"
                @click="clearSearch"
              >
                <i class="ri-close-line text-sm" />
              </button>
            </div>
            <p class="mt-1.5 text-[11px] leading-relaxed text-surface-400">
              {{ t('swap.searchHint') }}
            </p>
          </div>

          <!-- Alternatives List -->
          <div class="flex-1 overflow-y-auto px-5 py-4">
            <!-- 搜索结果态（优先于推荐列表） -->
            <template v-if="showSearchResults">
              <!-- 搜索中骨架 -->
              <div v-if="searching" class="space-y-3">
                <div v-for="i in 3" :key="i" class="animate-pulse rounded-xl border border-surface-100 bg-white p-4">
                  <div class="flex items-start gap-3">
                    <div class="h-10 w-10 flex-shrink-0 rounded-xl bg-surface-100" />
                    <div class="flex-1 space-y-2">
                      <div class="h-4 w-3/4 rounded bg-surface-100" />
                      <div class="h-3 w-1/2 rounded bg-surface-100" />
                    </div>
                  </div>
                </div>
              </div>

              <!-- 结果列表 -->
              <div v-else-if="searchResults.length > 0" class="space-y-3">
                <p class="mb-1 text-xs font-medium uppercase tracking-wider text-surface-500">
                  {{ t('swap.searchResults', { n: searchResults.length }) }}
                </p>
                <AlternativeCard
                  v-for="alt in searchResults"
                  :key="alt.id"
                  :alternative="alt"
                  @select="handleSelectAlternative"
                />
              </div>

              <!-- 搜索空态 -->
              <div v-else class="flex flex-col items-center justify-center py-12 text-center">
                <div class="mb-4 flex h-16 w-16 items-center justify-center rounded-2xl bg-surface-100">
                  <i class="ri-search-2-line text-3xl text-surface-400" />
                </div>
                <p class="text-sm font-medium text-surface-700">{{ t('swap.searchEmptyTitle', { kw: searchKeyword.trim() }) }}</p>
                <p class="mt-1 text-xs text-surface-500">{{ t('swap.searchEmptyHint') }}</p>
                <button
                  class="mt-3 text-sm font-medium text-brand-600 hover:text-brand-700"
                  @click="clearSearch"
                >
                  {{ t('swap.viewRecommended') }}
                </button>
              </div>
            </template>

            <template v-else>
            <!-- Loading Skeleton -->
            <div v-if="loading" class="space-y-3">
              <div v-for="i in 4" :key="i" class="animate-pulse rounded-xl border border-surface-100 bg-white p-4">
                <div class="flex items-start gap-3">
                  <div class="h-10 w-10 flex-shrink-0 rounded-xl bg-surface-100" />
                  <div class="flex-1 space-y-2">
                    <div class="h-4 w-3/4 rounded bg-surface-100" />
                    <div class="h-3 w-1/2 rounded bg-surface-100" />
                    <div class="h-3 w-full rounded bg-surface-100" />
                  </div>
                </div>
                <div class="mt-3 flex gap-2">
                  <div class="h-5 w-16 rounded-full bg-surface-100" />
                  <div class="h-5 w-20 rounded-full bg-surface-100" />
                </div>
              </div>
            </div>

            <!-- Error State -->
            <div v-else-if="error" class="flex flex-col items-center justify-center py-12 text-center">
              <div class="mb-3 flex h-12 w-12 items-center justify-center rounded-full bg-danger-50">
                <i class="ri-error-warning-line text-xl text-danger-500" />
              </div>
              <p class="text-sm font-medium text-surface-700">{{ error }}</p>
              <button
                class="mt-3 text-sm font-medium text-brand-600 hover:text-brand-700"
                @click="fetchAlternatives"
              >
                Retry
              </button>
            </div>

            <!-- Empty State -->
            <div
              v-else-if="alternatives.length === 0"
              class="flex flex-col items-center justify-center py-12 text-center"
            >
              <div class="mb-4 flex h-16 w-16 items-center justify-center rounded-2xl bg-surface-100">
                <i class="ri-map-pin-off-line text-3xl text-surface-400" />
              </div>
              <p class="text-sm font-medium text-surface-700">No alternatives found</p>
              <p class="mt-1 text-xs text-surface-500">Try adjusting your search criteria</p>
            </div>

            <!-- Alternatives -->
            <div v-else class="space-y-3">
              <AlternativeCard
                v-for="alt in alternatives"
                :key="alt.id"
                :alternative="alt"
                @select="handleSelectAlternative"
              />
            </div>
            </template>
          </div>
        </div>
      </div>
    </Transition>

    <!-- Swap Confirm Dialog -->
    <SwapConfirm
      :open="showConfirm"
      :old-name="activity?.name || ''"
      :new-name="selectedAlternative?.name || ''"
      @confirm="handleConfirmSwap"
      @close="handleConfirmClose"
    />
  </Teleport>
</template>

<style scoped>
.swap-panel-enter-active,
.swap-panel-leave-active {
  transition: all 0.35s cubic-bezier(0.4, 0, 0.2, 1);
}

.swap-panel-enter-active > div:first-child,
.swap-panel-leave-active > div:first-child {
  transition: opacity 0.35s cubic-bezier(0.4, 0, 0.2, 1);
}

.swap-panel-enter-active > div:last-child,
.swap-panel-leave-active > div:last-child {
  transition: transform 0.35s cubic-bezier(0.4, 0, 0.2, 1);
}

.swap-panel-enter-from,
.swap-panel-leave-to {
  pointer-events: none;
}

.swap-panel-enter-from > div:first-child,
.swap-panel-leave-to > div:first-child {
  opacity: 0;
}

.swap-panel-enter-from > div:last-child,
.swap-panel-leave-to > div:last-child {
  transform: translateX(100%);
}

.swap-panel-enter-to,
.swap-panel-leave-from {
  pointer-events: auto;
}
</style>
