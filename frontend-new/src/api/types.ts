// ==================== API 类型定义 ====================

// 通用响应
export interface ApiResponse<T = unknown> {
  success: boolean
  data: T
  error?: { code: string; message: string; details?: Record<string, unknown> }
  meta?: { page: number; size: number; total: number }
  requestId: string
}

// ==================== Auth ====================
export interface LoginRequest {
  email: string
  password: string
  deviceId?: string
  rememberMe?: boolean
}

export interface LoginResponse {
  accessToken: string
  refreshToken: string
  tokenType: string
  expiresIn: number
  user: UserProfile
}

export interface RegisterRequest {
  email: string
  password: string
  confirmPassword: string
  name?: string
}

export interface UserProfile {
  id: string
  email: string
  name: string
  avatarUrl?: string
  status: string
  lastLoginAt?: string
  createdAt: string
}

export interface UpdateProfileRequest {
  name?: string
  avatarUrl?: string
}

export interface UserPreference {
  userId: string
  visitDurationDefaults?: Record<string, number>
  mealDurations?: Record<string, number>
  activeWindow?: { start: string; end: string }
  blockedPeriods?: Array<{ start: string; end: string }>
  intensity?: 'relaxed' | 'standard' | 'packed'
  dietaryTags?: string[]
  accessibility?: boolean
  preferredTransportModes?: string[]
  updatedAt?: string
}

export interface UpdatePreferenceRequest {
  visitDurationDefaults?: Record<string, number>
  mealDurations?: Record<string, number>
  activeWindow?: { start: string; end: string }
  blockedPeriods?: Array<{ start: string; end: string }>
  intensity?: string
  dietaryTags?: string[]
  accessibility?: boolean
  preferredTransportModes?: string[]
}

// ==================== Trip ====================
export interface Trip {
  id: string
  userId: string
  title: string
  rawInput: string
  parsedInput?: ParsedInput
  timeStart: string
  timeEnd: string
  transportMode: string
  /** 活动频率：compact 紧凑 / moderate 适中 / relaxed 宽松 */
  pace: string
  preferences?: Record<string, unknown>
  status: 'draft' | 'planning' | 'planned' | 'completed' | 'failed' | 'archived'
  currentVersionId?: string
  isPublic: boolean
  shareToken?: string
  viewCount: number
  createdAt: string
  updatedAt: string
  /** 列表页轻量字段 */
  city?: string | null
  activityCount?: number
  landmarks?: string[]
  /** 作者显示名（公开卡片用，服务端跨服务解析，可能为空） */
  authorName?: string
  latestVersion?: TripVersion
}

export interface ParsedInput {
  places?: Array<{
    name: string
    priority: string
    type: string
    meal?: string
    preferredDurationMin?: number
    notes?: string
    /** 解析到的原文片段（v1.15.0） */
    quote?: string
  }>
  meals?: Array<{ type: string; preference: string; durationMin: number; quote?: string }>
  timeRange?: { start: string; end: string; timezone: string }
  transportMode?: string
  /** 目标城市（表单优先 > LLM 识别 > 正则；v1.15.0） */
  city?: string | null
  /** AI 解析摘要（v1.15.0） */
  summary?: string | null
  /** 城市来源：form / llm / regex / null */
  citySource?: string | null
}

export interface TripListResponse {
  items: Trip[]
  total: number
  page: number
  size: number
  totalPages: number
}

/** 行程封面图搜索结果（后端联网检索） */
export interface CoverResponse {
  keyword: string
  url: string
  title?: string
  width?: number
  height?: number
  source?: string
}

export interface CreateTripRequest {
  title: string
  rawInput: string
  timeStart: string
  timeEnd: string
  transportMode?: string
  /** 活动频率：compact 紧凑 / moderate 适中 / relaxed 宽松 */
  pace?: string
  preferences?: Record<string, unknown>
  /** 用户可选目的城市（v1.15.0，缺省时按 LLM/正则识别） */
  city?: string
  places?: Array<{
    name: string
    priority: string
    type: string
    meal?: string
    preferredDurationMin?: number
    notes?: string
  }>
}

export interface UpdateTripRequest {
  title?: string
  timeStart?: string
  timeEnd?: string
  transportMode?: string
  pace?: string
  status?: string
  preferences?: Record<string, unknown>
  isPublic?: boolean
}

