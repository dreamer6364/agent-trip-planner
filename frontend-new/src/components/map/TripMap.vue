<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch, nextTick } from 'vue'
import MapPanel from './MapPanel.vue'
import { planApi } from '@/api/plan'
import type { Activity, RouteInfo } from '@/api/types'
import {
  getTypeTheme,
  getModeTheme,
  TYPE_LEGEND_ORDER,
  MODE_LEGEND_ORDER,
  TYPE_THEMES,
  MODE_THEMES,
} from '@/utils/mapTheme'

interface Props {
  activities: Activity[]
  routes?: RouteInfo[]
  /** 规划城市（如「成都」），用于无坐标时地图居中 */
  city?: string
  /** 外部（行程列表点击）指定的聚焦节点：地图跳转、高亮并打开该活动气泡 */
  focusActivity?: Activity | null
}

const props = withDefaults(defineProps<Props>(), {
  routes: () => [],
  city: '',
  focusActivity: null,
})

const emit = defineEmits<{
  selectActivity: [activity: Activity]
}>()

const mapContainer = ref<HTMLElement | null>(null)
const amapLoaded = ref(false)
const mapLoading = ref(true)
const mapType = ref<'standard' | 'satellite'>('standard')
const selectedActivity = ref<Activity | null>(null)
const mapPanelVisible = ref(false)
const cityCenter = ref<[number, number]>([104.1954, 37.5569])
const activityCoords = ref<Map<string, { lat: number; lng: number }>>(new Map())
const geocoding = ref(false)
const routePolylines = ref<Map<string, string>>(new Map())
const routeLoading = ref(false)
const selectedSeq = ref<number | null>(null)

let map: any = null
let markers: Array<{ seq: number; marker: any; el: HTMLElement }> = []
let polylines: any[] = []
let infoWindow: any = null
let geocodeToken = 0
let routeToken = 0
/** 最近一次“跳转到节点”的时间戳：期间屏蔽自适应视野，避免跳转被 setFitView 覆盖 */
let lastFocusAt = 0
/** 聚焦时按需地理编码的并发令牌 */
let focusGeoToken = 0

const sortedActivities = computed(() => {
  return [...props.activities].sort((a, b) => a.seq - b.seq)
})

const typeLegend = computed(() =>
  TYPE_LEGEND_ORDER.map((k) => ({ key: k, ...TYPE_THEMES[k] })),
)

const modeLegend = computed(() =>
  MODE_LEGEND_ORDER.map((k) => ({ key: k, ...MODE_THEMES[k] })),
)

/** 地理编码查询名：去掉「午餐·」等前缀，去掉城市前缀 */
function geocodeQueryName(name: string, city: string): string {
  let n = (name || '').trim()
  const dot = n.indexOf('·')
  if (dot >= 0 && dot < n.length - 1) n = n.slice(dot + 1).trim()
  if (city) {
    if (n.startsWith(city)) n = n.slice(city.length).trim()
    const cityFull = city.endsWith('市') ? city : city + '市'
    if (n.startsWith(cityFull)) n = n.slice(cityFull.length).trim()
  }
  return n || (name || '').trim()
}

function coordsFromResponse(data: unknown): { lat: number; lng: number } | null {
  const d = data as Record<string, unknown> | null
  if (!d) return null
  const lat = Number(d.lat)
  const lng = Number(d.lng)
  if (Number.isFinite(lat) && Number.isFinite(lng) && (lat !== 0 || lng !== 0)) {
    return { lat, lng }
  }
  return null
}

const CITY_CENTERS: Record<string, [number, number]> = {
  北京: [116.4074, 39.9042],
  上海: [121.4737, 31.2304],
  广州: [113.2644, 23.1291],
  深圳: [114.0579, 22.5431],
  成都: [104.0668, 30.5728],
  杭州: [120.1551, 30.2741],
  西安: [108.9398, 34.3416],
  重庆: [106.5516, 29.5630],
  苏州: [120.5853, 31.2989],
  南京: [118.7969, 32.0603],
  武汉: [114.3055, 30.5928],
  长沙: [112.9388, 28.2282],
  青岛: [120.3826, 36.0671],
  厦门: [118.0894, 24.4798],
  昆明: [102.7183, 25.0389],
  大理: [100.2676, 25.6065],
  丽江: [100.2270, 26.8550],
  三亚: [109.5119, 18.2528],
  哈尔滨: [126.5349, 45.8038],
  沈阳: [123.4315, 41.8057],
  天津: [117.3616, 39.3434],
  郑州: [113.6254, 34.7466],
  合肥: [117.2272, 31.8206],
  福州: [119.2965, 26.0745],
  南昌: [115.8579, 28.6820],
  贵阳: [106.6302, 26.6470],
  南宁: [108.3665, 22.8170],
  宁波: [121.5440, 29.8683],
  无锡: [120.3119, 31.4912],
  桂林: [110.2900, 25.2736],
  洛阳: [112.4540, 34.6197],
  敦煌: [94.6618, 40.1424],
  拉萨: [91.1721, 29.6520],
  乌鲁木齐: [87.6168, 43.8256],
}

