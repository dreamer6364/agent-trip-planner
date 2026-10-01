<script setup lang="ts">
import { onMounted, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useNotificationStore } from '@/stores/notification'
import NotificationItem from './NotificationItem.vue'

const { t } = useI18n()
const store = useNotificationStore()

const notifications = computed(() => store.notifications)
const loading = computed(() => store.loading)

onMounted(() => {
  store.fetchNotifications({ page: 0, size: 50 })
})

function handleMarkAllRead() {
  store.markAllAsRead()
}

function handleRead(id: string) {
  store.markAsRead([id])
}
</script>

<template>
  <div class="max-w-2xl mx-auto p-4 sm:p-6">
    <div class="card-base overflow-hidden">
      <div class="flex items-center justify-between px-5 py-4 border-b border-surface-100">
        <div class="flex items-center gap-3">
          <h2 class="text-lg font-semibold text-surface-900">{{ t('notification.title') }}</h2>
          <span
            v-if="store.unreadCount > 0"
            class="inline-flex items-center justify-center h-5 min-w-[20px] rounded-full bg-brand-500 px-1.5 text-xs font-bold text-white"
          >
            {{ store.unreadCount > 99 ? '99+' : store.unreadCount }}
          </span>
        </div>
        <button
          v-if="store.unreadCount > 0"
          class="text-sm font-medium text-brand-600 hover:text-brand-700 transition-colors"
          @click="handleMarkAllRead"
        >
          {{ t('notification.markAllRead') }}
        </button>
      </div>

      <div v-if="loading && notifications.length === 0" class="p-8">
        <div class="space-y-4">
          <div v-for="i in 5" :key="i" class="flex items-start gap-3">
            <div class="h-9 w-9 rounded-full skeleton shrink-0" />
            <div class="flex-1 space-y-2">
              <div class="h-4 w-3/4 rounded skeleton" />
              <div class="h-3 w-full rounded skeleton" />
            </div>
          </div>
        </div>
      </div>

      <div
        v-else-if="notifications.length === 0"
        class="flex flex-col items-center justify-center py-16"
      >
        <div class="flex h-16 w-16 items-center justify-center rounded-full bg-surface-100 mb-4">
          <svg class="h-8 w-8 text-surface-300" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.5">
            <path stroke-linecap="round" stroke-linejoin="round" d="M14.857 17.082a23.848 23.848 0 005.454-1.31A8.967 8.967 0 0118 9.75v-.7V9A6 6 0 006 9v.75a8.967 8.967 0 01-2.312 6.022c1.733.64 3.56 1.085 5.455 1.31m5.714 0a24.255 24.255 0 01-5.714 0m5.714 0a3 3 0 11-5.714 0" />
          </svg>
        </div>
        <p class="text-surface-500 font-medium">{{ t('notification.empty') }}</p>
        <p class="text-sm text-surface-400 mt-1">{{ t('notification.emptyHint') }}</p>
      </div>

      <div v-else class="divide-y divide-surface-100">
        <NotificationItem
          v-for="notification in notifications"
          :key="notification.id"
          :notification="notification"
          @read="handleRead"
        />
      </div>
    </div>
  </div>
</template>
