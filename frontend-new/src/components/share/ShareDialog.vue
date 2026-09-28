<script setup lang="ts">
import { ref, watch, computed } from 'vue'
import UIModal from '@/components/ui/UIModal.vue'
import UIButton from '@/components/ui/UIButton.vue'
import UIInput from '@/components/ui/UIInput.vue'
import { tripApi } from '@/api/trip'
import type { ShareResponse } from '@/api/types'

interface Props {
  open: boolean
  tripId: string
}

const props = defineProps<Props>()

const emit = defineEmits<{
  close: []
}>()

const isPublic = ref(true)
const password = ref('')
const expireDays = ref<number | null>(7)
const shareInfo = ref<ShareResponse | null>(null)
const loading = ref(false)
const copying = ref(false)
const copied = ref(false)
const error = ref('')

const expireOptions = [
  { value: 7, label: '7 天' },
  { value: 30, label: '30 天' },
  { value: null, label: '永不过期' },
]

const shareLink = computed(() => {
  if (shareInfo.value) return shareInfo.value.shareUrl
  return ''
})

watch(
  () => props.open,
  async (isOpen) => {
    if (isOpen && props.tripId) {
      loading.value = true
      error.value = ''
      try {
        const info = await tripApi.getShareInfo(props.tripId)
        shareInfo.value = info
        isPublic.value = info.isPublic
      } catch {
        shareInfo.value = null
      } finally {
        loading.value = false
      }
    }
    if (!isOpen) {
      copied.value = false
    }
  }
)

async function handleCreateShare() {
  loading.value = true
  error.value = ''
  try {
    const result = await tripApi.createShare(props.tripId, {
      isPublic: isPublic.value,
      expireDays: expireDays.value ?? undefined,
      password: password.value || undefined,
    })
    shareInfo.value = result
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : '创建分享失败'
  } finally {
    loading.value = false
  }
}

async function handleRevoke() {
  loading.value = true
  error.value = ''
  try {
    await tripApi.revokeShare(props.tripId)
    shareInfo.value = null
    isPublic.value = true
    password.value = ''
    expireDays.value = 7
  } catch (e: unknown) {
    error.value = '撤销分享失败'
  } finally {
    loading.value = false
  }
}

async function handleCopy() {
  if (!shareLink.value) return
  try {
    await navigator.clipboard.writeText(shareLink.value)
    copying.value = true
    copied.value = true
    setTimeout(() => {
      copied.value = false
    }, 2000)
  } catch {
    const input = document.createElement('input')
    input.value = shareLink.value
    document.body.appendChild(input)
    input.select()
    document.execCommand('copy')
    document.body.removeChild(input)
    copied.value = true
    setTimeout(() => {
      copied.value = false
    }, 2000)
  }
}
</script>

