<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useNotificationStore } from '@/stores/notification'
import NotificationItem from './NotificationItem.vue'

interface Props {
  open: boolean
}

const props = defineProps<Props>()

const emit = defineEmits<{
  close: []
}>()

const router = useRouter()
const { t } = useI18n()
const store = useNotificationStore()
const panelRef = ref<HTMLElement | null>(null)

const recentNotifications = computed(() => {
  return store.notifications.slice(0, 10)
})

function handleClickOutside(e: MouseEvent) {
  if (panelRef.value && !panelRef.value.contains(e.target as Node)) {
    emit('close')
  }
}

function handleRead(id: string) {
  store.markAsRead([id])
}

function handleViewAll() {
  emit('close')
  router.push('/notifications')
}

function handleKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape' && props.open) {
    emit('close')
  }
}

watch(
  () => props.open,
  (isOpen) => {
    if (isOpen) {
      store.fetchUnread()
      setTimeout(() => {
        document.addEventListener('mousedown', handleClickOutside)
      }, 0)
    } else {
      document.removeEventListener('mousedown', handleClickOutside)
    }
  }
)

onMounted(() => {
  document.addEventListener('keydown', handleKeydown)
})

onUnmounted(() => {
  document.removeEventListener('mousedown', handleClickOutside)
  document.removeEventListener('keydown', handleKeydown)
})
</script>

<template>
  <Teleport to="body">
    <Transition name="panel">
      <div
        v-if="open"
        ref="panelRef"
        class="fixed z-50 mt-2 w-96 max-w-[calc(100vw-2rem)] origin-top-right"
        style="right: 1rem; top: 3.5rem;"
      >
        <div class="glass rounded-2xl shadow-panel overflow-hidden ring-1 ring-black/5">
          <div class="flex items-center justify-between px-4 py-3 border-b border-white/10">
            <div class="flex items-center gap-2">
              <h3 class="text-sm font-semibold text-surface-900">{{ t('notification.title') }}</h3>
              <span
                v-if="store.unreadCount > 0"
                class="inline-flex items-center justify-center h-5 min-w-[20px] rounded-full bg-danger-500 px-1.5 text-[10px] font-bold text-white"
              >
                {{ store.unreadCount > 99 ? '99+' : store.unreadCount }}
              </span>
            </div>
          </div>

          <div
            v-if="store.loading && recentNotifications.length === 0"
            class="p-4"
          >
            <div class="space-y-3">
              <div v-for="i in 3" :key="i" class="flex items-start gap-3">
                <div class="h-8 w-8 rounded-full skeleton shrink-0" />
                <div class="flex-1 space-y-1.5">
                  <div class="h-3 w-2/3 rounded skeleton" />
                  <div class="h-2.5 w-full rounded skeleton" />
                </div>
              </div>
            </div>
          </div>

          <div
            v-else-if="recentNotifications.length === 0"
            class="flex flex-col items-center justify-center py-10"
          >
            <svg class="h-10 w-10 text-surface-300 mb-2" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.5">
              <path stroke-linecap="round" stroke-linejoin="round" d="M14.857 17.082a23.848 23.848 0 005.454-1.31A8.967 8.967 0 0118 9.75v-.7V9A6 6 0 006 9v.75a8.967 8.967 0 01-2.312 6.022c1.733.64 3.56 1.085 5.455 1.31m5.714 0a24.255 24.255 0 01-5.714 0m5.714 0a3 3 0 11-5.714 0" />
            </svg>
            <p class="text-sm text-surface-500">{{ t('notification.empty') }}</p>
          </div>

          <div
            v-else
            class="max-h-80 overflow-y-auto divide-y divide-surface-100/50"
          >
            <NotificationItem
              v-for="notification in recentNotifications"
              :key="notification.id"
              :notification="notification"
              @read="handleRead"
            />
          </div>

          <div class="border-t border-white/10 px-4 py-2.5">
            <button
              class="w-full text-center text-sm font-medium text-brand-600 hover:text-brand-700 transition-colors"
              @click="handleViewAll"
            >
              {{ t('notification.viewAll') }}
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.panel-enter-active,
.panel-leave-active {
  transition: all 0.2s cubic-bezier(0.4, 0, 0.2, 1);
}

.panel-enter-from,
.panel-leave-to {
  opacity: 0;
  transform: scale(0.95) translateY(-4px);
}
</style>
