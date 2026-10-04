import { API_BASE, ApiError, apiGet, apiPost, apiPut, toApiError } from './index'
import type {
  LoginRequest,
  LoginResponse,
  RegisterRequest,
  UserProfile,
  UpdateProfileRequest,
} from './types'

/**
 * 头像上传：原生 fetch + FormData。
 * axios 浏览器默认 xhr 适配器对 FormData 的 Content-Type 处理不可靠
 * （实例默认 application/json 会把 FormData 序列化掉），fetch 由浏览器
 * 自动生成 multipart boundary，故此处绕开 axios。
 */
async function fetchWithAuth(path: string, init: RequestInit): Promise<Response> {
  const token = localStorage.getItem('tf_token') || ''
  return fetch(`${API_BASE}${path}`, {
    ...init,
    headers: { ...(init.headers as Record<string, string> | undefined), Authorization: `Bearer ${token}` },
  })
}

/** access token 过期时用 refresh token 换新（与 axios 拦截器逻辑一致），成功返回 true */
async function tryRefreshAccessToken(): Promise<boolean> {
  const refreshToken = localStorage.getItem('tf_refresh') || ''
  if (!refreshToken) return false
  try {
    const res = await fetch(`${API_BASE}/api/auth/refresh`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken }),
    })
    const json = await res.json().catch(() => null)
    if (!res.ok || !json?.success) return false
    localStorage.setItem('tf_token', json.data.accessToken)
    localStorage.setItem('tf_refresh', json.data.refreshToken)
    return true
  } catch {
    return false
  }
}

export const authApi = {
  login(data: LoginRequest) {
    return apiPost<LoginResponse>('/api/auth/login', data)
  },

  register(data: RegisterRequest) {
    return apiPost<LoginResponse>('/api/auth/register', data)
  },

  refresh(refreshToken: string) {
    return apiPost<LoginResponse>('/api/auth/refresh', { refreshToken })
  },

  logout() {
    const refreshToken = localStorage.getItem('tf_refresh') || ''
    return apiPost<null>('/api/auth/logout', undefined, {
      headers: {
        'X-Refresh-Token': refreshToken,
      },
    })
  },

  getProfile() {
    return apiGet<UserProfile>('/api/auth/me')
  },

  updateProfile(data: UpdateProfileRequest) {
    return apiPut<UserProfile>('/api/auth/me', data)
  },

  updatePassword(data: { currentPassword: string; newPassword: string; confirmNewPassword: string }) {
    return apiPut<null>('/api/auth/me/password', data)
  },

  /** 上传头像（multipart，≤5MB，服务端魔数校验），成功返回更新后的完整档案 */
  async uploadAvatar(file: File): Promise<UserProfile> {
    const form = new FormData()
    form.append('file', file)
    const doSend = () => fetchWithAuth('/api/auth/me/avatar', { method: 'POST', body: form })

    let res = await doSend()
    if (res.status === 401 && (await tryRefreshAccessToken())) {
      res = await doSend()
    }
    const json = await res.json().catch(() => null)
    if (!res.ok || !json?.success) {
      throw toApiError({ response: { status: res.status, data: json } })
    }
    return json.data as UserProfile
  },
}