<template>
  <UIModal
    :open="open"
    title="分享行程"
    size="md"
    @close="emit('close')"
  >
    <div class="space-y-5">
      <div v-if="loading && !shareInfo" class="flex items-center justify-center py-8">
        <svg class="h-6 w-6 text-brand-500 animate-spin" fill="none" viewBox="0 0 24 24">
          <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4" />
          <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
        </svg>
      </div>

      <template v-else>
        <div class="flex items-center justify-between rounded-xl bg-surface-50 p-4">
          <div class="flex items-center gap-3">
            <div class="flex h-10 w-10 items-center justify-center rounded-full bg-brand-100">
              <svg class="h-5 w-5 text-brand-600" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                <path stroke-linecap="round" stroke-linejoin="round" d="M13.828 10.172a4 4 0 00-5.656 0l-4 4a4 4 0 105.656 5.656l1.102-1.101m-.758-4.899a4 4 0 005.656 0l4-4a4 4 0 00-5.656-5.656l-1.1 1.1" />
              </svg>
            </div>
            <div>
              <p class="text-sm font-medium text-surface-900">公开访问</p>
              <p class="text-xs text-surface-500">{{ isPublic ? '任何人可通过链接查看' : '仅授权用户可查看' }}</p>
            </div>
          </div>
          <button
            type="button"
            :class="[
              'relative inline-flex h-6 w-11 items-center rounded-full transition-colors duration-200 focus:outline-none focus:ring-2 focus:ring-brand-500 focus:ring-offset-2',
              isPublic ? 'bg-brand-500' : 'bg-surface-300',
            ]"
            @click="isPublic = !isPublic"
          >
            <span
              :class="[
                'inline-block h-4 w-4 transform rounded-full bg-white transition-transform duration-200 shadow-sm',
                isPublic ? 'translate-x-6' : 'translate-x-1',
              ]"
            />
          </button>
        </div>

        <UIInput
          v-model="password"
          label="密码保护（可选）"
          placeholder="设置访问密码"
          type="password"
        />

        <div>
          <label class="mb-1.5 block text-sm font-medium text-surface-700">链接有效期</label>
          <div class="flex gap-2">
            <button
              v-for="opt in expireOptions"
              :key="String(opt.value)"
              :class="[
                'flex-1 rounded-xl border px-3 py-2.5 text-sm font-medium transition-all duration-200',
                expireDays === opt.value
                  ? 'border-brand-400 bg-brand-50 text-brand-700 shadow-sm'
                  : 'border-surface-200 bg-white text-surface-600 hover:border-surface-300',
              ]"
              @click="expireDays = opt.value"
            >
              {{ opt.label }}
            </button>
          </div>
        </div>

        <div
          v-if="shareInfo"
          class="rounded-xl border border-success-200 bg-success-50 p-4"
        >
          <p class="text-xs font-medium text-success-700 mb-2">分享链接</p>
          <div class="flex items-center gap-2">
            <div class="flex-1 min-w-0 rounded-lg bg-white px-3 py-2 border border-success-200">
              <p class="text-sm text-surface-900 truncate font-mono">{{ shareLink }}</p>
            </div>
            <button
              :class="[
                'inline-flex items-center gap-1.5 rounded-xl px-4 py-2 text-sm font-semibold transition-all duration-200',
                copied
                  ? 'bg-success-500 text-white shadow-lg shadow-success-500/25'
                  : 'bg-brand-500 text-white shadow-lg shadow-brand-500/25 hover:shadow-xl hover:-translate-y-0.5',
              ]"
              @click="handleCopy"
            >
              <svg v-if="!copied" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                <path stroke-linecap="round" stroke-linejoin="round" d="M8 5H6a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2v-1M8 5a2 2 0 002 2h2a2 2 0 002-2M8 5a2 2 0 012-2h2a2 2 0 012 2m0 0h2a2 2 0 012 2v3m2 4H10m0 0l3-3m-3 3l3 3" />
              </svg>
              <svg v-else class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                <path stroke-linecap="round" stroke-linejoin="round" d="M5 13l4 4L19 7" />
              </svg>
              {{ copied ? '已复制' : '复制' }}
            </button>
          </div>
          <p v-if="shareInfo.expiresAt" class="mt-2 text-xs text-success-600">
            过期时间：{{ new Date(shareInfo.expiresAt).toLocaleDateString('zh-CN') }}
          </p>
        </div>

        <p v-if="error" class="text-sm text-danger-500 flex items-center gap-1">
          <svg class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
            <path stroke-linecap="round" stroke-linejoin="round" d="M12 8v4m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
          </svg>
          {{ error }}
        </p>
      </template>
    </div>

    <template #footer>
      <template v-if="shareInfo">
        <UIButton variant="danger" size="sm" :loading="loading" @click="handleRevoke">
          撤销分享
        </UIButton>
        <UIButton variant="primary" size="sm" @click="emit('close')">
          完成
        </UIButton>
      </template>
      <template v-else>
        <UIButton variant="secondary" size="sm" @click="emit('close')">
          取消
        </UIButton>
        <UIButton variant="primary" size="sm" :loading="loading" @click="handleCreateShare">
          生成分享链接
        </UIButton>
      </template>
    </template>
  </UIModal>
</template>
