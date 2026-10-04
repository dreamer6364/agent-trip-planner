<script setup lang="ts">
import { ref, reactive, onMounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import dayjs from 'dayjs'
import { useAuthStore } from '@/stores/auth'
import { useAppStore } from '@/stores/app'
import { useToast } from '@/composables/useToast'
import { authApi } from '@/api/auth'
import UserAvatar from '@/components/layout/UserAvatar.vue'
import UITabs from '@/components/ui/UITabs.vue'
import UIButton from '@/components/ui/UIButton.vue'
import UIInput from '@/components/ui/UIInput.vue'
import AvatarPicker from '@/components/ui/AvatarPicker.vue'

const router = useRouter()
const { t } = useI18n()
const authStore = useAuthStore()
const appStore = useAppStore()
const toast = useToast()

const activeTab = ref('profile')
const profileLoading = ref(false)
const passwordLoading = ref(false)

const tabs = computed(() => [
  { key: 'profile', label: t('profile.tabProfile') },
  { key: 'preferences', label: t('profile.tabPreferences') },
  { key: 'security', label: t('profile.tabSecurity') },
])

const profileForm = reactive({
  name: '',
  avatarUrl: '',
})

const prefsForm = reactive({
  theme: 'light' as 'light' | 'dark',
  locale: 'zh-CN',
})

const passwordForm = reactive({
  currentPassword: '',
  newPassword: '',
  confirmPassword: '',
})

const passwordErrors = reactive({
  current: '',
  new: '',
  confirm: '',
})

const memberSince = computed(() => {
  if (!authStore.user?.createdAt) return '--'
  return dayjs(authStore.user.createdAt).format(t('profile.dateFormat'))
})

const themeOptions = computed(() => [
  { value: 'light', label: t('profile.themeLight') },
  { value: 'dark', label: t('profile.themeDark') },
])

const localeOptions = [
  { value: 'zh-CN', label: '中文' },
  { value: 'en-US', label: 'English' },
]

function goBack() {
  router.back()
}

function initProfileForm() {
  if (authStore.user) {
    profileForm.name = authStore.user.name || ''
    profileForm.avatarUrl = authStore.user.avatarUrl || ''
  }
}

function initPrefsForm() {
  prefsForm.theme = appStore.darkMode ? 'dark' : 'light'
  prefsForm.locale = appStore.locale || 'zh-CN'
}

async function saveProfile() {
  if (!profileForm.name.trim()) {
    toast.error(t('profile.nameRequired'))
    return
  }
  profileLoading.value = true
  try {
    await authStore.updateProfile({
      name: profileForm.name.trim(),
      // 空串 = 清除自定义头像（后端 null 跳过、空串覆盖），不能再用 || undefined 兜底
      avatarUrl: profileForm.avatarUrl.trim(),
    })
    toast.success(t('profile.saveProfileSuccess'))
  } catch (err: unknown) {
    const message = err instanceof Error ? err.message : t('profile.saveFailed')
    toast.error(message)
  } finally {
    profileLoading.value = false
  }
}

function savePreferences() {
  if (prefsForm.theme !== (appStore.darkMode ? 'dark' : 'light')) {
    appStore.toggleTheme()
  }
  if (prefsForm.locale !== appStore.locale) {
    appStore.setLocale(prefsForm.locale)
  }
  toast.success(t('profile.savePrefsSuccess'))
}

function validatePassword(): boolean {
  let valid = true
  passwordErrors.current = ''
  passwordErrors.new = ''
  passwordErrors.confirm = ''

  if (!passwordForm.currentPassword) {
    passwordErrors.current = t('profile.currentPasswordRequired')
    valid = false
  }
  if (!passwordForm.newPassword) {
    passwordErrors.new = t('profile.newPasswordRequired')
    valid = false
  } else if (passwordForm.newPassword.length < 8) {
    passwordErrors.new = t('profile.passwordTooShort')
    valid = false
  }
  if (!passwordForm.confirmPassword) {
    passwordErrors.confirm = t('profile.confirmNewPasswordRequired')
    valid = false
  } else if (passwordForm.newPassword !== passwordForm.confirmPassword) {
    passwordErrors.confirm = t('profile.passwordMismatch')
    valid = false
  }
  return valid
}

async function changePassword() {
  if (!validatePassword()) return
  passwordLoading.value = true
  try {
    await authApi.updatePassword({
      currentPassword: passwordForm.currentPassword,
      newPassword: passwordForm.newPassword,
      confirmNewPassword: passwordForm.confirmPassword,
    })
    passwordForm.currentPassword = ''
    passwordForm.newPassword = ''
    passwordForm.confirmPassword = ''
    toast.success(t('profile.passwordChangeSuccess'))
  } catch (err: unknown) {
    const message = err instanceof Error ? err.message : t('profile.passwordChangeFailed')
    toast.error(message)
  } finally {
    passwordLoading.value = false
  }
}

onMounted(async () => {
  if (!authStore.user) {
    await authStore.fetchUser()
  }
  initProfileForm()
  initPrefsForm()
})
</script>

<template>
  <div class="min-h-screen bg-gradient-to-br from-slate-50 to-blue-50">
    <div class="max-w-4xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
      <!-- Back Button -->
      <button
        class="inline-flex items-center gap-1.5 text-sm font-medium text-surface-600 hover:text-brand-600 transition-colors duration-200 mb-6"
        @click="goBack"
      >
        <svg class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
          <path stroke-linecap="round" stroke-linejoin="round" d="M15 19l-7-7 7-7" />
        </svg>
        {{ t('common.back') }}
      </button>

      <!-- Profile Header -->
      <div class="bg-white rounded-2xl shadow-card p-6 sm:p-8 mb-8">
        <div class="flex flex-col sm:flex-row items-center gap-6">
          <UserAvatar
            :name="authStore.user?.name || t('profile.userFallback')"
            :src="profileForm.avatarUrl || undefined"
            size="lg"
          />
          <div class="text-center sm:text-left flex-1 min-w-0">
            <h1 class="text-2xl font-bold text-surface-900 truncate">
              {{ authStore.user?.name || t('profile.userFallback') }}
            </h1>
            <p class="text-sm text-surface-500 mt-1 truncate">
              {{ authStore.user?.email || '' }}
            </p>
            <p class="text-xs text-surface-400 mt-1">
              {{ t('profile.memberSince', { date: memberSince }) }}
            </p>
          </div>
        </div>
      </div>

      <!-- Tabs -->
      <div class="bg-white rounded-2xl shadow-card overflow-hidden">
        <div class="px-6 sm:px-8 pt-4">
          <UITabs v-model="activeTab" :tabs="tabs" />
        </div>

        <!-- 个人信息 Tab -->
        <div v-if="activeTab === 'profile'" class="p-6 sm:p-8">
          <div class="max-w-lg space-y-5">
            <UIInput
              v-model="profileForm.name"
              :label="t('auth.name')"
              :placeholder="t('profile.nameRequired')"
            />
            <AvatarPicker v-model="profileForm.avatarUrl" />
            <div class="pt-2">
              <UIButton
                variant="primary"
                :loading="profileLoading"
                @click="saveProfile"
              >
                {{ t('profile.save') }}
              </UIButton>
            </div>
          </div>
        </div>

        <!-- 偏好设置 Tab -->
        <div v-if="activeTab === 'preferences'" class="p-6 sm:p-8">
          <div class="max-w-lg space-y-6">
            <!-- Theme Toggle -->
            <div>
              <label class="block text-sm font-medium text-surface-700 mb-3">{{ t('profile.themeLabel') }}</label>
              <div class="flex gap-3">
                <button
                  v-for="opt in themeOptions"
                  :key="opt.value"
                  :class="[
                    'flex-1 rounded-xl border-2 p-4 text-center transition-all duration-200',
                    prefsForm.theme === opt.value
                      ? 'border-brand-500 bg-brand-50 text-brand-700 shadow-sm'
                      : 'border-surface-200 bg-white text-surface-600 hover:border-surface-300',
                  ]"
                  @click="prefsForm.theme = opt.value as 'light' | 'dark'"
                >
                  <div class="text-2xl mb-1">{{ opt.value === 'dark' ? '🌙' : '☀️' }}</div>
                  <div class="text-sm font-medium">{{ opt.label }}</div>
                </button>
              </div>
            </div>

            <!-- Language -->
            <div>
              <label class="block text-sm font-medium text-surface-700 mb-3">{{ t('profile.language') }}</label>
              <div class="flex gap-3">
                <button
                  v-for="opt in localeOptions"
                  :key="opt.value"
                  :class="[
                    'flex-1 rounded-xl border-2 p-4 text-center transition-all duration-200',
                    prefsForm.locale === opt.value
                      ? 'border-brand-500 bg-brand-50 text-brand-700 shadow-sm'
                      : 'border-surface-200 bg-white text-surface-600 hover:border-surface-300',
                  ]"
                  @click="prefsForm.locale = opt.value"
                >
                  <div class="text-sm font-medium">{{ opt.label }}</div>
                </button>
              </div>
            </div>

            <div class="pt-2">
              <UIButton
                variant="primary"
                @click="savePreferences"
              >
                {{ t('profile.savePrefs') }}
              </UIButton>
            </div>
          </div>
        </div>

        <!-- 安全设置 Tab -->
        <div v-if="activeTab === 'security'" class="p-6 sm:p-8">
          <div class="max-w-lg space-y-6">
            <!-- Change Password -->
            <div>
              <h3 class="text-base font-semibold text-surface-900 mb-4">{{ t('profile.changePassword') }}</h3>
              <div class="space-y-4">
                <UIInput
                  v-model="passwordForm.currentPassword"
                  :label="t('profile.currentPassword')"
                  type="password"
                  :placeholder="t('profile.currentPasswordRequired')"
                  :error="passwordErrors.current"
                />
                <UIInput
                  v-model="passwordForm.newPassword"
                  :label="t('profile.newPassword')"
                  type="password"
                  :placeholder="t('profile.newPasswordPlaceholder')"
                  :error="passwordErrors.new"
                />
                <UIInput
                  v-model="passwordForm.confirmPassword"
                  :label="t('profile.confirmNewPassword')"
                  type="password"
                  :placeholder="t('profile.confirmPasswordPlaceholder')"
                  :error="passwordErrors.confirm"
                />
                <div class="pt-1">
                  <UIButton
                    variant="primary"
                    :loading="passwordLoading"
                    @click="changePassword"
                  >
                    {{ t('profile.changePassword') }}
                  </UIButton>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
