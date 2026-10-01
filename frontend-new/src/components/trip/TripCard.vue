<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { Trip } from '@/api/types'
import TripStatusBadge from './TripStatusBadge.vue'
import UIModal from '@/components/ui/UIModal.vue'
import { useTripStore } from '@/stores/trip'
import { useAuthStore } from '@/stores/auth'
import { useToast } from '@/composables/useToast'
import { tripCoverKeyword, useTripCover } from '@/composables/useTripCover'

const props = defineProps<{
  trip: Trip
}>()

const emit = defineEmits<{
  open: [tripId: string]
  deleted: [tripId: string]
}>()

const tripStore = useTripStore()
const authStore = useAuthStore()
const toast = useToast()
const { t, locale } = useI18n()

const menuOpen = ref(false)
const deleteOpen = ref(false)
const deleting = ref(false)

const canDelete = computed(() => {
  const uid = authStore.user?.id
  return !!uid && props.trip.userId === uid
})

const cityGradients: Record<string, string> = {
  北京: 'from-rose-500 to-orange-400',
  beijing: 'from-rose-500 to-orange-400',
  上海: 'from-sky-500 to-indigo-500',
  shanghai: 'from-sky-500 to-indigo-500',
  广州: 'from-emerald-500 to-teal-400',
  guangzhou: 'from-emerald-500 to-teal-400',
  深圳: 'from-violet-500 to-purple-500',
  shenzhen: 'from-violet-500 to-purple-500',
  成都: 'from-amber-500 to-red-500',
  chengdu: 'from-amber-500 to-red-500',
  杭州: 'from-teal-500 to-cyan-400',
  hangzhou: 'from-teal-500 to-cyan-400',
  西安: 'from-orange-500 to-amber-500',
  xian: 'from-orange-500 to-amber-500',
  南京: 'from-pink-500 to-rose-400',
  nanjing: 'from-pink-500 to-rose-400',
  重庆: 'from-red-500 to-pink-500',
  chongqing: 'from-red-500 to-pink-500',
  苏州: 'from-indigo-400 to-blue-500',
  suzhou: 'from-indigo-400 to-blue-500',
  武汉: 'from-cyan-500 to-blue-500',
  长沙: 'from-orange-500 to-rose-500',
  厦门: 'from-teal-400 to-sky-500',
  青岛: 'from-blue-500 to-cyan-400',
  昆明: 'from-emerald-400 to-lime-500',
  大理: 'from-sky-400 to-indigo-400',
  三亚: 'from-cyan-400 to-blue-500',
  桂林: 'from-emerald-500 to-cyan-500',
  丽江: 'from-amber-400 to-orange-500',
}

const defaultGradient = 'from-brand-500 to-accent-500'

const cityLabel = computed(() => props.trip.city || '')

const coverGradient = computed(() => {
  const city = props.trip.city || ''
  if (city && cityGradients[city]) return cityGradients[city]
  const title = props.trip.title?.toLowerCase() || ''
  for (const [key, gradient] of Object.entries(cityGradients)) {
    if (title.includes(key) || title.includes(key.toLowerCase())) return gradient
  }
  return defaultGradient
})

const landmarks = computed(() => (props.trip.landmarks || []).slice(0, 4))

/** 封面图：按「城市 + 景点」联网搜索，失败保留城市渐变底图 */
const coverKeyword = computed(() => tripCoverKeyword(props.trip))
const { coverUrl, coverLoaded, coverFailed } = useTripCover(coverKeyword)

const dateRange = computed(() => {
  if (!props.trip.timeStart || !props.trip.timeEnd) return ''
  const start = new Date(props.trip.timeStart).toLocaleDateString(locale.value, { month: 'short', day: 'numeric' })
  const end = new Date(props.trip.timeEnd).toLocaleDateString(locale.value, { month: 'short', day: 'numeric' })
  return `${start} - ${end}`
})

const dayCount = computed(() => {
  if (!props.trip.timeStart || !props.trip.timeEnd) return 0
  const start = new Date(props.trip.timeStart)
  const end = new Date(props.trip.timeEnd)
  return Math.ceil((end.getTime() - start.getTime()) / (1000 * 60 * 60 * 24)) + 1
})

const activityCount = computed(() => {
  return props.trip.activityCount ?? props.trip.latestVersion?.activities?.length ?? 0
})

