<script setup lang="ts">
import { ref, computed, watch, reactive } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '@/stores/auth'
import { useToast } from '@/composables/useToast'
import { ApiError } from '@/api/index'

const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const authStore = useAuthStore()
const toast = useToast()

// 与后端 RegisterRequest 密码规则保持一致
const PASSWORD_PATTERN = /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)(?=.*[@$!%*?&])[A-Za-z\d@$!%*?&]{8,}$/

const activeTab = computed(() => {
  if (route.path === '/register') return 'register'
  return 'login'
})

function switchTab(tab: string) {
  router.push(tab === 'login' ? '/login' : '/register')
}



const loginForm = reactive({
  email: '',
  password: '',
  rememberMe: false,
})

const registerForm = reactive({
  name: '',
  email: '',
  password: '',
  confirmPassword: '',
})

const loginErrors = reactive({
  email: '',
  password: '',
})

const registerErrors = reactive({
  name: '',
  email: '',
  password: '',
  confirmPassword: '',
})

const loginLoading = ref(false)
const registerLoading = ref(false)

// 表单级提交错误（后端返回的业务/校验错误）
const loginSubmitError = ref('')
const registerSubmitError = ref('')

function validateEmail(email: string): boolean {
  const re = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
  return re.test(email)
}

function clearLoginErrors() {
  loginErrors.email = ''
  loginErrors.password = ''
  loginSubmitError.value = ''
}

function clearRegisterErrors() {
  registerErrors.name = ''
  registerErrors.email = ''
  registerErrors.password = ''
  registerErrors.confirmPassword = ''
  registerSubmitError.value = ''
}

function validateLoginForm(): boolean {
  clearLoginErrors()
  let valid = true

  if (!loginForm.email.trim()) {
    loginErrors.email = t('auth.required', { field: t('auth.email') })
    valid = false
  } else if (!validateEmail(loginForm.email)) {
    loginErrors.email = t('auth.invalidEmail')
    valid = false
  }

  if (!loginForm.password) {
    loginErrors.password = t('auth.required', { field: t('auth.password') })
    valid = false
  }

  return valid
}

function validateRegisterForm(): boolean {
  clearRegisterErrors()
  let valid = true

  if (!registerForm.name.trim()) {
    registerErrors.name = t('auth.required', { field: t('auth.name') })
    valid = false
  } else if (registerForm.name.length < 2) {
    registerErrors.name = t('auth.nameTooShort')
    valid = false
  }

  if (!registerForm.email.trim()) {
    registerErrors.email = t('auth.required', { field: t('auth.email') })
    valid = false
  } else if (!validateEmail(registerForm.email)) {
    registerErrors.email = t('auth.invalidEmail')
    valid = false
  }

  if (!registerForm.password) {
    registerErrors.password = t('auth.required', { field: t('auth.password') })
    valid = false
  } else if (!PASSWORD_PATTERN.test(registerForm.password)) {
    registerErrors.password = t('auth.passwordHint')
    valid = false
  }

  if (!registerForm.confirmPassword) {
    registerErrors.confirmPassword = t('auth.pleaseConfirmPassword')
    valid = false
  } else if (registerForm.password !== registerForm.confirmPassword) {
    registerErrors.confirmPassword = t('auth.passwordMismatch')
    valid = false
  }

  return valid
}

/** 将后端字段级校验错误 (error.details) 回填到对应输入框 */
function applyFieldDetails(details: Record<string, string> | undefined, scope: 'login' | 'register') {
  if (!details) return
  if (scope === 'login') {
    if (details.email) loginErrors.email = details.email
    if (details.password) loginErrors.password = details.password
    return
  }
  if (details.name) registerErrors.name = details.name
  if (details.email) registerErrors.email = details.email
  if (details.password) registerErrors.password = details.password
  if (details.confirmPassword) registerErrors.confirmPassword = details.confirmPassword
}

/** 登录/注册失败统一处理：字段级错误 + 表单横幅 + Toast */
function handleAuthError(err: unknown, scope: 'login' | 'register') {
  const apiErr = err instanceof ApiError ? err : null
  const fallback = scope === 'login' ? t('auth.loginFailed') : t('auth.registerFailed')
  const message = apiErr?.message || (err instanceof Error && err.message ? err.message : fallback)

  applyFieldDetails(apiErr?.details, scope)

  if (scope === 'login') {
    loginSubmitError.value = message
  } else {
    registerSubmitError.value = message
  }
  toast.error(message)
}