function localCityCenter(city: string): [number, number] | null {
  if (!city) return null
  const name = city.replace(/市$/, '')
  if (CITY_CENTERS[name]) return CITY_CENTERS[name]
  for (const [k, v] of Object.entries(CITY_CENTERS)) {
    if (name.includes(k) || k.includes(name)) return v
  }
  return null
}

async function resolveGeo() {
  const token = ++geocodeToken
  const city = (props.city || '').trim()
  const acts = sortedActivities.value
  if (!city && acts.length === 0) return

  const local = localCityCenter(city)
  if (local) cityCenter.value = local

  geocoding.value = true
  try {
    if (city) {
      try {
        const resp = await planApi.geocode({ query: city })
        if (token !== geocodeToken) return
        const c = coordsFromResponse(resp)
        if (c) cityCenter.value = [c.lng, c.lat]
      } catch {
        // 保留本地中心
      }
    }

    const needQueries: string[] = []
    const needByQuery = new Map<string, string[]>()
    const coords = new Map<string, { lat: number; lng: number }>()

    for (const a of acts) {
      const id = String(a.id ?? a.seq)
      const rawLat = Number((a as any).lat)
      const rawLng = Number((a as any).lng)
      if (Number.isFinite(rawLat) && Number.isFinite(rawLng) && (rawLat || rawLng)) {
        coords.set(id, { lat: rawLat, lng: rawLng })
        continue
      }
      const q = geocodeQueryName(String(a.poiName || ''), city)
      if (!q || /自由活动|市区漫步|市区|buffer/i.test(q)) continue
      if (!needByQuery.has(q)) {
        needByQuery.set(q, [])
        needQueries.push(q)
      }
      needByQuery.get(q)!.push(id)
    }

    if (needQueries.length > 0) {
      try {
        const batch = await planApi.batchGeocode({ queries: needQueries, city: city || undefined })
        if (token !== geocodeToken) return
        const results = Array.isArray(batch?.results) ? batch.results : []
        results.forEach((r, i) => {
          const c = coordsFromResponse(r)
          if (!c) return
          const q = needQueries[i]
          const ids = needByQuery.get(q) || []
          for (const id of ids) coords.set(id, c)
        })
      } catch {
        for (const q of needQueries) {
          try {
            const r = await planApi.geocode({ query: q, city: city || undefined })
            if (token !== geocodeToken) return
            const c = coordsFromResponse(r)
            if (!c) continue
            for (const id of needByQuery.get(q) || []) coords.set(id, c)
          } catch {
            /* skip */
          }
        }
      }
    }

    if (token !== geocodeToken) return
    activityCoords.value = coords

    const hasLocalCenter = !!localCityCenter(city)
    if (!hasLocalCenter && coords.size > 0) {
      let sumLat = 0
      let sumLng = 0
      for (const c of coords.values()) {
        sumLat += c.lat
        sumLng += c.lng
      }
      cityCenter.value = [sumLng / coords.size, sumLat / coords.size]
    }

    void loadRealRoutes(token, city)
  } finally {
    if (token === geocodeToken) geocoding.value = false
  }
}

/**
 * 后端地理编码失败（坐标缺失）时的兜底：用高德 JS SDK 的 POI 搜索补齐坐标。
 * 没有这一步，地理编码失败的景点在地图上会直接「消失」（地标缺失）。
 * @returns 是否补到了新坐标
 */