const truncatedDescription = computed(() => {
  const text = props.trip.rawInput || ''
  return text.length > 80 ? text.slice(0, 80) + '...' : text
})

const authorLabel = computed(() => {
  if (!props.trip.isPublic) return ''
  return props.trip.authorName || props.trip.userId || ''
})

function stopClick(e: Event) {
  e.stopPropagation()
}

async function confirmDelete() {
  if (deleting.value) return
  deleting.value = true
  try {
    await tripStore.deleteTrip(props.trip.id)
    toast.success(t('tripCard.deleted'))
    emit('deleted', props.trip.id)
    deleteOpen.value = false
    menuOpen.value = false
  } catch {
    toast.error(t('tripCard.deleteFailed'))
  } finally {
    deleting.value = false
  }
}
</script>

<template>
  <div
    class="group relative cursor-pointer rounded-2xl bg-white dark:bg-surface-800 shadow-card hover:shadow-card-hover transition-all duration-300 ease-out hover:-translate-y-1 overflow-hidden"
    @click="emit('open', trip.id)"
  >
    <div
      :class="[
        'relative h-44 bg-gradient-to-br',
        coverGradient,
        'flex items-end p-4'
      ]"
    >
      <img
        v-if="coverUrl && !coverFailed"
        :src="coverUrl"
        alt=""
        loading="lazy"
        decoding="async"
        referrerpolicy="no-referrer"
        class="absolute inset-0 h-full w-full object-cover transition-opacity duration-500"
        :class="coverLoaded && !coverFailed ? 'opacity-100' : 'opacity-0'"
        @load="coverLoaded = true"
        @error="coverFailed = true"
      />
      <div class="absolute inset-0 bg-black/15 group-hover:bg-black/5 transition-colors duration-300" />
      <div class="absolute inset-0 opacity-20 mix-blend-overlay pointer-events-none"
           style="background-image: radial-gradient(circle at 20% 30%, white 1px, transparent 1px); background-size: 24px 24px;" />

      <div class="absolute top-3 right-3 flex items-center gap-2 z-20">
        <TripStatusBadge :status="trip.status" />
        <div v-if="canDelete" class="relative" @click.stop>
          <button
            class="flex h-8 w-8 items-center justify-center rounded-full bg-white/20 text-white backdrop-blur-sm hover:bg-white/35 transition-colors"
            :aria-label="t('tripCard.moreActions')"
            @click="menuOpen = !menuOpen"
          >
            <svg class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
              <path stroke-linecap="round" stroke-linejoin="round" d="M5 12h.01M12 12h.01M19 12h.01" />
            </svg>
          </button>
          <Transition
            enter-active-class="transition duration-100 ease-out"
            enter-from-class="scale-95 opacity-0"
            enter-to-class="scale-100 opacity-100"
            leave-active-class="transition duration-75 ease-in"
            leave-from-class="scale-100 opacity-100"
            leave-to-class="scale-95 opacity-0"
          >
            <div
              v-if="menuOpen"
              class="absolute right-0 top-10 z-30 w-36 origin-top-right rounded-xl border border-gray-100 bg-white p-1.5 shadow-xl ring-1 ring-black/5"
            >
              <button
                class="flex w-full items-center gap-2 rounded-lg px-3 py-2 text-sm text-red-600 transition-colors hover:bg-red-50"
                @click.stop="deleteOpen = true; menuOpen = false"
              >
                <svg class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                  <path stroke-linecap="round" stroke-linejoin="round" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
                </svg>
                {{ t('trip.delete') }}
              </button>
            </div>
          </Transition>
        </div>
      </div>

      <div class="relative z-10 w-full">
        <div class="flex flex-wrap items-center gap-2 mb-2">
          <span v-if="cityLabel" class="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-semibold bg-white/25 text-white backdrop-blur-sm">
            <svg class="w-3 h-3" fill="currentColor" viewBox="0 0 24 24">
              <path d="M12 2C8.13 2 5 5.13 5 9c0 5.25 7 13 7 13s7-7.75 7-13c0-3.87-3.13-7-7-7zm0 9.5a2.5 2.5 0 110-5 2.5 2.5 0 010 5z" />
            </svg>
            {{ cityLabel }}
          </span>
          <span v-if="dateRange" class="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium bg-white/25 text-white backdrop-blur-sm">
            <svg class="w-3 h-3 mr-1" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 7V3m8 4V3m-9 8h10M5 21h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v12a2 2 0 002 2z" />
            </svg>
            {{ dateRange }}
          </span>
        </div>

        <div v-if="landmarks.length" class="flex flex-wrap gap-1.5">
          <span
            v-for="name in landmarks"
            :key="name"
            class="inline-flex max-w-[7rem] items-center px-2 py-0.5 rounded-md text-[11px] font-medium bg-white/20 text-white backdrop-blur-sm truncate"
          >
            {{ name }}
          </span>
          <span
            v-if="(trip.landmarks || []).length > 4"
            class="inline-flex items-center px-1.5 py-0.5 rounded-md text-[11px] text-white/90 bg-white/15"
          >
            +{{ (trip.landmarks || []).length - 4 }}
          </span>
        </div>
      </div>
    </div>

    <div class="p-4">
      <h3 class="text-lg font-bold text-surface-900 dark:text-white mb-1 line-clamp-1 group-hover:text-brand-600 dark:group-hover:text-brand-400 transition-colors">
        {{ trip.title }}
      </h3>
      <p class="text-sm text-surface-500 dark:text-surface-400 mb-3 line-clamp-2 leading-relaxed">
        {{ truncatedDescription }}
      </p>
      <div class="flex items-center gap-2">
        <span class="inline-flex items-center px-2.5 py-1 rounded-lg bg-brand-50 dark:bg-brand-900/30 text-brand-700 dark:text-brand-300 text-xs font-medium">
          <svg class="w-3.5 h-3.5 mr-1" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M17.657 16.657L13.414 20.9a1.998 1.998 0 01-2.827 0l-4.244-4.243a8 8 0 1111.314 0z" />
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 11a3 3 0 11-6 0 3 3 0 016 0z" />
          </svg>
          {{ activityCount }} {{ t('trip.activities') }}
        </span>
        <span class="inline-flex items-center px-2.5 py-1 rounded-lg bg-accent-50 dark:bg-accent-900/30 text-accent-700 dark:text-accent-300 text-xs font-medium">
          <svg class="w-3.5 h-3.5 mr-1" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 7V3m8 4V3m-9 8h10M5 21h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v12a2 2 0 002 2z" />
          </svg>
          {{ dayCount }} {{ t('trip.days') }}
        </span>
      </div>
      <div
        v-if="authorLabel"
        class="mt-2.5 flex items-center gap-1.5 text-[11px] text-surface-400 dark:text-surface-500 truncate"
        :title="t('tripCard.authorId')"
      >
        <i class="ri-user-3-line shrink-0 text-surface-400 dark:text-surface-500"></i>
        <span>{{ t('tripCard.author', { name: authorLabel }) }}</span>
      </div>
    </div>

    <div class="absolute inset-0 rounded-2xl ring-1 ring-inset ring-black/[0.04] dark:ring-white/[0.06] pointer-events-none" />
    <div class="absolute inset-0 rounded-2xl opacity-0 group-hover:opacity-100 transition-opacity duration-300 pointer-events-none ring-2 ring-brand-400/50 dark:ring-brand-500/50 shadow-glow" />

    <UIModal
      :open="deleteOpen"
      :title="t('trip.delete')"
      size="sm"
      @close="deleteOpen = false"
      @click="stopClick"
    >
      <p class="text-sm text-surface-600 dark:text-surface-300 leading-relaxed">
        {{ t('tripCard.deleteConfirmBefore') }}<span class="font-semibold text-surface-900 dark:text-white">{{ trip.title }}</span>{{ t('tripCard.deleteConfirmAfter') }}
      </p>
      <template #footer>
        <button
          class="btn-secondary text-sm"
          :disabled="deleting"
          @click="deleteOpen = false"
        >
          {{ t('common.cancel') }}
        </button>
        <button
          class="inline-flex items-center justify-center gap-2 px-5 py-2.5 bg-danger-500 hover:bg-danger-600 text-white font-semibold text-sm rounded-xl transition-all disabled:opacity-50"
          :disabled="deleting"
          @click="confirmDelete"
        >
          {{ deleting ? t('tripCard.deleting') : t('tripCard.confirmDelete') }}
        </button>
      </template>
    </UIModal>
  </div>
</template>
