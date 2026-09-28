<script setup lang="ts">
import { computed } from 'vue'
import type { Activity, TripVersion } from '@/api/types'
import { normalizeActivities, formatTimePoint, computeStatsFromActivities } from '@/utils/activity'

/**
 * 版本对比视图（v1.18.0 增强）
 * - 按 POI 名称对齐的双栏 diff：保留/调整/新增/移除 四态着色
 * - 餐次按「天+餐段槽位」对齐（换版换餐厅不再误判为增删，标注"午餐换店"）
 * - 行归属以旧版的天为组；跨天移动在徽标下标注 D1→D2 及时间/时长/顺序变化点
 * - 顶部双版本选择 + 换位 + 统计对比（活动数/游览/交通/餐次）+ 差异摘要
 */
const props = defineProps<{
  versions: TripVersion[]
  left: string
  right: string
  /** 行程开始时间（用于天日期展示） */
  tripStart?: string
}>()

const emit = defineEmits<{
  'update:left': [id: string]
  'update:right': [id: string]
}>()

const TYPE_LABELS: Record<string, string> = {
  visit: '景点',
  meal: '用餐',
  transit: '交通',
  museum: '博物馆',
  park: '公园',
  temple: '寺庙',
  shopping: '购物',
  rest: '休息',
}

function versionActs(v?: TripVersion): Activity[] {
  if (!v) return []
  return normalizeActivities(v.activities).sort((a, b) => (a.seq || 0) - (b.seq || 0))
}

const leftVersion = computed(() => props.versions.find((v) => v.id === props.left))
const rightVersion = computed(() => props.versions.find((v) => v.id === props.right))
const leftActs = computed(() => versionActs(leftVersion.value))
const rightActs = computed(() => versionActs(rightVersion.value))

function versionLabel(v?: TripVersion): string {
  if (!v) return '选择版本'
  const date = new Date(v.createdAt).toLocaleDateString('zh-CN', { month: 'numeric', day: 'numeric' })
  return `版本 ${v.versionNum} · ${date} · ${normalizeActivities(v.activities).length}项`
}

function swapSides() {
  const l = props.left
  emit('update:left', props.right)
  emit('update:right', l)
}

const normName = (n: string) => n.replace(/\s+/g, '')

const MEAL_WORDS = ['早餐', '午餐', '晚餐', '下午茶', '夜宵'] as const

/** 餐次槽位：优先取名称里的餐段词，其次按开始时间推断（v1.18.0 换版对比用） */
function mealSlot(a: Activity): string {
  const name = a.poiName || ''
  const hit = MEAL_WORDS.find((w) => name.includes(w))
  if (hit) return hit
  const hm = String(a.scheduledStart || '').match(/(\d{1,2}):(\d{2})/)
  const min = hm ? Number(hm[1]) * 60 + Number(hm[2]) : 12 * 60
  if (min < 10 * 60 + 30) return '早餐'
  if (min < 14 * 60 + 30) return '午餐'
  if (min >= 16 * 60 + 30) return '晚餐'
  return '加餐'
}

interface DiffRow {
  key: string
  day: number
  left?: Activity
  right?: Activity
  status: 'kept' | 'changed' | 'added' | 'removed'
  /** 变化点标签（时间/天/时长/餐厅/顺序），显示在状态徽标下方 */
  changes: string[]
}