async function fillMissingCoordsByPlace(): Promise<boolean> {
  const AMap = (window as any).AMap
  if (!AMap || !amapLoaded.value) return false

  const city = (props.city || '').trim()
  const missing = sortedActivities.value.filter((a) => !activityCoord(a))
  if (missing.length === 0) return false

  let search: any
  try {
    search = new AMap.PlaceSearch({ citylimit: !!city, pageSize: 5, pageIndex: 1 })
  } catch {
    return false
  }

  let filled = 0
  for (const activity of missing) {
    const q = geocodeQueryName(String(activity.poiName || ''), city)
    if (!q || /自由活动|市区漫步|市区|buffer/i.test(q)) continue
    try {
      const result: any = await new Promise((resolve) => {
        search.search({ keywords: q, city, citylimit: !!city }, (status: string, res: any) => {
          resolve(status === 'complete' ? res : null)
        })
      })
      const pois = result?.poiList?.pois
      const poi = Array.isArray(pois) && pois.length > 0 ? pois[0] : null
      const loc = poi?.location
      if (!loc) continue
      const lng = Number(typeof loc.getLng === 'function' ? loc.getLng() : loc.lng)
      const lat = Number(typeof loc.getLat === 'function' ? loc.getLat() : loc.lat)
      if (!Number.isFinite(lng) || !Number.isFinite(lat) || (lng === 0 && lat === 0)) continue
      activityCoords.value.set(String(activity.id ?? activity.seq), { lat, lng })
      filled++
    } catch {
      /* 单个 POI 搜索失败不影响其余 */
    }
  }

  if (filled > 0) {
    addMarkers()
    addPolylines()
    if (Date.now() - lastFocusAt >= 1500) fitMapView()
  }
  return filled > 0
}

async function loadRealRoutes(token: number, city: string) {
  const acts = sortedActivities.value
  if (acts.length < 2) return
  const t = ++routeToken
  routeLoading.value = true
  try {
    const pairs: Array<{
      key: string
      from: { lat: number; lng: number }
      to: { lat: number; lng: number }
      mode: string
    }> = []
    for (let i = 0; i < acts.length - 1; i++) {
      const a = activityCoord(acts[i])
      const b = activityCoord(acts[i + 1])
      if (!a || !b) continue
      if (Math.abs(a.lat - b.lat) < 1e-6 && Math.abs(a.lng - b.lng) < 1e-6) continue
      pairs.push({
        key: `${i}-${i + 1}`,
        from: a,
        to: b,
        mode: resolveRouteMode(acts[i], props.routes[i]),
      })
    }
    if (pairs.length === 0) return

    await Promise.all(
      pairs.map(async (p) => {
        try {
          const resp = await planApi.route({
            originLat: p.from.lat,
            originLng: p.from.lng,
            destLat: p.to.lat,
            destLng: p.to.lng,
            mode: p.mode,
            city: city || undefined,
          })
          if (t !== routeToken || token !== geocodeToken) return
          if (resp?.success) {
            const line = pickRoutePolyline(resp)
            if (line) routePolylines.value.set(p.key, line)
          }
        } catch {
          // 单段失败：该段用直线
        }
      }),
    )
    if (t === routeToken && amapLoaded.value && map) {
      addPolylines()
    }
  } finally {
    if (t === routeToken) routeLoading.value = false
  }
}

function resolveRouteMode(activity: Activity, route?: RouteInfo): string {
  const fromRoute = resolveModeFrom(route?.mode)
  if (fromRoute) return fromRoute
  const raw = String(activity.transportMode || (activity as any).transport_mode || '')
  const k = raw.toLowerCase().trim()
  if (k === 'walk' || k === 'walking') return 'walk'
  if (k === 'drive' || k === 'driving' || k === 'car' || k === 'taxi') return 'drive'
  if (k === 'bike' || k === 'bicycle' || k === 'cycling') return 'bike'
  if (k === 'subway' || k === 'metro') return 'transit'
  if (k === 'transit') return 'transit'
  return 'transit'
}

function resolveModeFrom(mode?: string): string | null {
  if (!mode) return null
  const k = mode.toLowerCase().trim()
  if (k === 'walk' || k === 'walking') return 'walk'
  if (k === 'drive' || k === 'driving' || k === 'car') return 'drive'
  if (k === 'bike' || k === 'bicycle' || k === 'cycling') return 'bike'
  if (k === 'transit' || k === 'subway' || k === 'metro') return 'transit'
  if (k === 'mixed') return 'mixed'
  return null
}

