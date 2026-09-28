import { apiGet, apiPost, apiPut } from './index'
import type {
  LoginRequest,
  LoginResponse,
  RegisterRequest,
  UserProfile,
  UpdateProfileRequest,
} from './types'

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
}
