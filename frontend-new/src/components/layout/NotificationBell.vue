<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { notificationApi } from '@/api/notification'
import type { Notification } from '@/api/types'

const { t } = useI18n()

const unreadCount = ref(0)
const panelOpen = ref(false)

const hasUnread = computed(() => unreadCount.value > 0)

const notifications = ref<Notification[]>([])

onMounted(async () => {
  try {
    const data = await notificationApi.getUnreadCount()
    unreadCount.value = data?.count ?? 0
  } catch {
    // Silently fail - notification count is non-critical
  }
})

function togglePanel() {
  panelOpen.value = !panelOpen.value
  if (panelOpen.value && notifications.value.length === 0) {
    fetchNotifications()
  }
}

async function fetchNotifications() {
  try {
    const page = await notificationApi.list({ page: 0, size: 10 })
    notifications.value = page?.content ?? []
    const countData = await notificationApi.getUnreadCount()
    unreadCount.value = countData?.count ?? 0
  } catch {
    // Silently fail
  }
}

async function markAsRead(id: string) {
  try {
    await notificationApi.markOneAsRead(id)

    const notification = notifications.value.find((n) => n.id === id)
    if (notification && !notification.read) {
      notification.read = true
      unreadCount.value = Math.max(0, unreadCount.value - 1)
    }
  } catch {
    // Silently fail
  }
}

async function markAllRead() {
  try {
    await notificationApi.markAllAsRead()
    notifications.value.forEach((n) => { n.read = true })
    unreadCount.value = 0
  } catch {
    // Silently fail
  }
}

function formatTime(dateStr: string): string {
  const date = new Date(dateStr)
  const now = new Date()
  const diffMs = now.getTime() - date.getTime()
  const diffMin = Math.floor(diffMs / 60000)

  if (diffMin < 1) return t('notificationBell.justNow')
  if (diffMin < 60) return t('notificationBell.minutesAgo', { n: diffMin })
  const diffHr = Math.floor(diffMin / 60)
  if (diffHr < 24) return t('notificationBell.hoursAgo', { n: diffHr })
  const diffDay = Math.floor(diffHr / 24)
  return t('notificationBell.daysAgo', { n: diffDay })
}
</script>

<template>
  <div class="relative">
    <!-- Bell Button -->
    <button
      class="relative flex h-9 w-9 items-center justify-center rounded-xl text-gray-600 transition-all hover:bg-gray-100"
      @click="togglePanel"
    >
      <svg class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
        <path stroke-linecap="round" stroke-linejoin="round" d="M15 17h5l-1.405-1.405A2.032 2.032 0 0118 14.158V11a6.002 6.002 0 00-4-5.659V5a2 2 0 10-4 0v.341C7.67 6.165 6 8.388 6 11v3.159c0 .538-.214 1.055-.595 1.436L4 17h5m6 0v1a3 3 0 11-6 0v-1m6 0H9" />
      </svg>

      <!-- Unread Badge -->
      <span
        v-if="hasUnread"
        class="absolute -right-0.5 -top-0.5 flex h-4 min-w-4 items-center justify-center rounded-full bg-gradient-to-r from-red-500 to-pink-500 px-1 text-[10px] font-bold text-white shadow-lg shadow-red-500/30"
      >
        {{ unreadCount > 99 ? '99+' : unreadCount }}
      </span>

      <!-- Pulsing Dot -->
      <span
        v-if="hasUnread"
        class="absolute -right-0.5 -top-0.5 h-2.5 w-2.5"
      >
        <span class="absolute inline-flex h-full w-full animate-ping rounded-full bg-red-400 opacity-75" />
        <span class="relative inline-flex h-2.5 w-2.5 rounded-full bg-red-500" />
      </span>
    </button>

    <!-- Notification Panel -->
    <Transition
      enter-active-class="transition duration-200 ease-out"
      enter-from-class="scale-95 opacity-0"
      enter-to-class="scale-100 opacity-100"
      leave-active-class="transition duration-150 ease-in"
      leave-from-class="scale-100 opacity-100"
      leave-to-class="scale-95 opacity-0"
    >
      <div
        v-if="panelOpen"
        class="absolute right-0 z-50 mt-2 w-80 origin-top-right rounded-2xl border border-gray-100 bg-white shadow-2xl ring-1 ring-black/5"
      >
        <!-- Header -->
        <div class="flex items-center justify-between border-b border-gray-100 px-4 py-3">
          <h3 class="text-sm font-semibold text-gray-900">{{ t('notification.title') }}</h3>
          <button
            v-if="unreadCount > 0"
            class="text-xs font-medium text-brand-600 hover:text-brand-700"
            @click="markAllRead"
          >
            {{ t('notification.markAllRead') }}
          </button>
        </div>

        <!-- Notification List -->
        <div class="max-h-80 overflow-y-auto">
          <div v-if="notifications.length === 0" class="px-4 py-8 text-center">
            <svg class="mx-auto h-10 w-10 text-gray-300" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.5">
              <path stroke-linecap="round" stroke-linejoin="round" d="M14.857 17.082a23.848 23.848 0 005.454-1.31A8.967 8.967 0 0118 9.75v-.7V9A6 6 0 006 9v.75a8.967 8.967 0 01-2.312 6.022c1.733.64 3.56 1.085 5.455 1.31m5.714 0a24.255 24.255 0 01-5.714 0m5.714 0a3 3 0 11-5.714 0" />
            </svg>
            <p class="mt-2 text-sm text-gray-500">{{ t('notification.empty') }}</p>
          </div>

          <button
            v-for="notification in notifications"
            :key="notification.id"
            :class="[
              'flex w-full flex-col gap-1 border-b border-gray-50 px-4 py-3 text-left transition-colors hover:bg-gray-50',
              !notification.read ? 'bg-brand-50/30' : '',
            ]"
            @click="markAsRead(notification.id)"
          >
            <div class="flex items-start justify-between">
              <p class="text-sm font-medium text-gray-900">{{ notification.title }}</p>
              <span
                v-if="!notification.read"
                class="mt-1 h-2 w-2 flex-shrink-0 rounded-full bg-brand-500"
              />
            </div>
            <p class="text-xs text-gray-500 line-clamp-2">{{ notification.content }}</p>
            <span class="text-[10px] text-gray-400">{{ formatTime(notification.createdAt) }}</span>
          </button>
        </div>

        <!-- Footer -->
        <div class="border-t border-gray-100 px-4 py-2.5 text-center">
          <button
            class="text-xs font-medium text-brand-600 hover:text-brand-700"
            @click="panelOpen = false"
          >
            {{ t('notificationBell.collapse') }}
          </button>
        </div>
      </div>
    </Transition>

    <!-- Backdrop -->
    <div
      v-if="panelOpen"
      class="fixed inset-0 z-40"
      @click="panelOpen = false"
    />
  </div>
</template>