async function handleLogin() {
  if (!validateLoginForm()) return

  loginLoading.value = true
  try {
    await authStore.login(loginForm.email, loginForm.password, loginForm.rememberMe)
    toast.success(t('auth.loginSuccess'))
    router.push('/dashboard')
  } catch (err: unknown) {
    handleAuthError(err, 'login')
  } finally {
    loginLoading.value = false
  }
}

async function handleRegister() {
  if (!validateRegisterForm()) return

  registerLoading.value = true
  try {
    await authStore.register(
      registerForm.name,
      registerForm.email,
      registerForm.password,
      registerForm.confirmPassword
    )
    toast.success(t('auth.registerSuccess'))
    router.push('/dashboard')
  } catch (err: unknown) {
    handleAuthError(err, 'register')
  } finally {
    registerLoading.value = false
  }
}

function handleGoogleLogin() {
  toast.info(t('auth.oauthComingSoon', { provider: 'Google' }))
}

function handleGithubLogin() {
  toast.info(t('auth.oauthComingSoon', { provider: 'GitHub' }))
}
</script>

<template>
  <div class="w-full max-w-md mx-auto">
    <!-- Tab Header -->
    <div class="mb-8">
      <div class="relative flex rounded-2xl bg-surface-100 dark:bg-surface-800 p-1">
        <!-- Sliding indicator -->
        <div
          :class="[
            'absolute top-1 bottom-1 rounded-xl bg-white dark:bg-surface-700 shadow-sm transition-all duration-300 ease-out',
            activeTab === 'login' ? 'left-1 w-[calc(50%-4px)]' : 'left-[calc(50%+3px)] w-[calc(50%-4px)]',
          ]"
        />

        <button
          :class="[
            'relative z-10 flex-1 py-2.5 text-sm font-semibold rounded-xl transition-colors duration-200',
            activeTab === 'login'
              ? 'text-brand-600 dark:text-brand-400'
              : 'text-surface-500 hover:text-surface-700 dark:text-surface-400 dark:hover:text-surface-300',
          ]"
          @click="switchTab('login')"
        >
          {{ t('auth.login') }}
        </button>
        <button
          :class="[
            'relative z-10 flex-1 py-2.5 text-sm font-semibold rounded-xl transition-colors duration-200',
            activeTab === 'register'
              ? 'text-brand-600 dark:text-brand-400'
              : 'text-surface-500 hover:text-surface-700 dark:text-surface-400 dark:hover:text-surface-300',
          ]"
          @click="switchTab('register')"
        >
          {{ t('auth.register') }}
        </button>
      </div>
    </div>

    <!-- Login Form -->
    <Transition
      enter-active-class="transition-all duration-300 ease-out"
      enter-from-class="opacity-0 translate-x-4"
      enter-to-class="opacity-100 translate-x-0"
      leave-active-class="transition-all duration-200 ease-in"
      leave-from-class="opacity-100 translate-x-0"
      leave-to-class="opacity-0 -translate-x-4"
    >
      <form
        v-if="activeTab === 'login'"
        key="login"
        class="space-y-5"
        @submit.prevent="handleLogin"
      >
        <!-- Email -->
        <div class="space-y-1.5">
          <label class="block text-sm font-medium text-surface-700 dark:text-surface-300">
            {{ t('auth.email') }}
          </label>
          <div class="relative">
            <div class="pointer-events-none absolute inset-y-0 left-0 flex items-center pl-3.5">
              <svg class="h-5 w-5 text-surface-400" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.5">
                <path stroke-linecap="round" stroke-linejoin="round" d="M21.75 6.75v10.5a2.25 2.25 0 01-2.25 2.25h-15a2.25 2.25 0 01-2.25-2.25V6.75m19.5 0A2.25 2.25 0 0019.5 4.5h-15a2.25 2.25 0 00-2.25 2.25m19.5 0v.243a2.25 2.25 0 01-1.07 1.916l-7.5 4.615a2.25 2.25 0 01-2.36 0L3.32 8.91a2.25 2.25 0 01-1.07-1.916V6.75" />
              </svg>
            </div>
            <input
              v-model="loginForm.email"
              type="email"
              placeholder="your@email.com"
              :class="[
                'input-base pl-11',
                loginErrors.email && 'border-danger-400 focus:ring-danger-500/30 focus:border-danger-500',
              ]"
              @input="loginErrors.email = ''; loginSubmitError = ''"
            />
          </div>
          <p v-if="loginErrors.email" class="flex items-center gap-1 text-xs text-danger-500">
            <svg class="h-3.5 w-3.5 shrink-0" fill="currentColor" viewBox="0 0 20 20">
              <path fill-rule="evenodd" d="M18 10a8 8 0 11-16 0 8 8 0 0116 0zm-7-4a1 1 0 11-2 0 1 1 0 012 0zM9 9a1 1 0 000 2v3a1 1 0 001 1h1a1 1 0 100-2v-3a1 1 0 00-1-1H9z" clip-rule="evenodd" />
            </svg>
            {{ loginErrors.email }}
          </p>
        </div>

        <!-- Password -->
        <div class="space-y-1.5">
          <div class="flex items-center justify-between">
            <label class="block text-sm font-medium text-surface-700 dark:text-surface-300">
              {{ t('auth.password') }}
            </label>
            <a href="#" class="text-xs font-medium text-brand-500 hover:text-brand-600 transition-colors">
              {{ t('auth.forgotPassword') }}
            </a>
          </div>
          <div class="relative">
            <div class="pointer-events-none absolute inset-y-0 left-0 flex items-center pl-3.5">
              <svg class="h-5 w-5 text-surface-400" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.5">
                <path stroke-linecap="round" stroke-linejoin="round" d="M16.5 10.5V6.75a4.5 4.5 0 10-9 0v3.75m-.75 11.25h10.5a2.25 2.25 0 002.25-2.25v-6.75a2.25 2.25 0 00-2.25-2.25H6.75a2.25 2.25 0 00-2.25 2.25v6.75a2.25 2.25 0 002.25 2.25z" />
              </svg>
            </div>
            <input
              v-model="loginForm.password"
              type="password"
              :placeholder="t('auth.password')"
              :class="[
                'input-base pl-11',
                loginErrors.password && 'border-danger-400 focus:ring-danger-500/30 focus:border-danger-500',
              ]"
              @input="loginErrors.password = ''; loginSubmitError = ''"
            />
          </div>
          <p v-if="loginErrors.password" class="flex items-center gap-1 text-xs text-danger-500">
            <svg class="h-3.5 w-3.5 shrink-0" fill="currentColor" viewBox="0 0 20 20">
              <path fill-rule="evenodd" d="M18 10a8 8 0 11-16 0 8 8 0 0116 0zm-7-4a1 1 0 11-2 0 1 1 0 012 0zM9 9a1 1 0 000 2v3a1 1 0 001 1h1a1 1 0 100-2v-3a1 1 0 00-1-1H9z" clip-rule="evenodd" />
            </svg>
            {{ loginErrors.password }}
          </p>
        </div>

        <!-- Remember Me -->
        <label class="flex items-center gap-2.5 cursor-pointer group">
          <div class="relative">
            <input
              v-model="loginForm.rememberMe"
              type="checkbox"
              class="peer sr-only"
            />
            <div class="h-5 w-5 rounded-lg border-2 border-surface-300 bg-white transition-all duration-200 peer-checked:border-brand-500 peer-checked:bg-brand-500 peer-focus:ring-2 peer-focus:ring-brand-500/30 group-hover:border-surface-400">
              <svg class="h-full w-full text-white opacity-0 peer-checked:opacity-100 transition-opacity" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="3">
                <path stroke-linecap="round" stroke-linejoin="round" d="M4.5 12.75l6 6 9-13.5" />
              </svg>
            </div>
          </div>
          <span class="text-sm text-surface-600 dark:text-surface-400 select-none">{{ t('auth.rememberMe') }}</span>
        </label>

        <!-- Submit Error -->
        <div
          v-if="loginSubmitError"
          class="flex items-start gap-2 rounded-xl border border-danger-200 bg-danger-50 px-3.5 py-3 text-sm leading-snug text-danger-600 dark:border-danger-500/30 dark:bg-danger-500/10 dark:text-danger-400"
        >
          <svg class="h-4 w-4 mt-0.5 shrink-0" fill="currentColor" viewBox="0 0 20 20">
            <path fill-rule="evenodd" d="M18 10a8 8 0 11-16 0 8 8 0 0116 0zm-7-4a1 1 0 11-2 0 1 1 0 012 0zM9 9a1 1 0 000 2v3a1 1 0 001 1h1a1 1 0 100-2v-3a1 1 0 00-1-1H9z" clip-rule="evenodd" />
          </svg>
          <span class="flex-1 min-w-0 break-words">{{ loginSubmitError }}</span>
        </div>

        <!-- Submit -->
        <button
          type="submit"
          :disabled="loginLoading"
          class="btn-primary w-full py-3.5 text-base"
        >
          <svg v-if="loginLoading" class="animate-spin -ml-1 h-5 w-5" fill="none" viewBox="0 0 24 24">
            <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4" />
            <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
          </svg>
          {{ loginLoading ? t('common.loading') : t('auth.login') }}
        </button>

        <!-- Divider -->
        <div class="relative my-6">
          <div class="absolute inset-0 flex items-center">
            <div class="w-full border-t border-surface-200 dark:border-surface-700" />
          </div>
          <div class="relative flex justify-center text-sm">
            <span class="bg-white dark:bg-surface-900 px-3 text-surface-400">{{ t('auth.orContinueWith') }}</span>
          </div>
        </div>

        <!-- OAuth Buttons -->
        <div class="grid grid-cols-2 gap-3">
          <button
            type="button"
            class="flex items-center justify-center gap-2 rounded-xl border border-surface-200 bg-white px-4 py-3 text-sm font-medium text-surface-700 transition-all hover:bg-surface-50 hover:border-surface-300 active:scale-[0.98] dark:border-surface-700 dark:bg-surface-800 dark:text-surface-300 dark:hover:bg-surface-700"
            @click="handleGoogleLogin"
          >
            <svg class="h-5 w-5" viewBox="0 0 24 24">
              <path d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92a5.06 5.06 0 01-2.2 3.32v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.1z" fill="#4285F4" />
              <path d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z" fill="#34A853" />
              <path d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l2.85-2.22.81-.62z" fill="#FBBC05" />
              <path d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z" fill="#EA4335" />
            </svg>
            Google
          </button>
          <button
            type="button"
            class="flex items-center justify-center gap-2 rounded-xl border border-surface-200 bg-white px-4 py-3 text-sm font-medium text-surface-700 transition-all hover:bg-surface-50 hover:border-surface-300 active:scale-[0.98] dark:border-surface-700 dark:bg-surface-800 dark:text-surface-300 dark:hover:bg-surface-700"
            @click="handleGithubLogin"
          >
            <svg class="h-5 w-5" fill="currentColor" viewBox="0 0 24 24">
              <path fill-rule="evenodd" d="M12 2C6.477 2 2 6.484 2 12.017c0 4.425 2.865 8.18 6.839 9.504.5.092.682-.217.682-.483 0-.237-.008-.868-.013-1.703-2.782.605-3.369-1.343-3.369-1.343-.454-1.158-1.11-1.466-1.11-1.466-.908-.62.069-.608.069-.608 1.003.07 1.531 1.032 1.531 1.032.892 1.53 2.341 1.088 2.91.832.092-.647.35-1.088.636-1.338-2.22-.253-4.555-1.113-4.555-4.951 0-1.093.39-1.988 1.029-2.688-.103-.253-.446-1.272.098-2.65 0 0 .84-.27 2.75 1.026A9.564 9.564 0 0112 6.844c.85.004 1.705.115 2.504.337 1.909-1.296 2.747-1.027 2.747-1.027.546 1.379.202 2.398.1 2.651.64.7 1.028 1.595 1.028 2.688 0 3.848-2.339 4.695-4.566 4.943.359.309.678.92.678 1.855 0 1.338-.012 2.419-.012 2.747 0 .268.18.58.688.482A10.019 10.019 0 0022 12.017C22 6.484 17.522 2 12 2z" clip-rule="evenodd" />
            </svg>
            GitHub
          </button>
        </div>

        <!-- Bottom Link -->
        <p class="mt-6 text-center text-sm text-surface-500">
          {{ t('auth.noAccount') }}
          <button
            type="button"
            class="font-semibold text-brand-500 hover:text-brand-600 transition-colors"
            @click="switchTab('register')"
          >
            {{ t('auth.register') }}
          </button>
        </p>
      </form>
    </Transition>

    <!-- Register Form -->
    <Transition
      enter-active-class="transition-all duration-300 ease-out"
      enter-from-class="opacity-0 translate-x-4"
      enter-to-class="opacity-100 translate-x-0"
      leave-active-class="transition-all duration-200 ease-in"
      leave-from-class="opacity-100 translate-x-0"
      leave-to-class="opacity-0 -translate-x-4"
    >
      <form
        v-if="activeTab === 'register'"
        key="register"
        class="space-y-5"
        @submit.prevent="handleRegister"
      >
        <!-- Name -->
        <div class="space-y-1.5">
          <label class="block text-sm font-medium text-surface-700 dark:text-surface-300">
            {{ t('auth.name') }}
          </label>
          <div class="relative">
            <div class="pointer-events-none absolute inset-y-0 left-0 flex items-center pl-3.5">
              <svg class="h-5 w-5 text-surface-400" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.5">
                <path stroke-linecap="round" stroke-linejoin="round" d="M15.75 6a3.75 3.75 0 11-7.5 0 3.75 3.75 0 017.5 0zM4.501 20.118a7.5 7.5 0 0114.998 0A17.933 17.933 0 0112 21.75c-2.676 0-5.216-.584-7.499-1.632z" />
              </svg>
            </div>
            <input
              v-model="registerForm.name"
              type="text"
              :placeholder="t('auth.name')"
              :class="[
                'input-base pl-11',
                registerErrors.name && 'border-danger-400 focus:ring-danger-500/30 focus:border-danger-500',
              ]"
              @input="registerErrors.name = ''; registerSubmitError = ''"
            />
          </div>
          <p v-if="registerErrors.name" class="flex items-center gap-1 text-xs text-danger-500">
            <svg class="h-3.5 w-3.5 shrink-0" fill="currentColor" viewBox="0 0 20 20">
              <path fill-rule="evenodd" d="M18 10a8 8 0 11-16 0 8 8 0 0116 0zm-7-4a1 1 0 11-2 0 1 1 0 012 0zM9 9a1 1 0 000 2v3a1 1 0 001 1h1a1 1 0 100-2v-3a1 1 0 00-1-1H9z" clip-rule="evenodd" />
            </svg>
            {{ registerErrors.name }}
          </p>
        </div>

        <!-- Email -->
        <div class="space-y-1.5">
          <label class="block text-sm font-medium text-surface-700 dark:text-surface-300">
            {{ t('auth.email') }}
          </label>
          <div class="relative">
            <div class="pointer-events-none absolute inset-y-0 left-0 flex items-center pl-3.5">
              <svg class="h-5 w-5 text-surface-400" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.5">
                <path stroke-linecap="round" stroke-linejoin="round" d="M21.75 6.75v10.5a2.25 2.25 0 01-2.25 2.25h-15a2.25 2.25 0 01-2.25-2.25V6.75m19.5 0A2.25 2.25 0 0019.5 4.5h-15a2.25 2.25 0 00-2.25 2.25m19.5 0v.243a2.25 2.25 0 01-1.07 1.916l-7.5 4.615a2.25 2.25 0 01-2.36 0L3.32 8.91a2.25 2.25 0 01-1.07-1.916V6.75" />
              </svg>
            </div>
            <input
              v-model="registerForm.email"
              type="email"
              placeholder="your@email.com"
              :class="[
                'input-base pl-11',
                registerErrors.email && 'border-danger-400 focus:ring-danger-500/30 focus:border-danger-500',
              ]"
              @input="registerErrors.email = ''; registerSubmitError = ''"
            />
          </div>
          <p v-if="registerErrors.email" class="flex items-center gap-1 text-xs text-danger-500">
            <svg class="h-3.5 w-3.5 shrink-0" fill="currentColor" viewBox="0 0 20 20">
              <path fill-rule="evenodd" d="M18 10a8 8 0 11-16 0 8 8 0 0116 0zm-7-4a1 1 0 11-2 0 1 1 0 012 0zM9 9a1 1 0 000 2v3a1 1 0 001 1h1a1 1 0 100-2v-3a1 1 0 00-1-1H9z" clip-rule="evenodd" />
            </svg>
            {{ registerErrors.email }}
          </p>
        </div>

        <!-- Password -->
        <div class="space-y-1.5">
          <label class="block text-sm font-medium text-surface-700 dark:text-surface-300">
            {{ t('auth.password') }}
          </label>
          <div class="relative">
            <div class="pointer-events-none absolute inset-y-0 left-0 flex items-center pl-3.5">
              <svg class="h-5 w-5 text-surface-400" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.5">
                <path stroke-linecap="round" stroke-linejoin="round" d="M16.5 10.5V6.75a4.5 4.5 0 10-9 0v3.75m-.75 11.25h10.5a2.25 2.25 0 002.25-2.25v-6.75a2.25 2.25 0 00-2.25-2.25H6.75a2.25 2.25 0 00-2.25 2.25v6.75a2.25 2.25 0 002.25 2.25z" />
              </svg>
            </div>
            <input
              v-model="registerForm.password"
              type="password"
              :placeholder="t('auth.password')"
              :class="[
                'input-base pl-11',
                registerErrors.password && 'border-danger-400 focus:ring-danger-500/30 focus:border-danger-500',
              ]"
              @input="registerErrors.password = ''; registerSubmitError = ''"
            />
          </div>
          <p v-if="registerErrors.password" class="flex items-center gap-1 text-xs text-danger-500">
            <svg class="h-3.5 w-3.5 shrink-0" fill="currentColor" viewBox="0 0 20 20">
              <path fill-rule="evenodd" d="M18 10a8 8 0 11-16 0 8 8 0 0116 0zm-7-4a1 1 0 11-2 0 1 1 0 012 0zM9 9a1 1 0 000 2v3a1 1 0 001 1h1a1 1 0 100-2v-3a1 1 0 00-1-1H9z" clip-rule="evenodd" />
            </svg>
            {{ registerErrors.password }}
          </p>
          <p v-else class="text-xs leading-relaxed text-surface-400 dark:text-surface-500">
            {{ t('auth.passwordHint') }}
          </p>
        </div>

        <!-- Confirm Password -->
        <div class="space-y-1.5">
          <label class="block text-sm font-medium text-surface-700 dark:text-surface-300">
            {{ t('auth.confirmPassword') }}
          </label>
          <div class="relative">
            <div class="pointer-events-none absolute inset-y-0 left-0 flex items-center pl-3.5">
              <svg class="h-5 w-5 text-surface-400" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.5">
                <path stroke-linecap="round" stroke-linejoin="round" d="M9 12.75L11.25 15 15 9.75m-3-7.036A11.959 11.959 0 013.598 6 11.99 11.99 0 003 9.749c0 5.592 3.824 10.29 9 11.623 5.176-1.332 9-6.03 9-11.622 0-1.31-.21-2.571-.598-3.751h-.152c-3.196 0-6.1-1.248-8.25-3.285z" />
              </svg>
            </div>
            <input
              v-model="registerForm.confirmPassword"
              type="password"
              :placeholder="t('auth.confirmPassword')"
              :class="[
                'input-base pl-11',
                registerErrors.confirmPassword && 'border-danger-400 focus:ring-danger-500/30 focus:border-danger-500',
              ]"
              @input="registerErrors.confirmPassword = ''; registerSubmitError = ''"
            />
          </div>
          <p v-if="registerErrors.confirmPassword" class="flex items-center gap-1 text-xs text-danger-500">
            <svg class="h-3.5 w-3.5 shrink-0" fill="currentColor" viewBox="0 0 20 20">
              <path fill-rule="evenodd" d="M18 10a8 8 0 11-16 0 8 8 0 0116 0zm-7-4a1 1 0 11-2 0 1 1 0 012 0zM9 9a1 1 0 000 2v3a1 1 0 001 1h1a1 1 0 100-2v-3a1 1 0 00-1-1H9z" clip-rule="evenodd" />
            </svg>
            {{ registerErrors.confirmPassword }}
          </p>
        </div>

        <!-- Submit Error -->
        <div
          v-if="registerSubmitError"
          class="flex items-start gap-2 rounded-xl border border-danger-200 bg-danger-50 px-3.5 py-3 text-sm leading-snug text-danger-600 dark:border-danger-500/30 dark:bg-danger-500/10 dark:text-danger-400"
        >
          <svg class="h-4 w-4 mt-0.5 shrink-0" fill="currentColor" viewBox="0 0 20 20">
            <path fill-rule="evenodd" d="M18 10a8 8 0 11-16 0 8 8 0 0116 0zm-7-4a1 1 0 11-2 0 1 1 0 012 0zM9 9a1 1 0 000 2v3a1 1 0 001 1h1a1 1 0 100-2v-3a1 1 0 00-1-1H9z" clip-rule="evenodd" />
          </svg>
          <span class="flex-1 min-w-0 break-words">{{ registerSubmitError }}</span>
        </div>

        <!-- Submit -->
        <button
          type="submit"
          :disabled="registerLoading"
          class="btn-primary w-full py-3.5 text-base"
        >
          <svg v-if="registerLoading" class="animate-spin -ml-1 h-5 w-5" fill="none" viewBox="0 0 24 24">
            <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4" />
            <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
          </svg>
          {{ registerLoading ? t('common.loading') : t('auth.register') }}
        </button>

        <!-- Divider -->
        <div class="relative my-6">
          <div class="absolute inset-0 flex items-center">
            <div class="w-full border-t border-surface-200 dark:border-surface-700" />
          </div>
          <div class="relative flex justify-center text-sm">
            <span class="bg-white dark:bg-surface-900 px-3 text-surface-400">{{ t('auth.orContinueWith') }}</span>
          </div>
        </div>

        <!-- OAuth Buttons -->
        <div class="grid grid-cols-2 gap-3">
          <button
            type="button"
            class="flex items-center justify-center gap-2 rounded-xl border border-surface-200 bg-white px-4 py-3 text-sm font-medium text-surface-700 transition-all hover:bg-surface-50 hover:border-surface-300 active:scale-[0.98] dark:border-surface-700 dark:bg-surface-800 dark:text-surface-300 dark:hover:bg-surface-700"
            @click="handleGoogleLogin"
          >
            <svg class="h-5 w-5" viewBox="0 0 24 24">
              <path d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92a5.06 5.06 0 01-2.2 3.32v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.1z" fill="#4285F4" />
              <path d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z" fill="#34A853" />
              <path d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l2.85-2.22.81-.62z" fill="#FBBC05" />
              <path d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z" fill="#EA4335" />
            </svg>
            Google
          </button>
          <button
            type="button"
            class="flex items-center justify-center gap-2 rounded-xl border border-surface-200 bg-white px-4 py-3 text-sm font-medium text-surface-700 transition-all hover:bg-surface-50 hover:border-surface-300 active:scale-[0.98] dark:border-surface-700 dark:bg-surface-800 dark:text-surface-300 dark:hover:bg-surface-700"
            @click="handleGithubLogin"
          >
            <svg class="h-5 w-5" fill="currentColor" viewBox="0 0 24 24">
              <path fill-rule="evenodd" d="M12 2C6.477 2 2 6.484 2 12.017c0 4.425 2.865 8.18 6.839 9.504.5.092.682-.217.682-.483 0-.237-.008-.868-.013-1.703-2.782.605-3.369-1.343-3.369-1.343-.454-1.158-1.11-1.466-1.11-1.466-.908-.62.069-.608.069-.608 1.003.07 1.531 1.032 1.531 1.032.892 1.53 2.341 1.088 2.91.832.092-.647.35-1.088.636-1.338-2.22-.253-4.555-1.113-4.555-4.951 0-1.093.39-1.988 1.029-2.688-.103-.253-.446-1.272.098-2.65 0 0 .84-.27 2.75 1.026A9.564 9.564 0 0112 6.844c.85.004 1.705.115 2.504.337 1.909-1.296 2.747-1.027 2.747-1.027.546 1.379.202 2.398.1 2.651.64.7 1.028 1.595 1.028 2.688 0 3.848-2.339 4.695-4.566 4.943.359.309.678.92.678 1.855 0 1.338-.012 2.419-.012 2.747 0 .268.18.58.688.482A10.019 10.019 0 0022 12.017C22 6.484 17.522 2 12 2z" clip-rule="evenodd" />
            </svg>
            GitHub
          </button>
        </div>

        <!-- Bottom Link -->
        <p class="mt-6 text-center text-sm text-surface-500">
          {{ t('auth.hasAccount') }}
          <button
            type="button"
            class="font-semibold text-brand-500 hover:text-brand-600 transition-colors"
            @click="switchTab('login')"
          >
            {{ t('auth.login') }}
          </button>
        </p>
      </form>
    </Transition>
  </div>
</template>
