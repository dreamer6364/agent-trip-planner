import axios, { type AxiosInstance, type InternalAxiosRequestConfig } from 'axios'
import type { ApiResponse } from './types'

const API_BASE = import.meta.env.VITE_API_BASE || ''

const instance: AxiosInstance = axios.create({
  baseURL: API_BASE,
  timeout: 30000,
  headers: { 'Content-Type': 'application/json' },
})

// 请求拦截器 - 注入 Token
instance.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = localStorage.getItem('tf_token')
  if (token && config.headers) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

/**
 * 统一 API 错误对象
 *
 * 所有失败响应（含网络/超时）都归一化为 ApiError，
 * 携带后端 message / code / details（字段级校验错误）。
 */
export class ApiError extends Error {
  code?: string
  status?: number
  details?: Record<string, string>

  constructor(message: string, code?: string, status?: number, details?: Record<string, string>) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
    this.details = details
  }
}

function defaultStatusMessage(status?: number): string {
  switch (status) {
    case 400: return '请求参数错误'
    case 401: return '未登录或登录已过期'
    case 403: return '没有权限执行该操作'
    case 404: return '请求的资源不存在'
    case 409: return '操作冲突，请刷新后重试'
    case 429: return '请求过于频繁，请稍后再试'
    case 500: return '服务器内部错误，请稍后重试'
    case 502: return '服务暂时不可用，请稍后重试'
    case 503: return '服务暂时不可用，请稍后重试'
    default: return status ? `请求失败 (${status})` : '请求失败，请重试'
  }
}

/** 将 axios 错误归一化为 ApiError */
export function toApiError(error: unknown): ApiError {
  if (error instanceof ApiError) return error

  const err = error as { response?: { status?: number; data?: { error?: { code?: string; message?: string; details?: Record<string, string> } } }; code?: string; message?: string }
  const res = err?.response
  const backendError = res?.data?.error

  if (backendError?.message || backendError?.details) {
    return new ApiError(
      backendError.message || defaultStatusMessage(res?.status),
      backendError.code,
      res?.status,
      backendError.details
    )
  }

  if (res) {
    return new ApiError(defaultStatusMessage(res.status), undefined, res.status)
  }

  if (err?.code === 'ECONNABORTED') {
    return new ApiError('请求超时，请重试', 'TIMEOUT')
  }

  return new ApiError('网络异常，请检查网络连接后重试', 'NETWORK_ERROR')
}

/** 响应拦截器 - 统一错误处理 + Token 刷新 */
let isRefreshing = false
let failedQueue: Array<{ resolve: (value: unknown) => void; reject: (reason?: unknown) => void }> = []

function processQueue(error: unknown) {
  if (error) {
    const normalized = toApiError(error)
    failedQueue.forEach(({ reject }) => reject(normalized))
  } else {
    // 刷新成功：放行排队请求，由各自 then 分支重试原请求
    failedQueue.forEach(({ resolve }) => resolve(null))
  }
  failedQueue = []
}

// 认证类请求的 401 是业务失败（密码错误/邮箱已注册），不能触发 Token 刷新与强制跳转
const AUTH_URL_RE = /\/api\/auth\/(login|register|refresh|logout)/

instance.interceptors.response.use(
  (response) => response,
  async (error) => {
    const originalRequest = error.config as ({ url?: string; _retry?: boolean; headers?: Record<string, string> } | undefined)
    const status: number | undefined = error.response?.status
    const requestUrl: string = originalRequest?.url ?? ''
    const isAuthCall = AUTH_URL_RE.test(requestUrl)

    if (status === 401 && !isAuthCall && originalRequest && !originalRequest._retry) {
      if (isRefreshing) {
        return new Promise((resolve, reject) => {
          failedQueue.push({ resolve, reject })
        }).then(() => instance(originalRequest))
      }

      originalRequest._retry = true
      isRefreshing = true

      const refreshToken = localStorage.getItem('tf_refresh')
      if (!refreshToken) {
        isRefreshing = false
        localStorage.removeItem('tf_token')
        localStorage.removeItem('tf_refresh')
        window.location.href = '/login'
        return Promise.reject(toApiError(error))
      }

      try {
        const { data } = await axios.post(`${API_BASE}/api/auth/refresh`, {
          refreshToken,
        })
        const { accessToken, refreshToken: newRefresh } = data.data
        localStorage.setItem('tf_token', accessToken)
        localStorage.setItem('tf_refresh', newRefresh)
        if (originalRequest.headers) {
          originalRequest.headers.Authorization = `Bearer ${accessToken}`
        }
        processQueue(null)
        return instance(originalRequest)
      } catch (refreshError) {
        processQueue(refreshError)
        localStorage.removeItem('tf_token')
        localStorage.removeItem('tf_refresh')
        window.location.href = '/login'
        return Promise.reject(toApiError(refreshError))
      } finally {
        isRefreshing = false
      }
    }

    return Promise.reject(toApiError(error))
  }
)

// 通用 API 调用
export async function apiGet<T>(url: string, params?: Record<string, unknown>): Promise<T> {
  const response = await instance.get<ApiResponse<T>>(url, { params })
  return response.data.data
}

export async function apiPost<T>(url: string, data?: unknown, config?: { params?: Record<string, unknown>; headers?: Record<string, string>; timeout?: number }): Promise<T> {
  const response = await instance.post<ApiResponse<T>>(url, data, config)
  return response.data.data
}

export async function apiPut<T>(url: string, data?: unknown): Promise<T> {
  const response = await instance.put<ApiResponse<T>>(url, data)
  return response.data.data
}

export async function apiPatch<T>(url: string, data?: unknown): Promise<T> {
  const response = await instance.patch<ApiResponse<T>>(url, data)
  return response.data.data
}

export async function apiDelete<T>(url: string): Promise<T> {
  const response = await instance.delete<ApiResponse<T>>(url)
  return response.data.data
}

export default instance
