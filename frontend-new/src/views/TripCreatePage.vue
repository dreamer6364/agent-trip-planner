<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useTripStore } from '@/stores/trip'
import { useToast } from '@/composables/useToast'
import TripForm from '@/components/trip/TripForm.vue'
import AIPlanIntroModal from '@/components/trip/AIPlanIntroModal.vue'
import type { CreateTripRequest } from '@/api/types'

const { t } = useI18n()
const router = useRouter()
const tripStore = useTripStore()
const toast = useToast()

const isSubmitting = ref(false)
const showPlanIntro = ref(false)
const pendingForm = ref<CreateTripRequest | null>(null)

const tips = [
  '试着描述你喜欢的旅行风格',
  '提到具体城市名效果更好',
  '可以指定交通方式'
]

const handleBack = () => {
  router.back()
}

const handleSubmit = (formData: CreateTripRequest) => {
  pendingForm.value = formData
  showPlanIntro.value = true
}

const handleIntroClose = () => {
  if (isSubmitting.value) return
  showPlanIntro.value = false
}

const handlePlanConfirm = async () => {
  if (!pendingForm.value || isSubmitting.value) return
  isSubmitting.value = true
  try {
    const newTrip = await tripStore.createTrip(pendingForm.value)
    toast.success('行程创建成功！AI 已生成完整行程')
    showPlanIntro.value = false
    pendingForm.value = null
    router.push(`/trips/${newTrip.id}/planning`)
  } catch (error: unknown) {
    console.error('Failed to create trip:', error)
    showPlanIntro.value = false
    const msg = error instanceof Error ? error.message : ''
    if (msg.includes('timeout') || msg.includes('Timeout')) {
      toast.error('AI 规划超时，请稍后在行程详情中重试')
    } else {
      toast.error(msg || '创建失败，请重试')
    }
  } finally {
    isSubmitting.value = false
  }
}
</script>

<template>
  <div class="min-h-screen bg-gradient-to-br from-slate-50 to-blue-50">
    <div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
      <!-- Back Button -->
      <button
        @click="handleBack"
        class="inline-flex items-center text-gray-600 hover:text-gray-800 mb-8 transition-colors duration-200"
      >
        <i class="ri-arrow-left-line mr-2"></i>
        {{ t('common.back') }}
      </button>

      <!-- Page Title -->
      <h1 class="text-4xl font-bold mb-10">
        <span class="bg-gradient-to-r from-blue-600 via-purple-500 to-pink-500 bg-clip-text text-transparent">
          {{ t('tripCreate.title') }}
        </span>
      </h1>

      <!-- Main Content -->
      <div class="flex flex-col lg:flex-row gap-10">
        <!-- Form Section -->
        <div class="flex-1 lg:w-2/3">
          <div class="bg-white rounded-2xl shadow-sm p-8">
            <TripForm
              @submit="handleSubmit"
              :loading="isSubmitting"
            />
          </div>
        </div>

        <!-- AI Planning Intro / Progress Modal -->
        <AIPlanIntroModal
          :open="showPlanIntro"
          :loading="isSubmitting"
          @close="handleIntroClose"
          @confirm="handlePlanConfirm"
        />

        <!-- Tips Panel (Desktop Only) -->
        <div class="hidden lg:block lg:w-1/3">
          <div class="bg-white rounded-2xl shadow-sm p-8 sticky top-8">
            <div class="flex items-center mb-6">
              <div class="w-10 h-10 rounded-xl bg-gradient-to-br from-yellow-400 to-orange-400 flex items-center justify-center mr-3">
                <i class="ri-lightbulb-line text-white text-xl"></i>
              </div>
              <h3 class="text-lg font-semibold text-gray-800">{{ t('tripCreate.tips.title') }}</h3>
            </div>
            
            <ul class="space-y-4">
              <li 
                v-for="(tip, index) in tips" 
                :key="index"
                class="flex items-start"
              >
                <div class="w-6 h-6 rounded-full bg-gradient-to-br from-blue-100 to-purple-100 flex items-center justify-center mr-3 mt-0.5 flex-shrink-0">
                  <span class="text-sm font-medium text-blue-600">{{ index + 1 }}</span>
                </div>
                <p class="text-gray-600 leading-relaxed">{{ tip }}</p>
              </li>
            </ul>

            <div class="mt-8 p-4 bg-gradient-to-br from-blue-50 to-purple-50 rounded-xl">
              <p class="text-sm text-gray-600">
                <i class="ri-information-line mr-1"></i>
                {{ t('tripCreate.tips.hint') }}
              </p>
            </div>
          </div>
        </div>
      </div>

      <!-- Mobile Tips (Accordion) -->
      <div class="lg:hidden mt-8">
        <details class="bg-white rounded-2xl shadow-sm overflow-hidden">
          <summary class="px-6 py-4 cursor-pointer font-medium text-gray-700 flex items-center justify-between">
            <div class="flex items-center">
              <i class="ri-lightbulb-line text-yellow-500 mr-2"></i>
              {{ t('tripCreate.tips.title') }}
            </div>
            <i class="ri-arrow-down-s-line text-gray-400 transition-transform duration-200"></i>
          </summary>
          <div class="px-6 pb-6">
            <ul class="space-y-4">
              <li 
                v-for="(tip, index) in tips" 
                :key="index"
                class="flex items-start"
              >
                <div class="w-6 h-6 rounded-full bg-gradient-to-br from-blue-100 to-purple-100 flex items-center justify-center mr-3 mt-0.5 flex-shrink-0">
                  <span class="text-sm font-medium text-blue-600">{{ index + 1 }}</span>
                </div>
                <p class="text-gray-600 leading-relaxed">{{ tip }}</p>
              </li>
            </ul>
          </div>
        </details>
      </div>
    </div>
  </div>
</template>