// ==================== TripVersion ====================
export interface TripVersion {
  id: string
  tripId: string
  versionNum: number
  parentVersionId?: string
  activities: Activity[]
  routes?: RouteInfo[]
  conflicts?: unknown[]
  stats?: VersionStats
  feedback?: string
  solverMeta?: SolverMeta
  status: string
  createdAt: string
}

export interface Activity {
  id?: string
  seq: number
  day?: number
  poiName: string
  activityType: string
  priority: string
  scheduledStart?: string
  scheduledEnd?: string
  durationMin: number
  travelDurationMin?: number
  travelDistanceKm?: number
  travelDistanceMeters?: number
  status: string
  transportMode?: string
  notes?: string
  slogan?: string
  poiCategory?: string
  poiAddress?: string
  /** 地图坐标（后端有则返回，休息节点 rest 无坐标） */
  lat?: number
  lng?: number
  /** 餐厅/POI 评分与人均（v1.15.0） */
  rating?: number
  cost?: string
  [key: string]: unknown
}

export interface RouteInfo {
  from: string
  to: string
  mode: string
  distance?: number
  duration?: number
  /** 后端兼容字段 */
  distance_km?: number
  duration_min?: number
  polyline?: string
}

export interface VersionStats {
  totalDurationMin?: number
  transitDurationMin?: number
  visitDurationMin?: number
  mealDurationMin?: number
  restDurationMin?: number
  bufferDurationMin?: number
  placeCount?: Record<string, number>
  estimatedCost?: number
}

export interface SolverMeta {
  solveTimeMs?: number
  iterations?: number
  objectiveValue?: number
  solverStatus?: string
}

// ==================== Planning ====================
export interface PlanTaskProgress {
  taskId: string
  tripId: string
  versionId?: string
  taskType: string
  status: 'pending' | 'running' | 'completed' | 'failed'
  progress: number
  stage?: string
  message?: string
  solverStats?: Record<string, unknown>
  errorMessage?: string
  resultVersionId?: string
  startedAt?: string
  completedAt?: string
  createdAt: string
}

export interface ParsePreviewRequest {
  rawInput: string
  timeStart: string
  timeEnd: string
  transportMode?: string
  preferences?: Record<string, unknown>
}

export interface ParsePreviewResponse {
  parsedInput: ParsedInput
  valid: boolean
  warning?: string
}

// ==================== Alternatives ====================
export interface ActivityAlternative {
  id: string
  name: string
  type: string
  reason: string
  durationMin?: number
  priority?: string
  lat?: number
  lng?: number
  address?: string
  rating?: number
  imageUrl?: string
  distanceFromOriginalMeters?: number
  travelTimeFromOriginalMin?: number
  source?: string
  tags?: string[]
}

export interface AlternativesResponse {
  tripId: string
  activityId: string
  originalName: string
  originalType: string
  alternatives: ActivityAlternative[]
  count: number
  responseTime: string
}

/** POI 关键词搜索结果项（换景点搜索框，POST /api/plan/poi-search，支持模糊匹配） */
export interface PoiSearchItem {
  name: string
  type: string
  lat: number
  lng: number
  address?: string
  rating?: number
  preferredDurationMin?: number
  source?: string
  /** 匹配方式：精确/前缀/包含/模糊匹配/近似匹配 */
  matchType?: string
  /** 模糊得分（0-100） */
  fuzzyScore?: number
}

// ==================== Share ====================
export interface ShareResponse {
  shareToken: string
  shareUrl: string
  isPublic: boolean
  expiresAt?: string
  viewCount: number
}

export interface ShareTripRequest {
  isPublic: boolean
  expireDays?: number
  password?: string
  allowExport?: boolean
}

// ==================== Export ====================
export interface ExportResponse {
  downloadUrl: string
  filename: string
  fileSize: number
  contentType: string
  expiresAt?: string
}

// ==================== Notification ====================
export interface Notification {
  id: string
  type: 'PROGRESS' | 'COMPLETED' | 'FAILED' | 'REPLAN' | 'SYSTEM' | 'SHARE'
  title: string
  content: string
  data?: Record<string, unknown>
  read: boolean
  priority: 'low' | 'normal' | 'high' | 'urgent'
  createdAt: string
}

// ==================== WebSocket ====================
export interface ProgressMessage {
  type: 'PROGRESS' | 'COMPLETED' | 'FAILED' | 'REPLAN_COMPLETED' | 'REPLAN_FAILED'
  taskId: string
  tripId: string
  versionId?: string
  percent: number
  stage?: string
  message?: string
  resultVersionId?: string
  error?: string
  timestamp: string
}
