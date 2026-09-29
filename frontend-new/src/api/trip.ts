import { apiGet, apiPost, apiPut, apiDelete } from './index'
import type {
  Trip,
  TripListResponse,
  CreateTripRequest,
  UpdateTripRequest,
  TripVersion,
  AlternativesResponse,
  ShareResponse,
  ExportResponse,
  CoverResponse,
} from './types'

export const tripApi = {
  // 行程 CRUD
  list(params?: { page?: number; size?: number; keyword?: string; status?: string }) {
    return apiGet<TripListResponse>('/api/trips', params as Record<string, unknown>)
  },

  get(id: string) {
    return apiGet<Trip>(`/api/trips/${id}`)
  },

  create(data: CreateTripRequest) {
    return apiPost<Trip>('/api/trips', data, { timeout: 300000 })
  },

  update(id: string, data: UpdateTripRequest) {
    return apiPut<Trip>(`/api/trips/${id}`, data)
  },

  remove(id: string) {
    return apiDelete<null>(`/api/trips/${id}`)
  },

  // 版本
  getVersions(id: string, params?: { page?: number; size?: number }) {
    return apiGet<TripVersion[]>(`/api/trips/${id}/versions`, params as Record<string, unknown>)
  },

  getVersion(id: string, versionNum: number) {
    return apiGet<TripVersion>(`/api/trips/${id}/versions/${versionNum}`)
  },

  createVersion(id: string, data: { feedback: string; baseVersionId: string }) {
    return apiPost<TripVersion>(`/api/trips/${id}/versions`, data)
  },

  rollbackVersion(id: string, versionNum: number, data?: { reason?: string }) {
    return apiPost<TripVersion>(`/api/trips/${id}/versions/${versionNum}/rollback`, data)
  },

  forkVersion(id: string, versionNum: number, title?: string) {
    const params = title ? `?title=${encodeURIComponent(title)}` : ''
    return apiPost<{ newTripId: string }>(
      `/api/trips/${id}/versions/${versionNum}/fork${params}`,
      undefined,
    )
  },

  // 规划（同步 AI/内联，LLM 可能耗时较长）；variant=true 换版规划：主题不变、排除已用 POI、内容不同
  triggerPlan(id: string, baseVersionId?: string, variant?: boolean) {
    const params = new URLSearchParams()
    if (baseVersionId) params.set('baseVersionId', baseVersionId)
    if (variant) params.set('variant', 'true')
    const qs = params.toString()
    return apiPost<{ message: string }>(`/api/trips/${id}/plan${qs ? `?${qs}` : ''}`, undefined, { timeout: 300000 })
  },

  saveActivities(id: string, data: unknown) {
    return apiPost<{ message: string }>(`/api/trips/${id}/activities`, data)
  },

  // 替换
  getAlternatives(tripId: string, data: {
    activityId?: string
    activityName: string
    seq?: number
    city?: string
    activityType?: string
    limit?: number
    radiusKm?: number
  }) {
    return apiPost<AlternativesResponse>(`/api/trips/${tripId}/alternatives`, data)
  },

  replaceActivity(tripId: string, data: {
    activityId?: string
    activityName: string
    seq?: number
    alternativeId: string
    alternativeName: string
    /** 搜索换入时携带目标 POI 坐标/地址（后端直接写入并重算距离时间） */
    lat?: number
    lng?: number
    address?: string
    autoAdjust?: boolean
  }) {
    return apiPost<Record<string, unknown>>(`/api/trips/${tripId}/replace`, data)
  },

  // 分享
  getShareInfo(id: string) {
    return apiGet<ShareResponse>(`/api/trips/${id}/share`)
  },

  createShare(id: string, data: { isPublic: boolean; expireDays?: number; password?: string }) {
    return apiPost<ShareResponse>(`/api/trips/${id}/share`, data)
  },

  revokeShare(id: string) {
    return apiDelete<null>(`/api/trips/${id}/share`)
  },

  // 导出
  exportTrip(id: string, params?: { format?: string; language?: string; includeMap?: boolean; includeStats?: boolean; version?: number }) {
    return apiGet<ExportResponse>(`/api/trips/${id}/export`, params as Record<string, unknown>)
  },

  // 封面图（联网按关键词搜索，供行程卡片展示）
  searchCover(kw: string) {
    return apiGet<CoverResponse>('/api/trips/covers', { kw })
  },

  // 公开/分享
  getSharedTrip(token: string) {
    return apiGet<Trip>(`/api/trips/shared/${token}`)
  },

  getPublicTrips(params?: { page?: number; size?: number; keyword?: string }) {
    return apiGet<TripListResponse>('/api/trips/public', params as Record<string, unknown>)
  },
}
