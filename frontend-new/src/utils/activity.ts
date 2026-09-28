import type { Activity, VersionStats } from '@/api/types'

/**
 * 后端活动字段多为 snake_case，统一规范化为前端 camelCase
 */
const FIELD_MAP: Record<string, string> = {
  poi_name: 'poiName',
  activity_type: 'activityType',
  scheduled_start: 'scheduledStart',
  scheduled_end: 'scheduledEnd',
  duration_min: 'durationMin',
  travel_duration_min: 'travelDurationMin',
  transport_mode: 'transportMode',
  travel_distance_km: 'travelDistanceKm',
  poi_category: 'poiCategory',
  poi_address: 'poiAddress',
}

export function normalizeActivity(raw: unknown): Activity {
  const src = (raw && typeof raw === 'object' ? raw : {}) as Record<string, unknown>
  const out: Record<string, unknown> = { ...src }

  for (const [snake, camel] of Object.entries(FIELD_MAP)) {
    if (src[snake] !== undefined && src[camel] === undefined) {
      out[camel] = src[snake]
    }
  }

  // LLM 可能返回 name/type/startTime
  if (out.poiName === undefined && src.name !== undefined) out.poiName = src.name
  if (out.activityType === undefined && src.type !== undefined) out.activityType = src.type
  if (out.scheduledStart === undefined && src.startTime !== undefined) out.scheduledStart = src.startTime
  if (out.scheduledEnd === undefined && src.endTime !== undefined) out.scheduledEnd = src.endTime
  if (out.durationMin === undefined && src.duration !== undefined) out.durationMin = src.duration

  out.seq = Number(out.seq ?? 0) || 0
  out.day = Number(out.day ?? 1) || 1
  out.durationMin = Number(out.durationMin ?? 0) || 0
  out.travelDurationMin = Number(out.travelDurationMin ?? 0) || 0
  out.travelDistanceKm = Number(out.travelDistanceKm ?? 0) || 0
  if (src.travel_distance_m !== undefined && out.travelDistanceMeters === undefined) {
    out.travelDistanceMeters = Number(src.travel_distance_m) || 0
  }
  // 后端只有 km 时补齐米制字段，交通连接件才有距离可显示
  if (!out.travelDistanceMeters && Number(out.travelDistanceKm) > 0) {
    out.travelDistanceMeters = Math.round(Number(out.travelDistanceKm) * 1000)
  }
  out.activityType = String(out.activityType ?? 'visit')
  out.priority = String(out.priority ?? 'recommended')
  out.status = String(out.status ?? 'scheduled')
  out.transportMode = String(out.transportMode ?? 'walk')
  out.poiName = String(out.poiName ?? '')

  return out as Activity
}

export function normalizeActivities(list: unknown): Activity[] {
  if (!Array.isArray(list)) return []
  return list.map(normalizeActivity)
}

export interface LatLng {
  lat: number
  lng: number
}

/** 读取活动坐标（后端字段 lat/lng，可能是字符串） */
export function activityCoord(activity: Activity | null | undefined): LatLng | null {
  if (!activity) return null
  const lat = Number((activity as unknown as Record<string, unknown>).lat)
  const lng = Number((activity as unknown as Record<string, unknown>).lng)
  if (!Number.isFinite(lat) || !Number.isFinite(lng) || (lat === 0 && lng === 0)) return null
  return { lat, lng }
}

function haversineMeters(a: LatLng, b: LatLng): number {
  const R = 6371000
  const rad = (d: number) => (d * Math.PI) / 180
  const dLat = rad(b.lat - a.lat)
  const dLng = rad(b.lng - a.lng)
  const s =
    Math.sin(dLat / 2) * Math.sin(dLat / 2) +
    Math.cos(rad(a.lat)) * Math.cos(rad(b.lat)) * Math.sin(dLng / 2) * Math.sin(dLng / 2)
  return 2 * R * Math.atan2(Math.sqrt(s), Math.sqrt(1 - s))
}

/**
 * 两点间路程分钟数兜底估算（后端 travelDurationMin=0 或缺失时使用）
 * - 有坐标：直线距离 × 1.35 绕行系数，按交通方式时速折算
 * - 无坐标：按交通方式给经验值
 */
