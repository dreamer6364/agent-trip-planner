import { ref, watch, onMounted, unref } from 'vue'
import type { MaybeRef } from 'vue'
import { tripApi } from '@/api/trip'

/** 关键词 -> 已解析的封面图直链 */
const cache = new Map<string, string>()
/** 关键词 -> 进行中的请求（并发去重） */
const inflight = new Map<string, Promise<string | null>>()

interface TripCoverSource {
  city?: string | null
  landmarks?: string[]
  title?: string
}

/**
 * 由行程的城市 + 景点生成封面图搜索关键词
 * 例：{ city: '杭州', landmarks: ['西湖','雷峰塔','河坊街'] } -> '杭州 西湖 雷峰塔'
 */
export function tripCoverKeyword(trip: TripCoverSource): string {
  const city = (trip.city || '').trim()
  const parts: string[] = []
  if (city) parts.push(city)
  for (const landmark of trip.landmarks || []) {
    const name = (landmark || '').trim()
    if (!name) continue
    if (city && (city.includes(name) || name.includes(city))) continue
    parts.push(name)
    if (parts.length >= 3) break
  }
  if (parts.length === 0 && trip.title) parts.push(trip.title.slice(0, 24))
  return parts.join(' ').trim()
}

/** 搜索封面图（模块级缓存 + 并发去重，失败返回 null 表示使用默认底图） */
export function resolveTripCover(keyword: string): Promise<string | null> {
  const kw = keyword.trim()
  if (!kw) return Promise.resolve(null)

  const cached = cache.get(kw)
  if (cached) return Promise.resolve(cached)

  const running = inflight.get(kw)
  if (running) return running

  const task = tripApi
    .searchCover(kw)
    .then((res) => {
      const url = res?.url || ''
      if (url) cache.set(kw, url)
      return url || null
    })
    .catch(() => null)
    .finally(() => {
      inflight.delete(kw)
    })

  inflight.set(kw, task)
  return task
}

/**
 * 行程封面图组合式
 * 返回可直接绑定到 <img :src> 的直链；未取到时为 null（由卡片渐变底图兜底），
 * 图片加载失败时由调用方置 coverFailed 回退。
 */
export function useTripCover(keyword: MaybeRef<string>) {
  const coverUrl = ref<string | null>(null)
  const coverLoaded = ref(false)
  const coverFailed = ref(false)
  let loadToken = 0

  async function load() {
    const kw = unref(keyword).trim()
    const token = Date.now() + Math.random()
    loadToken = token
    coverUrl.value = null
    coverLoaded.value = false
    coverFailed.value = false
    if (!kw) return

    const url = await resolveTripCover(kw)
    if (loadToken !== token) return
    if (!url) return
    coverUrl.value = url
  }

  onMounted(load)
  watch(() => unref(keyword), load)

  return { coverUrl, coverLoaded, coverFailed, reload: load }
}