function pickRoutePolyline(resp: {
  polyline?: string
  steps?: Array<{ polyline?: string }>
}): string | undefined {
  if (resp.polyline && resp.polyline.trim()) return resp.polyline.trim()
  const steps = Array.isArray(resp.steps) ? resp.steps : []
  const parts: string[] = []
  let prevLast: string | null = null
  for (const step of steps) {
    const p = (step?.polyline || '').trim()
    if (!p) continue
    for (const pt of p.split(';')) {
      const t = pt.trim()
      if (!t) continue
      if (prevLast !== null && t === prevLast) continue
      parts.push(t)
      prevLast = t
    }
  }
  return parts.length >= 2 ? parts.join(';') : undefined
}

function activityCoord(activity: Activity): { lat: number; lng: number } | null {
  const id = String(activity.id ?? activity.seq)
  const cached = activityCoords.value.get(id)
  if (cached) return cached
  const lat = Number((activity as any).lat)
  const lng = Number((activity as any).lng)
  if (Number.isFinite(lat) && Number.isFinite(lng) && (lat || lng)) return { lat, lng }
  return null
}

const showFallback = computed(() => !amapLoaded.value)

function loadAMap(): Promise<void> {
  return new Promise((resolve, reject) => {
    if ((window as any).AMap) {
      resolve()
      return
    }

    const amapKey = __VITE_AMAP_KEY__ || ''

    ;(window as any)._AMapSecurityConfig = {
      securityJsCode: '',
    }

    const script = document.createElement('script')
    script.src = `https://webapi.amap.com/maps?v=2.0&key=${amapKey}&plugin=AMap.Scale,AMap.InfoWindow,AMap.PlaceSearch,AMap.Geocoder`
    script.onload = () => resolve()
    script.onerror = () => reject(new Error('Failed to load AMap JS API'))
    document.head.appendChild(script)
  })
}

function initMap() {
  if (!mapContainer.value || !(window as any).AMap) return

  const AMap = (window as any).AMap
  map = new AMap.Map(mapContainer.value, {
    zoom: 12,
    center: cityCenter.value,
    mapStyle: 'amap://styles/whitesmoke',
    viewMode: '2D',
  })

  map.addControl(new AMap.Scale())

  infoWindow = new AMap.InfoWindow({
    offset: new AMap.Pixel(0, -28),
    isCustom: true,
    autoMove: true,
    closeWhenClickMap: true,
  })

  addMarkers()
  addPolylines()
  fitMapView()
  // 外部已指定聚焦节点（如从行程列表点进来、或地图刚挂载）：初始化完成后立即跳转
  if (props.focusActivity) {
    focusOnMap(props.focusActivity)
  }
}