export function estimateTravelMinutes(
  from: LatLng | null,
  to: LatLng | null,
  mode?: string,
): number {
  const m = String(mode || '').toLowerCase()
  const speedKmh =
    m === 'drive' || m === 'taxi' ? 25 : m === 'transit' || m === 'subway' ? 18 : m === 'bike' ? 15 : 4.5
  if (!from || !to) {
    if (m === 'drive' || m === 'taxi' || m === 'transit' || m === 'subway') return 15
    return m === 'bike' ? 8 : 10
  }
  const roadKm = (haversineMeters(from, to) / 1000) * 1.35
  return Math.max(3, Math.min(240, Math.ceil((Math.max(0.2, roadKm) / speedKmh) * 60)))
}

/**
 * 同天相邻活动之间展示用的交通分钟数：
 * 优先真实值，为 0/缺失时按坐标与交通方式估算，保证不会显示「0分钟」。
 */
export function effectiveTravelMinutes(current: Activity, next?: Activity): number {
  const raw = Number(current.travelDurationMin ?? 0) || 0
  if (raw > 0) return raw
  if (!next) return 0
  return estimateTravelMinutes(activityCoord(current), activityCoord(next), current.transportMode)
}

/** 解析时间点：支持 "08:00"、"HH:mm:ss"、ISO datetime */
export function formatTimePoint(value?: string | null): string {
  if (!value) return ''
  const s = String(value).trim()
  const hm = s.match(/^(\d{1,2}):(\d{2})/)
  if (hm && !s.includes('T') && !s.includes('-')) {
    return `${hm[1].padStart(2, '0')}:${hm[2]}`
  }
  try {
    const d = new Date(s)
    if (Number.isNaN(d.getTime())) return hm ? `${hm[1].padStart(2, '0')}:${hm[2]}` : s
    return d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false })
  } catch {
    return s
  }
}

export function formatDurationText(min?: number | null): string {
  const m = Math.max(0, Math.floor(Number(min ?? 0) || 0))
  if (m <= 0) return '0分钟'
  if (m < 60) return `${m}分钟`
  const h = Math.floor(m / 60)
  const rest = m % 60
  return rest > 0 ? `${h}小时${rest}分` : `${h}小时`
}

export const TRANSPORT_LABELS: Record<string, string> = {
  walk: '步行',
  transit: '公交/地铁',
  drive: '驾车',
  bike: '骑行',
  subway: '地铁',
  taxi: '打车',
  mixed: '混合出行',
}

/** 从活动列表现算统计（后端旧数据 stats 缺字段时兜底） */
export function computeStatsFromActivities(list: Activity[]): Partial<VersionStats> {
  let visitDurationMin = 0
  let transitDurationMin = 0
  let mealDurationMin = 0
  let restDurationMin = 0
  const placeCount: Record<string, number> = { must: 0, recommended: 0, optional: 0 }

  for (const a of list) {
    const dur = Number(a.durationMin) || 0
    const travel = Number(a.travelDurationMin) || 0
    const type = String(a.activityType || 'visit')
    if (type === 'transit') {
      transitDurationMin += dur + travel
    } else if (type === 'meal') {
      mealDurationMin += dur
      transitDurationMin += travel
    } else if (type === 'rest') {
      restDurationMin += dur
      transitDurationMin += travel
    } else {
      visitDurationMin += dur
      transitDurationMin += travel
      const priority = a.priority && placeCount[a.priority] !== undefined ? a.priority : 'recommended'
      placeCount[priority] = (placeCount[priority] || 0) + 1
    }
  }

  return {
    totalDurationMin: visitDurationMin + transitDurationMin + mealDurationMin + restDurationMin,
    visitDurationMin,
    transitDurationMin,
    mealDurationMin,
    restDurationMin,
    placeCount,
  }
}

/** 优先使用后端 stats，缺失时从活动现算 */
export function resolveVersionStats(
  raw: Partial<VersionStats> | undefined | null,
  activities: Activity[],
): Partial<VersionStats> {
  const base = (raw || {}) as Partial<VersionStats>
  const hasDuration = Number(base.totalDurationMin ?? 0) > 0
  const hasPlaces = base.placeCount && Object.values(base.placeCount).some((v) => Number(v) > 0)
  if (hasDuration && hasPlaces) return base
  if (activities.length === 0) return base
  return { ...base, ...computeStatsFromActivities(activities) }
}
