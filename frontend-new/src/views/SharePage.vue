<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { tripApi } from '@/api/trip'
import { useToast } from '@/composables/useToast'
import ShareView from '@/components/share/ShareView.vue'
import UIButton from '@/components/ui/UIButton.vue'
import UIInput from '@/components/ui/UIInput.vue'
import type { Trip } from '@/api/types'

const route = useRoute()
const router = useRouter()
const toast = useToast()
const { t } = useI18n()

const token = computed(() => route.params.token as string)

const trip = ref<Trip | null>(null)
const loading = ref(true)
const notFound = ref(false)

const needsPassword = ref(false)
const password = ref('')
const passwordError = ref('')
const unlockLoading = ref(false)

async function fetchSharedTrip(pwd?: string) {
  loading.value = true
  notFound.value = false
  needsPassword.value = false
  try {
    const data = await tripApi.getSharedTrip(token.value)
    trip.value = data
  } catch (err: unknown) {
    const axiosErr = err as { response?: { status?: number } }
    if (axiosErr.response?.status === 404) {
      notFound.value = true
    } else if (axiosErr.response?.status === 403) {
      needsPassword.value = true
    } else {
      notFound.value = true
      toast.error(t('sharePage.loadFailed'))
    }
  } finally {
    loading.value = false
  }
}

async function submitPassword() {
  if (!password.value.trim()) {
    passwordError.value = t('sharePage.enterPassword')
    return
  }
  unlockLoading.value = true
  passwordError.value = ''
  try {
    const data = await tripApi.getSharedTrip(token.value)
    trip.value = data
    needsPassword.value = false
  } catch {
    passwordError.value = t('sharePage.passwordWrong')
  } finally {
    unlockLoading.value = false
  }
}

function goHome() {
  router.push('/')
}

onMounted(() => {
  fetchSharedTrip()
})
</script>

<template>
  <div class="min-h-screen bg-gradient-to-br from-slate-50 to-blue-50">
    <!-- Loading State -->
    <div v-if="loading" class="flex items-center justify-center min-h-screen">
      <div class="text-center">
        <div class="inline-flex h-12 w-12 items-center justify-center rounded-full bg-brand-100 mb-4 animate-pulse-soft">
          <svg class="h-6 w-6 text-brand-600 animate-spin" fill="none" viewBox="0 0 24 24">
            <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4" />
            <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
          </svg>
        </div>
        <p class="text-sm text-surface-500">{{ t('sharePage.loadingTrip') }}</p>
      </div>
    </div>

    <!-- Not Found State -->
    <div v-else-if="notFound" class="flex items-center justify-center min-h-screen px-4">
      <div class="text-center max-w-md">
        <div class="inline-flex h-20 w-20 items-center justify-center rounded-full bg-surface-100 mb-6">
          <svg class="h-10 w-10 text-surface-400" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.5">
            <path stroke-linecap="round" stroke-linejoin="round" d="M9.75 9.75l4.5 4.5m0-4.5l-4.5 4.5M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
          </svg>
        </div>
        <h1 class="text-xl font-bold text-surface-900 mb-2">{{ t('sharePage.notFoundTitle') }}</h1>
        <p class="text-sm text-surface-500 mb-8">
          {{ t('sharePage.notFoundDesc') }}
        </p>
        <UIButton variant="primary" @click="goHome">
          {{ t('sharePage.backHome') }}
        </UIButton>
      </div>
    </div>

    <!-- Password Required State -->
    <div v-else-if="needsPassword" class="flex items-center justify-center min-h-screen px-4">
      <div class="w-full max-w-sm">
        <div class="bg-white rounded-2xl shadow-card p-8 text-center">
          <div class="inline-flex h-16 w-16 items-center justify-center rounded-full bg-brand-100 mb-5">
            <svg class="h-8 w-8 text-brand-600" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
              <path stroke-linecap="round" stroke-linejoin="round" d="M12 15v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2zm10-10V7a4 4 0 00-8 0v4h8z" />
            </svg>
          </div>
          <h1 class="text-xl font-bold text-surface-900 mb-2">{{ t('sharePage.passwordTitle') }}</h1>
          <p class="text-sm text-surface-500 mb-6">{{ t('sharePage.passwordDesc') }}</p>

          <form @submit.prevent="submitPassword" class="space-y-4">
            <UIInput
              v-model="password"
              type="password"
              :placeholder="t('sharePage.enterPassword')"
              :error="passwordError"
              @keyup.enter="submitPassword"
            />
            <UIButton
              variant="primary"
              class="w-full"
              :loading="unlockLoading"
              @click="submitPassword"
            >
              {{ t('sharePage.unlock') }}
            </UIButton>
          </form>
        </div>

        <div class="text-center mt-6">
          <button
            class="text-sm text-surface-500 hover:text-brand-600 transition-colors duration-200"
            @click="goHome"
          >
            {{ t('sharePage.backHome') }}
          </button>
        </div>
      </div>
    </div>

    <!-- Trip Content -->
    <div v-else-if="trip">
      <ShareView :trip="trip" />

      <!-- Footer -->
      <div class="border-t border-surface-200 bg-white/80 backdrop-blur-sm">
        <div class="max-w-3xl mx-auto px-4 py-6 text-center">
          <p class="text-xs text-surface-400 flex items-center justify-center gap-1.5">
            <svg class="h-3.5 w-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
              <path stroke-linecap="round" stroke-linejoin="round" d="M13 10V3L4 14h7v7l9-11h-7z" />
            </svg>
            Powered by TripForge
          </p>
        </div>
      </div>
    </div>
  </div>
</template>
