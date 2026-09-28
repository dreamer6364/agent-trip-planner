/**
 * 地图统一主题：活动类型 / 出行方式 颜色、图标、中文标签
 * key 对齐后端 activity_type (visit/meal/shopping/transit) 与 transport_mode (walk/drive/bike/transit/mixed)
 */

export interface TypeTheme {
  fill: string
  stroke: string
  text: string
  icon: string
  label: string
}

export const TYPE_THEMES: Record<string, TypeTheme> = {
  visit: {
    fill: '#3b6cf7',
    stroke: '#2550eb',
    text: '#fff',
    icon: 'ri-landscape-line',
    label: '景点',
  },
  meal: {
    fill: '#f97316',
    stroke: '#ea580c',
    text: '#fff',
    icon: 'ri-restaurant-line',
    label: '餐饮',
  },
  shopping: {
    fill: '#ec4899',
    stroke: '#db2777',
    text: '#fff',
    icon: 'ri-shopping-bag-line',
    label: '购物',
  },
  transit: {
    fill: '#3b82f6',
    stroke: '#2563eb',
    text: '#fff',
    icon: 'ri-bus-line',
    label: '交通',
  },
  hotel: {
    fill: '#a855f7',
    stroke: '#9333ea',
    text: '#fff',
    icon: 'ri-hotel-line',
    label: '酒店',
  },
  entertainment: {
    fill: '#22c55e',
    stroke: '#16a34a',
    text: '#fff',
    icon: 'ri-gamepad-line',
    label: '娱乐',
  },
  rest: {
    fill: '#14b8a6',
    stroke: '#0d9488',
    text: '#fff',
    icon: 'ri-cup-line',
    label: '休息',
  },
  default: {
    fill: '#6b778c',
    stroke: '#505f79',
    text: '#fff',
    icon: 'ri-map-pin-line',
    label: '地点',
  },
}

/** 历史/别名类型 → 标准 key */
const TYPE_ALIASES: Record<string, string> = {
  attraction: 'visit',
  restaurant: 'meal',
  place: 'visit',
  poi: 'visit',
  transport: 'transit',
  dining: 'meal',
  food: 'meal',
}

export function resolveTypeKey(raw?: string | null): string {
  const k = String(raw || '').toLowerCase().trim()
  if (TYPE_THEMES[k]) return k
  if (TYPE_ALIASES[k]) return TYPE_ALIASES[k]
  return 'default'
}

export function getTypeTheme(raw?: string | null): TypeTheme {
  return TYPE_THEMES[resolveTypeKey(raw)] || TYPE_THEMES.default
}

/** 图例展示顺序（不含 default） */
export const TYPE_LEGEND_ORDER = ['visit', 'meal', 'shopping', 'transit', 'rest', 'hotel', 'entertainment'] as const

export interface ModeTheme {
  color: string
  icon: string
  label: string
}

export const MODE_THEMES: Record<string, ModeTheme> = {
  walk: { color: '#22c55e', icon: 'ri-walk-line', label: '步行' },
  transit: { color: '#3b82f6', icon: 'ri-bus-line', label: '公交' },
  drive: { color: '#f97316', icon: 'ri-car-line', label: '驾车' },
  bike: { color: '#a855f7', icon: 'ri-bike-line', label: '骑行' },
  mixed: { color: '#6b778c', icon: 'ri-route-line', label: '混合' },
  default: { color: '#6b778c', icon: 'ri-route-line', label: '路线' },
}

const MODE_ALIASES: Record<string, string> = {
  walking: 'walk',
  onfoot: 'walk',
  foot: 'walk',
  driving: 'drive',
  car: 'drive',
  taxi: 'drive',
  bicycle: 'bike',
  cycling: 'bike',
  subway: 'transit',
  metro: 'transit',
  bus: 'transit',
  public: 'transit',
}

export function resolveModeKey(raw?: string | null): string {
  const k = String(raw || '').toLowerCase().trim()
  if (MODE_THEMES[k]) return k
  if (MODE_ALIASES[k]) return MODE_ALIASES[k]
  if (!k) return 'mixed'
  return 'mixed'
}

export function getModeTheme(raw?: string | null): ModeTheme {
  return MODE_THEMES[resolveModeKey(raw)] || MODE_THEMES.default
}

export const MODE_LEGEND_ORDER = ['walk', 'transit', 'drive', 'bike'] as const

/** 优先级中文 */
export function priorityLabel(p?: string | null): string {
  const k = String(p || '').toLowerCase()
  if (k === 'must') return '必去'
  if (k === 'optional') return '可选'
  if (k === 'recommended' || k === 'recommend') return '推荐'
  return p || '推荐'
}

/** 状态中文 */
export function statusLabel(s?: string | null): string {
  const k = String(s || '').toLowerCase()
  if (k === 'scheduled') return '已安排'
  if (k === 'completed') return '已完成'
  if (k === 'skipped') return '已跳过'
  if (k === 'cancelled') return '已取消'
  if (k === 'pending') return '待定'
  return s || '已安排'
}