function escapeHtml(s: string): string {
  return String(s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

function buildInfoHtml(activity: Activity): string {
  const theme = getTypeTheme(activity.activityType)
  const name = escapeHtml(String(activity.poiName || ''))
  const type = escapeHtml(theme.label)
  const bits: string[] = []
  const rating = Number(activity.rating)
  if (!Number.isNaN(rating) && rating > 0) bits.push(`★ ${rating.toFixed(1)}`)
  const cost = String(activity.cost ?? '').trim()
  if (cost && cost !== '0') bits.push(cost.includes('¥') ? cost : `人均 ¥${cost}`)
  const metaLine = bits.length
    ? `<div style="font-size:11px;color:#b45309;font-weight:600;margin-top:2px;">${escapeHtml(bits.join(' · '))}</div>`
    : ''
  const address = String(activity.poiAddress ?? '').trim()
  const addressLine = address
    ? `<div style="font-size:11px;color:#64748b;margin-top:2px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">${escapeHtml(address)}</div>`
    : ''
  return `
    <div style="
      min-width:140px;max-width:240px;padding:8px 12px;border-radius:12px;
      background:rgba(255,255,255,0.97);box-shadow:0 4px 16px rgba(0,0,0,0.12);
      font-family:Inter,'Noto Sans SC',sans-serif;
    ">
      <div style="display:flex;align-items:center;gap:6px;margin-bottom:4px;">
        <span style="display:inline-flex;width:18px;height:18px;border-radius:50%;background:${theme.fill};color:#fff;font-size:11px;font-weight:700;align-items:center;justify-content:center;">${activity.seq}</span>
        <span style="font-size:12px;color:${theme.fill};font-weight:600;">${type}</span>
      </div>
      <div style="font-size:13px;font-weight:700;color:#1e293b;line-height:1.35;">${name}</div>
      ${metaLine}
      ${addressLine}
    </div>
  `
}

function applyMarkerSelectedStyle(entry: (typeof markers)[number], selected: boolean) {
  if (!entry?.el) return
  const inner = entry.el.querySelector('[data-marker-inner]') as HTMLElement | null
  if (!inner) return
  if (selected) {
    inner.style.transform = 'scale(1.2)'
    inner.style.boxShadow = `0 0 0 4px rgba(59,108,247,0.35), 0 4px 12px rgba(0,0,0,0.25)`
    inner.style.zIndex = '2'
  } else {
    inner.style.transform = 'scale(1)'
    inner.style.boxShadow = '0 2px 8px rgba(0,0,0,0.2)'
    inner.style.zIndex = ''
  }
}

function refreshMarkerSelection() {
  for (const entry of markers) {
    applyMarkerSelectedStyle(entry, entry.seq === selectedSeq.value)
  }
}

function selectOnMap(activity: Activity) {
  selectedActivity.value = activity
  selectedSeq.value = activity.seq
  mapPanelVisible.value = true
  refreshMarkerSelection()
  const coord = activityCoord(activity)
  if (coord && infoWindow && map) {
    infoWindow.setContent(buildInfoHtml(activity))
    infoWindow.open(map, new (window as any).AMap.LngLat(coord.lng, coord.lat))
  }
  emit('selectActivity', activity)
}

/**
 * 跳转到指定活动节点（行程列表点击触发）：
 * 高亮 marker、打开信息气泡、把地图中心平移到该点并放大到 16 级
 */
function focusOnMap(activity: Activity) {
  selectedActivity.value = activity
  selectedSeq.value = activity.seq
  mapPanelVisible.value = true
  refreshMarkerSelection()

  const coord = activityCoord(activity)
  if (coord && infoWindow && map) {
    infoWindow.setContent(buildInfoHtml(activity))
    infoWindow.open(map, new (window as any).AMap.LngLat(coord.lng, coord.lat))
  }

  // 地图尚未初始化时先记住高亮，initMap 完成后会再执行一次本函数完成跳转
  if (!map || !amapLoaded.value) return

  if (coord) {
    panToCoord(coord)
    return
  }
  geocodeAndPan(activity)
}

/** 平移 + 缩放至目标坐标（16 级），并记录时间戳屏蔽随后的自适应视野 */
function panToCoord(coord: { lat: number; lng: number }) {
  if (!map) return
  lastFocusAt = Date.now()
  try {
    map.setZoomAndCenter(16, [coord.lng, coord.lat], false, 600)
  } catch {
    try {
      map.setCenter([coord.lng, coord.lat])
      map.setZoom(16)
    } catch {
      /* ignore */
    }
  }
}

/** 该活动还没有坐标时：现场地理编码 → 回填 marker → 跳转；失败则落回城市中心 */
async function geocodeAndPan(activity: Activity) {
  const token = ++focusGeoToken
  const q = geocodeQueryName(String(activity.poiName || ''), props.city || '')
  if (!q) {
    if (map) {
      lastFocusAt = Date.now()
      map.setCenter(cityCenter.value)
    }
    return
  }
  try {
    const resp = await planApi.geocode({ query: q, city: props.city || undefined })
    const c = coordsFromResponse(resp)
    if (token !== focusGeoToken || !c || !map) return
    activityCoords.value.set(String(activity.id ?? activity.seq), c)
    addMarkers()
    if (selectedSeq.value !== activity.seq) return
    panToCoord(c)
    if (infoWindow) {
      infoWindow.setContent(buildInfoHtml(activity))
      infoWindow.open(map, new (window as any).AMap.LngLat(c.lng, c.lat))
    }
  } catch {
    if (token === focusGeoToken && map) {
      lastFocusAt = Date.now()
      map.setCenter(cityCenter.value)
    }
  }
}

function addMarkers() {
  if (!map) return
  const AMap = (window as any).AMap

  markers.forEach((m) => map.remove(m.marker))
  markers = []
  if (infoWindow) infoWindow.close()

  sortedActivities.value.forEach((activity) => {
    const coord = activityCoord(activity)
    if (!coord) return
    const { lat, lng } = coord
    const theme = getTypeTheme(activity.activityType)

    const content = document.createElement('div')
    content.className = 'custom-marker'
    content.innerHTML = `
      <div data-marker-inner style="
        position: relative;
        width: 34px;
        height: 34px;
        display: flex;
        align-items: center;
        justify-content: center;
        background: ${theme.fill};
        border: 3px solid ${theme.stroke};
        border-radius: 50%;
        color: ${theme.text};
        font-size: 13px;
        font-weight: 700;
        font-family: Inter,'Noto Sans SC',sans-serif;
        box-shadow: 0 2px 8px rgba(0,0,0,0.2);
        cursor: pointer;
        transition: transform 0.2s, box-shadow 0.2s;
      ">
        ${activity.seq}
      </div>
    `

    const marker = new AMap.Marker({
      position: new AMap.LngLat(lng, lat),
      content: content,
      anchor: 'center',
      offset: new AMap.Pixel(0, 0),
      zIndex: 100,
    })

    marker.on('click', () => {
      selectOnMap(activity)
    })

    marker.on('mouseover', () => {
      if (!infoWindow || !map) return
      infoWindow.setContent(buildInfoHtml(activity))
      infoWindow.open(map, new AMap.LngLat(lng, lat))
    })

    markers.push({ seq: activity.seq, marker, el: content })
    map.add(marker)
  })

  refreshMarkerSelection()
}

function addPolylines() {
  if (!map) return
  const AMap = (window as any).AMap

  polylines.forEach((p) => map.remove(p))
  polylines = []

  const activities = sortedActivities.value
  if (activities.length < 2) return

  for (let i = 0; i < activities.length - 1; i++) {
    const key = `${i}-${i + 1}`
    const route = props.routes[i]
    const mode = resolveRouteMode(activities[i], route)
    const theme = getModeTheme(mode)
    let pathPoints: [number, number][] = []

    const real = routePolylines.value.get(key)
    if (real) {
      pathPoints = decodePolyline(real)
    } else if (route?.polyline) {
      pathPoints = decodePolyline(route.polyline)
    } else {
      const a = activityCoord(activities[i])
      const b = activityCoord(activities[i + 1])
      if (a && b) {
        pathPoints = [
          [a.lng, a.lat],
          [b.lng, b.lat],
        ]
      }
    }

    if (pathPoints.length < 2) continue

    const path = pathPoints.map((p) => new AMap.LngLat(p[0], p[1]))
    const isFallback = !real && !route?.polyline

    const polyline = new AMap.Polyline({
      path,
      strokeColor: theme.color,
      strokeWeight: isFallback ? 3 : 5,
      strokeOpacity: isFallback ? 0.5 : 0.88,
      lineJoin: 'round',
      lineCap: 'round',
      showDir: !isFallback,
      strokeStyle: isFallback ? 'dashed' : 'solid',
      zIndex: 50,
    })

    polylines.push(polyline)
    map.add(polyline)
  }

  if (polylines.length > 0 && markers.length > 0) {
    try {
      map.setFitView(
        markers.map((m) => m.marker),
        false,
        [60, 60, 60, 60],
      )
    } catch {
      /* ignore */
    }
  }
}

function decodePolyline(encoded: string): [number, number][] {
  const s = (encoded || '').trim()
  if (!s) return []

  if (s.includes(',') && (s.includes(';') || s.split(',').length === 2)) {
    const points: [number, number][] = []
    const parts = s.includes(';') ? s.split(';') : [s]
    for (const part of parts) {
      const seg = part.split(',')
      if (seg.length < 2) continue
      const lng = Number(seg[0])
      const lat = Number(seg[1])
      if (Number.isFinite(lng) && Number.isFinite(lat)) {
        points.push([lng, lat])
      }
    }
    if (points.length >= 1) return points
  }

  if (!/^[\x20-\x7e]+$/.test(s)) return []

  const points: [number, number][] = []
  let index = 0
  let lat = 0
  let lng = 0

  try {
    while (index < s.length) {
      let shift = 0
      let result = 0
      let byte: number
      do {
        byte = s.charCodeAt(index++) - 63
        if (Number.isNaN(byte)) return points
        result |= (byte & 0x1f) << shift
        shift += 5
      } while (byte >= 0x20 && index < s.length)
      const dlat = result & 1 ? ~(result >> 1) : result >> 1
      lat += dlat

      shift = 0
      result = 0
      do {
        byte = s.charCodeAt(index++) - 63
        if (Number.isNaN(byte)) return points
        result |= (byte & 0x1f) << shift
        shift += 5
      } while (byte >= 0x20 && index < s.length)
      const dlng = result & 1 ? ~(result >> 1) : result >> 1
      lng += dlng

      points.push([lng / 1e5, lat / 1e5])
    }
  } catch {
    return points
  }

  return points
}

function fitMapView() {
  if (!map) return
  // 跳转到节点后的 1.5 秒内不重置视野，保证跳转结果可见
  if (Date.now() - lastFocusAt < 1500) return
  const markerObjs = markers.map((m) => m.marker)
  if (markerObjs.length === 0) {
    map.setZoomAndCenter(11, cityCenter.value)
    return
  }
  if (markerObjs.length === 1) {
    map.setZoomAndCenter(15, cityCenter.value)
    return
  }
  map.setFitView(markerObjs, false, [80, 80, 80, 80])
}

function toggleMapType() {
  if (!map) return
  mapType.value = mapType.value === 'standard' ? 'satellite' : 'standard'
  if (mapType.value === 'satellite') {
    map.setLayers([new (window as any).AMap.TileLayer.Satellite()])
  } else {
    map.setLayers([new (window as any).AMap.TileLayer()])
  }
}

function handleZoomIn() {
  map?.zoomIn()
}

function handleZoomOut() {
  map?.zoomOut()
}

function handleMapPanelClose() {
  mapPanelVisible.value = false
  selectedActivity.value = null
  selectedSeq.value = null
  refreshMarkerSelection()
  if (infoWindow) infoWindow.close()
}

function handleFocusTimeline() {
  if (selectedActivity.value) {
    emit('selectActivity', selectedActivity.value)
  }
  handleMapPanelClose()
}

watch(
  () => props.activities,
  async () => {
    await resolveGeo()
    if (amapLoaded.value) {
      nextTick(async () => {
        const focused = Date.now() - lastFocusAt < 1500
        if (map && !focused) map.setCenter(cityCenter.value)
        addMarkers()
        addPolylines()
        if (!focused) fitMapView()
        await fillMissingCoordsByPlace()
      })
    }
  },
  { deep: true },
)

watch(
  () => props.focusActivity,
  (val) => {
    if (val) focusOnMap(val)
  },
)

watch(
  () => props.city,
  async () => {
    await resolveGeo()
    if (amapLoaded.value && map) {
      map.setCenter(cityCenter.value)
      addMarkers()
      addPolylines()
      fitMapView()
      await fillMissingCoordsByPlace()
    }
  },
)

watch(
  () => props.routes,
  () => {
    if (amapLoaded.value && map) addPolylines()
  },
  { deep: true },
)

onMounted(async () => {
  mapLoading.value = true
  await resolveGeo()
  try {
    await loadAMap()
    amapLoaded.value = true
    await nextTick()
    initMap()
    // 地理编码失败的景点用 POI 搜索兜底，保证地图上地标齐全
    void fillMissingCoordsByPlace()
  } catch {
    amapLoaded.value = false
  } finally {
    mapLoading.value = false
  }
})

onUnmounted(() => {
  if (map) {
    map.destroy()
    map = null
  }
})
</script>

<template>
  <div class="relative h-full w-full overflow-hidden bg-surface-100">
    <!-- AMap Container -->
    <div ref="mapContainer" class="h-full w-full" :class="{ hidden: showFallback }" />

    <!-- Loading -->
    <div
      v-if="mapLoading && !amapLoaded"
      class="absolute inset-0 z-30 flex flex-col items-center justify-center bg-surface-50"
    >
      <div class="mb-3 h-10 w-10 animate-spin rounded-full border-[3px] border-brand-100 border-t-brand-500" />
      <p class="text-sm font-medium text-surface-500">地图加载中…</p>
      <p v-if="city" class="mt-1 text-xs text-brand-600">{{ city }}</p>
    </div>

    <!-- Fallback Placeholder -->
    <div
      v-else-if="showFallback"
      class="flex h-full w-full flex-col items-center justify-center overflow-y-auto bg-gradient-to-br from-brand-50 via-white to-accent-50 p-6"
    >
      <div class="mb-4 text-center">
        <div class="mx-auto mb-3 flex h-14 w-14 items-center justify-center rounded-2xl bg-brand-100">
          <i class="ri-map-2-line text-3xl text-brand-600" />
        </div>
        <h3 class="text-lg font-semibold text-surface-800">行程地图</h3>
        <p class="mt-1 text-sm text-surface-500">景点分布与路线可视化</p>
        <p v-if="city" class="mt-0.5 text-xs font-medium text-brand-600">{{ city }}</p>
      </div>

      <div class="grid max-h-[50%] w-full max-w-md grid-cols-1 gap-2 overflow-y-auto sm:grid-cols-2">
        <div
          v-for="activity in sortedActivities"
          :key="activity.seq"
          class="flex items-center gap-2 rounded-xl bg-white/80 px-3 py-2 shadow-sm backdrop-blur-sm"
        >
          <div
            class="flex h-7 w-7 flex-shrink-0 items-center justify-center rounded-full text-xs font-bold text-white"
            :style="{ background: getTypeTheme(activity.activityType).fill }"
          >
            {{ activity.seq }}
          </div>
          <span class="truncate text-xs font-medium text-surface-700">{{ activity.poiName }}</span>
        </div>
      </div>

      <p class="mt-4 text-xs text-surface-400">地图组件加载失败，以下为行程点列表</p>
    </div>

    <!-- Map Controls -->
    <div class="absolute right-3 top-3 z-10 flex flex-col gap-2">
      <button
        class="flex h-9 w-9 items-center justify-center rounded-lg bg-white/90 text-surface-600 shadow-card backdrop-blur-sm transition-all hover:bg-white hover:text-brand-600 hover:shadow-card-hover"
        title="切换地图类型"
        @click="toggleMapType"
      >
        <i :class="mapType === 'standard' ? 'ri-road-map-line' : 'ri-earth-line'" />
      </button>
      <button
        class="flex h-9 w-9 items-center justify-center rounded-lg bg-white/90 text-surface-600 shadow-card backdrop-blur-sm transition-all hover:bg-white hover:text-brand-600 hover:shadow-card-hover"
        title="放大"
        @click="handleZoomIn"
      >
        <i class="ri-add-line text-lg" />
      </button>
      <button
        class="flex h-9 w-9 items-center justify-center rounded-lg bg-white/90 text-surface-600 shadow-card backdrop-blur-sm transition-all hover:bg-white hover:text-brand-600 hover:shadow-card-hover"
        title="缩小"
        @click="handleZoomOut"
      >
        <i class="ri-subtract-line text-lg" />
      </button>
    </div>

    <!-- Activity Type Legend -->
    <div class="absolute bottom-3 left-3 z-10 max-w-[46%] rounded-xl bg-white/92 px-3 py-2.5 shadow-card backdrop-blur-sm">
      <p class="mb-1.5 text-[10px] font-semibold tracking-wider text-surface-400">地点类型</p>
      <div class="flex flex-wrap gap-x-3 gap-y-1">
        <div v-for="item in typeLegend" :key="item.key" class="flex items-center gap-1.5">
          <span
            class="flex h-4 w-4 items-center justify-center rounded-full"
            :style="{ background: item.fill }"
          >
            <i :class="[item.icon, 'text-[9px] text-white']" />
          </span>
          <span class="text-[10px] font-medium text-surface-600">{{ item.label }}</span>
        </div>
      </div>
    </div>

    <!-- Route Mode Legend -->
    <div
      v-if="sortedActivities.length > 1"
      class="absolute bottom-3 right-3 z-10 max-w-[50%] rounded-xl bg-white/92 px-3 py-2 shadow-card backdrop-blur-sm"
    >
      <div class="flex flex-wrap items-center gap-x-3 gap-y-1">
        <span v-if="routeLoading" class="flex items-center gap-1 text-[10px] font-medium text-brand-600">
          <span class="inline-block h-2.5 w-2.5 animate-spin rounded-full border border-brand-200 border-t-brand-500" />
          加载真实路线…
        </span>
        <div v-for="item in modeLegend" :key="item.key" class="flex items-center gap-1.5">
          <span class="flex items-center">
            <i :class="[item.icon, 'text-xs']" :style="{ color: item.color }" />
          </span>
          <span class="h-0.5 w-4 rounded" :style="{ background: item.color }" />
          <span class="text-[10px] text-surface-500">{{ item.label }}</span>
        </div>
      </div>
    </div>

    <!-- Map Side Panel -->
    <MapPanel
      :activity="selectedActivity"
      :visible="mapPanelVisible"
      @close="handleMapPanelClose"
      @focus-timeline="handleFocusTimeline"
    />
  </div>
</template>

<style scoped>
.custom-marker {
  animation: markerDrop 0.3s ease-out;
}

@keyframes markerDrop {
  0% {
    transform: translateY(-20px);
    opacity: 0;
  }
  100% {
    transform: translateY(0);
    opacity: 1;
  }
}
</style>
