/**
 * 高德 Web 端导航链接工具（v1.22.0 线段导航）
 *
 * 有起终点坐标时走 `uri.amap.com/navigation`（网页版路线规划，callnative=1 尝试唤起高德 App）；
 * 坐标缺失（如休息节点无坐标）时降级为 `uri.amap.com/direction` POI 名称导航。
 */

export interface NavPoint {
  lat?: number | null
  lng?: number | null
  name?: string
}

/** 项目内出行方式 → 高德导航 mode（car/bus/walk/bike/ride） */
const AMAP_MODE: Record<string, string> = {
  walk: 'walk',
  walking: 'walk',
  bike: 'bike',
  bicycle: 'bike',
  cycling: 'bike',
  drive: 'car',
  driving: 'car',
  car: 'car',
  taxi: 'ride',
  transit: 'bus',
  bus: 'bus',
  subway: 'bus',
  metro: 'bus',
  mixed: 'car',
}

function hasCoord(p?: NavPoint | null): boolean {
  if (!p) return false
  const lat = Number(p.lat)
  const lng = Number(p.lng)
  return Number.isFinite(lat) && Number.isFinite(lng) && (lat !== 0 || lng !== 0)
}

/** 构造高德导航 URL（起点 → 终点，按出行方式选 car/bus/walk/bike） */
export function buildNavUrl(from: NavPoint, to: NavPoint, mode?: string): string {
  const m = AMAP_MODE[String(mode || '').toLowerCase().trim()] || 'car'
  const enc = (s?: string) => encodeURIComponent(s || '')
  if (hasCoord(from) && hasCoord(to)) {
    return (
      `https://uri.amap.com/navigation?from=${from!.lng},${from!.lat},${enc(from!.name)}` +
      `&to=${to!.lng},${to!.lat},${enc(to!.name)}` +
      `&mode=${m}&policy=1&src=tripforge&coordinate=gaode&callnative=1`
    )
  }
  return (
    `https://uri.amap.com/direction?from=${enc(from.name)}` +
    `&to=${enc(to.name)}&mode=${m}&policy=1&src=tripforge&callnative=1`
  )
}

/** 新标签打开导航（坐标缺失降级为 POI 名称导航；两端均无有效输入时不动作） */
export function openNavigation(from: NavPoint, to: NavPoint, mode?: string): void {
  const usable = (p?: NavPoint) => hasCoord(p) || !!(p?.name && p.name.trim())
  if (!usable(from) || !usable(to)) return
  window.open(buildNavUrl(from, to, mode), '_blank', 'noopener')
}
