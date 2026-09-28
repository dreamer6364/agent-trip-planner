import { apiGet, apiPut, apiDelete } from './index'
import type { Notification } from './types'

export interface NotificationPage {
  content: Notification[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

export const notificationApi = {
  list(params?: { page?: number; size?: number }) {
    return apiGet<NotificationPage>('/api/notifications', params as Record<string, unknown>)
  },

  getUnread(params?: { limit?: number }) {
    return apiGet<Notification[]>('/api/notifications/unread', params as Record<string, unknown>)
  },

  getUnreadCount() {
    return apiGet<{ count: number }>('/api/notifications/unread-count')
  },

  markAsRead(ids: string[]) {
    return apiPut<null>('/api/notifications/read', ids)
  },

  markOneAsRead(id: string) {
    return apiPut<null>(`/api/notifications/${id}/read`)
  },

  markAllAsRead() {
    return apiPut<null>('/api/notifications/read-all')
  },

  cleanup() {
    return apiDelete<null>('/api/notifications/cleanup')
  },
}
