import { defineStore } from 'pinia'
import { ref } from 'vue'
import { notificationApi } from '@/api/notification'
import type { Notification } from '@/api/types'

export const useNotificationStore = defineStore('notification', () => {
  const notifications = ref<Notification[]>([])
  const unreadCount = ref(0)
  const loading = ref(false)

  async function fetchNotifications(params?: { page?: number; size?: number }) {
    loading.value = true
    try {
      const page = await notificationApi.list(params)
      notifications.value = page?.content ?? []
    } finally {
      loading.value = false
    }
  }

  async function fetchUnreadCount() {
    try {
      const data = await notificationApi.getUnreadCount()
      unreadCount.value = data.count
    } catch {
      // ignore
    }
  }

  async function fetchUnread() {
    try {
      const data = await notificationApi.getUnread()
      unreadCount.value = data.length
      return data
    } catch {
      return []
    }
  }

  async function markAsRead(ids: string[]) {
    await notificationApi.markAsRead(ids)
    notifications.value = notifications.value.map((n) =>
      ids.includes(n.id) ? { ...n, read: true } : n,
    )
    unreadCount.value = Math.max(0, unreadCount.value - ids.length)
  }

  async function markAllAsRead() {
    await notificationApi.markAllAsRead()
    notifications.value = notifications.value.map((n) => ({ ...n, read: true }))
    unreadCount.value = 0
  }

  function addNotification(notification: Notification) {
    notifications.value.unshift(notification)
    if (!notification.read) {
      unreadCount.value++
    }
  }

  return {
    notifications, unreadCount, loading,
    fetchNotifications, fetchUnreadCount, fetchUnread,
    markAsRead, markAllAsRead, addNotification,
  }
})