const rows = computed<DiffRow[]>(() => {
  const out: DiffRow[] = []
  let uid = 0

  // 第一轮：按 POI 名称匹配（同名保留/调整）
  const lMap = new Map(leftActs.value.map((a) => [normName(a.poiName), a]))
  const matchedL = new Set<Activity>()
  const rightMatched = new Set<Activity>()

  for (const r of rightActs.value) {
    const l = lMap.get(normName(r.poiName))
    if (!l || matchedL.has(l)) continue
    matchedL.add(l)
    rightMatched.add(r)
    const changes: string[] = []
    if (formatTimePoint(l.scheduledStart) !== formatTimePoint(r.scheduledStart) ||
        formatTimePoint(l.scheduledEnd) !== formatTimePoint(r.scheduledEnd)) changes.push('时间')
    if (String(l.day || 1) !== String(r.day || 1)) changes.push(`D${l.day || 1}→D${r.day || 1}`)
    if ((l.durationMin || 0) !== (r.durationMin || 0)) changes.push('时长')
    if (String(l.day || 1) === String(r.day || 1) && (l.seq || 0) !== (r.seq || 0)) changes.push('顺序')
    // 行归属：以旧版的天为组浏览，跨天移动通过 changes 标注
    out.push({
      key: `m${uid++}`,
      day: Number(l.day) || Number(r.day) || 1,
      left: l,
      right: r,
      status: changes.length ? 'changed' : 'kept',
      changes,
    })
  }

  // 第二轮：未匹配的餐次按「天 + 餐段槽位」对齐（换版后餐厅名必变，名称匹配会漏判为增删）
  const mealPool = new Map<string, Activity[]>()
  for (const l of leftActs.value) {
    if (matchedL.has(l) || l.activityType !== 'meal') continue
    const k = `${l.day || 1}-${mealSlot(l)}`
    const list = mealPool.get(k) || []
    list.push(l)
    mealPool.set(k, list)
  }
  for (const r of rightActs.value) {
    if (rightMatched.has(r) || r.activityType !== 'meal') continue
    const k = `${r.day || 1}-${mealSlot(r)}`
    const pool = mealPool.get(k)
    const l = pool?.shift()
    if (!l) continue
    matchedL.add(l)
    rightMatched.add(r)
    const changes: string[] = []
    if (formatTimePoint(l.scheduledStart) !== formatTimePoint(r.scheduledStart) ||
        formatTimePoint(l.scheduledEnd) !== formatTimePoint(r.scheduledEnd)) changes.push('时间')
    if ((l.durationMin || 0) !== (r.durationMin || 0)) changes.push('时长')
    if (normName(l.poiName) !== normName(r.poiName)) changes.push(`${mealSlot(r)}换店`)
    out.push({
      key: `m${uid++}`,
      day: Number(l.day) || Number(r.day) || 1,
      left: l,
      right: r,
      status: changes.length ? 'changed' : 'kept',
      changes,
    })
  }

  // 第三轮：剩余未匹配 → 新增（右）/ 移除（左）
  for (const r of rightActs.value) {
    if (rightMatched.has(r)) continue
    out.push({ key: `m${uid++}`, day: Number(r.day) || 1, right: r, status: 'added', changes: [] })
  }
  for (const l of leftActs.value) {
    if (matchedL.has(l)) continue
    out.push({ key: `m${uid++}`, day: Number(l.day) || 1, left: l, status: 'removed', changes: [] })
  }

  // 排序：先按天，组内按左侧序号（新增项按右侧序号）交错排布
  return out.sort((a, b) => {
    if (a.day !== b.day) return a.day - b.day
    const pa = a.left?.seq ?? a.right?.seq ?? 0
    const pb = b.left?.seq ?? b.right?.seq ?? 0
    if (pa !== pb) return pa - pb
    return (a.right?.seq ?? 0) - (b.right?.seq ?? 0)
  })
})

const dayGroups = computed(() => {
  const map = new Map<number, DiffRow[]>()
  for (const row of rows.value) {
    const list = map.get(row.day) || []
    list.push(row)
    map.set(row.day, list)
  }
  return Array.from(map.entries())
    .sort((a, b) => a[0] - b[0])
    .map(([day, items]) => ({ day, items }))
})

function dayDate(day: number): string {
  if (!props.tripStart) return ''
  const start = new Date(props.tripStart)
  if (Number.isNaN(start.getTime())) return ''
  const d = new Date(start.getFullYear(), start.getMonth(), start.getDate())
  d.setDate(d.getDate() + day - 1)
  return d.toLocaleDateString('zh-CN', { month: 'long', day: 'numeric', weekday: 'short' })
}

interface SideStats {
  total: number
  visitMin: number
  transitMin: number
  mealCount: number
}

function computeSide(acts: Activity[]): SideStats {
  const s = computeStatsFromActivities(acts)
  return {
    total: acts.length,
    visitMin: Math.round(Number(s.visitDurationMin ?? 0)),
    transitMin: Math.round(Number(s.transitDurationMin ?? 0)),
    mealCount: acts.filter((a) => a.activityType === 'meal').length,
  }
}

