import { apiGet, apiPost } from './index'
import type { PlanTaskProgress, ParsePreviewRequest, ParsePreviewResponse, PoiSearchItem } from './types'

export const planApi = {
  parsePreview(data: ParsePreviewRequest) {
    return apiPost<ParsePreviewResponse>('/api/plan/parse-preview', data)
  },

  getTaskProgress(taskId: string) {
    return apiGet<PlanTaskProgress>(`/api/plan/tasks/${taskId}`)
  },

  getTripTasks(tripId: string) {
    return apiGet<PlanTaskProgress[]>(`/api/plan/trips/${tripId}/tasks`)
  },

  geocode(data: { query: string; city?: string; source?: string }) {
    return apiPost<Record<string, unknown>>('/api/plan/geocode', data)
  },

  /** POI 关键词搜索（换景点搜索框，返回多条含坐标/地址/评分） */
  poiSearch(data: { keyword: string; city?: string; limit?: number }) {
    return apiPost<PoiSearchItem[]>('/api/plan/poi-search', data)
  },

  batchGeocode(data: { queries: string[]; city?: string; source?: string }) {
    return apiPost<{ results: Record<string, unknown>[] }>('/api/plan/batch-geocode', data)
  },

  reverseGeocode(params: { lat: number; lng: number; source?: string }) {
    return apiPost<Record<string, unknown>>('/api/plan/reverse-geocode', undefined, { params: params as Record<string, unknown> })
  },

  /** 路径规划：返回真实道路 polyline（高德 lng,lat;lng,lat 格式） */
  route(data: {
    originLat: number
    originLng: number
    destLat: number
    destLng: number
    mode?: string
    city?: string
  }) {
    return apiPost<{
      success?: boolean
      distance?: number
      duration?: number
      polyline?: string
      mode?: string
      steps?: Array<{ polyline?: string }>
    }>('/api/plan/map/route', data)
  },
}