const leftStats = computed(() => computeSide(leftActs.value))
const rightStats = computed(() => computeSide(rightActs.value))

const summary = computed(() => {
  let added = 0
  let removed = 0
  let changed = 0
  let kept = 0
  for (const row of rows.value) {
    if (row.status === 'added') added++
    else if (row.status === 'removed') removed++
    else if (row.status === 'changed') changed++
    else kept++
  }
  return { added, removed, changed, kept }
})

function deltaText(l: number, r: number, unit: string): string {
  const d = r - l
  if (d === 0) return '持平'
  return `${d > 0 ? '+' : ''}${d}${unit}`
}

function deltaClass(l: number, r: number): string {
  if (r === l) return 'text-surface-500 dark:text-surface-400'
  return r > l ? 'text-emerald-600 dark:text-emerald-400' : 'text-rose-600 dark:text-rose-400'
}

function timeRange(a?: Activity): string {
  if (!a) return ''
  const s = formatTimePoint(a.scheduledStart)
  const e = formatTimePoint(a.scheduledEnd)
  if (s && e) return `${s} – ${e}`
  return s || e
}

const STATUS_META: Record<DiffRow['status'], { label: string; cls: string }> = {
  kept: { label: '保留', cls: 'bg-surface-100 text-surface-600 dark:bg-surface-700 dark:text-surface-300' },
  changed: { label: '调整', cls: 'bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300' },
  added: { label: '新增', cls: 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-300' },
  removed: { label: '移除', cls: 'bg-rose-100 text-rose-700 dark:bg-rose-900/40 dark:text-rose-300' },
}
</script>

<template>
  <div class="flex h-full w-full flex-col overflow-hidden">
    <!-- 顶栏：版本选择 + 换位 -->
    <div class="border-b border-surface-200 bg-white px-3 py-2.5 dark:border-surface-700 dark:bg-surface-800">
      <div class="flex items-center gap-2">
        <select
          :value="props.left"
          class="min-w-0 flex-1 rounded-lg border border-surface-200 bg-white px-3 py-2 text-sm font-medium text-surface-700 outline-none focus:border-brand-400 focus:ring-2 focus:ring-brand-500/20 dark:border-surface-600 dark:bg-surface-700 dark:text-surface-300"
          @change="emit('update:left', ($event.target as HTMLSelectElement).value)"
        >
          <option v-for="v in props.versions" :key="v.id" :value="v.id">
            {{ versionLabel(v) }}
          </option>
        </select>

        <button
          class="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-surface-100 text-surface-500 transition-colors hover:bg-surface-200 hover:text-surface-700 dark:bg-surface-700 dark:text-surface-300 dark:hover:bg-surface-600"
          title="交换左右版本"
          @click="swapSides"
        >
          <svg class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
            <path stroke-linecap="round" stroke-linejoin="round" d="M8 7h12m0 0l-3-3m3 3l-3 3m-4 7H4m0 0l3-3m-3 3l3-3" />
          </svg>
        </button>

        <select
          :value="props.right"
          class="min-w-0 flex-1 rounded-lg border border-surface-200 bg-white px-3 py-2 text-sm font-medium text-surface-700 outline-none focus:border-brand-400 focus:ring-2 focus:ring-brand-500/20 dark:border-surface-600 dark:bg-surface-700 dark:text-surface-300"
          @change="emit('update:right', ($event.target as HTMLSelectElement).value)"
        >
          <option v-for="v in props.versions" :key="v.id" :value="v.id">
            {{ versionLabel(v) }}
          </option>
        </select>
      </div>

      <!-- 统计对比 -->
      <div class="mt-2 grid grid-cols-2 gap-2 text-xs">
        <div class="rounded-lg bg-surface-50 px-3 py-2 dark:bg-surface-700/60">
          <div class="mb-1 font-semibold text-surface-700 dark:text-surface-200">
            {{ versionLabel(leftVersion) }}
          </div>
          <div class="flex flex-wrap gap-x-3 gap-y-0.5 text-surface-500 dark:text-surface-400">
            <span>活动 <b class="text-surface-700 dark:text-surface-200">{{ leftStats.total }}</b></span>
            <span>游览 <b class="text-surface-700 dark:text-surface-200">{{ leftStats.visitMin }}</b>分</span>
            <span>交通 <b class="text-surface-700 dark:text-surface-200">{{ leftStats.transitMin }}</b>分</span>
            <span>餐次 <b class="text-surface-700 dark:text-surface-200">{{ leftStats.mealCount }}</b></span>
          </div>
        </div>
        <div class="rounded-lg bg-brand-50 px-3 py-2 dark:bg-brand-900/20">
          <div class="mb-1 font-semibold text-brand-700 dark:text-brand-300">
            {{ versionLabel(rightVersion) }}
          </div>
          <div class="flex flex-wrap gap-x-3 gap-y-0.5 text-surface-500 dark:text-surface-400">
            <span>活动 <b :class="deltaClass(leftStats.total, rightStats.total)">{{ rightStats.total }}</b>
              <em class="not-italic text-[10px]" :class="deltaClass(leftStats.total, rightStats.total)">({{ deltaText(leftStats.total, rightStats.total, '') }})</em>
            </span>
            <span>游览 <b :class="deltaClass(leftStats.visitMin, rightStats.visitMin)">{{ rightStats.visitMin }}</b>分
              <em class="not-italic text-[10px]" :class="deltaClass(leftStats.visitMin, rightStats.visitMin)">({{ deltaText(leftStats.visitMin, rightStats.visitMin, '分') }})</em>
            </span>
            <span>交通 <b :class="deltaClass(leftStats.transitMin, rightStats.transitMin)">{{ rightStats.transitMin }}</b>分
              <em class="not-italic text-[10px]" :class="deltaClass(leftStats.transitMin, rightStats.transitMin)">({{ deltaText(leftStats.transitMin, rightStats.transitMin, '分') }})</em>
            </span>
            <span>餐次 <b :class="deltaClass(leftStats.mealCount, rightStats.mealCount)">{{ rightStats.mealCount }}</b></span>
          </div>
        </div>
      </div>

      <!-- 差异摘要 -->
      <div class="mt-2 flex flex-wrap items-center gap-1.5 text-xs">
        <span class="rounded-full bg-emerald-100 px-2 py-0.5 font-medium text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-300">
          新增 {{ summary.added }}
        </span>
        <span class="rounded-full bg-rose-100 px-2 py-0.5 font-medium text-rose-700 dark:bg-rose-900/40 dark:text-rose-300">
          移除 {{ summary.removed }}
        </span>
        <span class="rounded-full bg-amber-100 px-2 py-0.5 font-medium text-amber-700 dark:bg-amber-900/40 dark:text-amber-300">
          调整 {{ summary.changed }}
        </span>
        <span class="rounded-full bg-surface-100 px-2 py-0.5 font-medium text-surface-600 dark:bg-surface-700 dark:text-surface-300">
          保留 {{ summary.kept }}
        </span>
      </div>
    </div>

    <!-- 对齐 diff 列表 -->
    <div class="scrollbar-thin flex-1 overflow-y-auto p-3">
      <div v-if="props.versions.length < 2" class="flex h-full flex-col items-center justify-center text-surface-400">
        <svg class="mb-3 h-12 w-12" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.5">
          <path stroke-linecap="round" stroke-linejoin="round" d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2" />
        </svg>
        <p class="text-sm">至少需要两个版本才能对比</p>
      </div>

      <template v-else-if="rows.length > 0">
        <div v-for="group in dayGroups" :key="group.day" class="mb-4">
          <div class="mb-2 flex items-center gap-2">
            <span class="rounded-lg bg-surface-900 px-2.5 py-1 text-xs font-bold text-white dark:bg-white dark:text-surface-900">
              第 {{ group.day }} 天
            </span>
            <span v-if="dayDate(group.day)" class="text-xs text-surface-500 dark:text-surface-400">
              {{ dayDate(group.day) }}
            </span>
            <span class="h-px flex-1 bg-surface-200 dark:bg-surface-700" />
          </div>

          <div
            v-for="row in group.items"
            :key="`${row.key}-${row.day}`"
            class="mb-1.5 flex items-stretch gap-1.5"
          >
            <!-- 左（旧） -->
            <div
              v-if="row.left"
              class="min-w-0 flex-1 rounded-lg border px-3 py-2"
              :class="row.status === 'removed'
                ? 'border-rose-200 bg-rose-50/70 dark:border-rose-800 dark:bg-rose-900/20'
                : 'border-surface-100 bg-white dark:border-surface-700 dark:bg-surface-800'"
            >
              <div class="flex items-center gap-2">
                <span
                  class="flex h-5 w-5 shrink-0 items-center justify-center rounded-full text-[10px] font-bold"
                  :class="row.status === 'removed'
                    ? 'bg-rose-200 text-rose-700 dark:bg-rose-800 dark:text-rose-200'
                    : 'bg-surface-200 text-surface-600 dark:bg-surface-700 dark:text-surface-300'"
                >{{ row.left.seq }}</span>
                <span
                  class="truncate text-sm font-semibold text-surface-900 dark:text-white"
                  :class="{ 'line-through opacity-70': row.status === 'removed' }"
                  :title="row.left.poiName"
                >{{ row.left.poiName }}</span>
              </div>
              <div class="mt-1 flex flex-wrap gap-x-2 text-[11px] text-surface-500 dark:text-surface-400">
                <span v-if="timeRange(row.left)" class="tabular-nums">{{ timeRange(row.left) }}</span>
                <span>{{ TYPE_LABELS[row.left.activityType] || row.left.activityType }}</span>
                <span>{{ row.left.durationMin }}分钟</span>
              </div>
            </div>
            <div
              v-else
              class="flex flex-1 items-center justify-center rounded-lg border border-dashed border-surface-200 text-xs text-surface-400 dark:border-surface-700"
            >
              — 此版本无 —
            </div>

            <!-- 状态徽标 + 变化点 -->
            <div class="flex w-12 shrink-0 flex-col items-center justify-center gap-0.5">
              <span
                class="rounded px-1.5 py-0.5 text-[10px] font-bold"
                :class="STATUS_META[row.status].cls"
              >{{ STATUS_META[row.status].label }}</span>
              <span
                v-if="row.changes.length"
                class="text-center text-[9px] leading-tight text-surface-400 dark:text-surface-500"
                :title="row.changes.join('、')"
              >{{ row.changes.join(' ') }}</span>
            </div>

            <!-- 右（新） -->
            <div
              v-if="row.right"
              class="min-w-0 flex-1 rounded-lg border px-3 py-2"
              :class="row.status === 'added'
                ? 'border-emerald-200 bg-emerald-50/70 dark:border-emerald-800 dark:bg-emerald-900/20'
                : 'border-surface-100 bg-white dark:border-surface-700 dark:bg-surface-800'"
            >
              <div class="flex items-center gap-2">
                <span
                  class="flex h-5 w-5 shrink-0 items-center justify-center rounded-full text-[10px] font-bold"
                  :class="row.status === 'added'
                    ? 'bg-emerald-200 text-emerald-700 dark:bg-emerald-800 dark:text-emerald-200'
                    : 'bg-brand-100 text-brand-700 dark:bg-brand-900/50 dark:text-brand-300'"
                >{{ row.right.seq }}</span>
                <span class="truncate text-sm font-semibold text-surface-900 dark:text-white" :title="row.right.poiName">
                  {{ row.right.poiName }}
                </span>
              </div>
              <div class="mt-1 flex flex-wrap gap-x-2 text-[11px] text-surface-500 dark:text-surface-400">
                <span v-if="timeRange(row.right)" class="tabular-nums">{{ timeRange(row.right) }}</span>
                <span>{{ TYPE_LABELS[row.right.activityType] || row.right.activityType }}</span>
                <span>{{ row.right.durationMin }}分钟</span>
              </div>
            </div>
            <div
              v-else
              class="flex flex-1 items-center justify-center rounded-lg border border-dashed border-surface-200 text-xs text-surface-400 dark:border-surface-700"
            >
              — 此版本无 —
            </div>
          </div>
        </div>
      </template>

      <div v-else class="flex h-full flex-col items-center justify-center text-surface-400">
        <p class="text-sm">所选版本暂无行程数据</p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.scrollbar-thin::-webkit-scrollbar {
  width: 4px;
}
.scrollbar-thin::-webkit-scrollbar-track {
  background: transparent;
}
.scrollbar-thin::-webkit-scrollbar-thumb {
  background: rgb(209 213 219 / 0.6);
  border-radius: 9999px;
}
</style>
