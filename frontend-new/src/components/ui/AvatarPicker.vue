<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import UIInput from '@/components/ui/UIInput.vue'
import UserAvatar from '@/components/layout/UserAvatar.vue'
import { useAuthStore } from '@/stores/auth'
import { useToast } from '@/composables/useToast'
import { authApi } from '@/api/auth'

const props = defineProps<{
  modelValue: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

const { t } = useI18n()
const authStore = useAuthStore()
const toast = useToast()

const PRESET_NAMES = [
  'fox', 'cat', 'panda', 'frog', 'penguin', 'bear',
  'rabbit', 'lion', 'owl', 'monkey', 'chick', 'koala',
]

const presetUrls = PRESET_NAMES.map((name) => `/avatars/${name}.svg`)

const FILE_INPUT_ACCEPT = 'image/png,image/jpeg,image/webp,image/gif'
const MAX_UPLOAD_BYTES = 5 * 1024 * 1024
const RESIZE_MAX_SIDE = 256

const fileInput = ref<HTMLInputElement | null>(null)
const uploading = ref(false)
const customImgBroken = ref(false)

// 模型值是「非预设、非默认」的自选头像（上传产物或自定义 URL）
const hasCustomAvatar = computed(
  () => !!props.modelValue && !presetUrls.includes(props.modelValue) && !customImgBroken.value,
)

watch(
  () => props.modelValue,
  () => {
    customImgBroken.value = false
  },
)

function isSelected(url: string) {
  return (props.modelValue || '') === url
}

function pick(url: string) {
  emit('update:modelValue', url)
}

function openFilePicker() {
  fileInput.value?.click()
}

/** 客户端等比压缩到最长边 RESIZE_MAX_SIDE，统一转 JPEG（白底合成避免透明区变黑） */
function resizeToJpeg(file: File): Promise<Blob> {
  return new Promise((resolve, reject) => {
    const objectUrl = URL.createObjectURL(file)
    const img = new Image()
    img.onload = () => {
      URL.revokeObjectURL(objectUrl)
      const scale = Math.min(1, RESIZE_MAX_SIDE / Math.max(img.width, img.height))
      const width = Math.max(1, Math.round(img.width * scale))
      const height = Math.max(1, Math.round(img.height * scale))
      const canvas = document.createElement('canvas')
      canvas.width = width
      canvas.height = height
      const ctx = canvas.getContext('2d')
      if (!ctx) {
        reject(new Error(t('profile.avatarUploadFailed')))
        return
      }
      ctx.fillStyle = '#ffffff'
      ctx.fillRect(0, 0, width, height)
      ctx.drawImage(img, 0, 0, width, height)
      canvas.toBlob(
        (blob) => (blob ? resolve(blob) : reject(new Error(t('profile.avatarUploadFailed')))),
        'image/jpeg',
        0.88,
      )
    }
    img.onerror = () => {
      URL.revokeObjectURL(objectUrl)
      reject(new Error(t('profile.avatarTypeInvalid')))
    }
    img.src = objectUrl
  })
}

async function onFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = '' // 允许重复选择同一文件
  if (!file) return

  if (!file.type.startsWith('image/')) {
    toast.error(t('profile.avatarTypeInvalid'))
    return
  }
  if (file.size > MAX_UPLOAD_BYTES) {
    toast.error(t('profile.avatarTooLarge'))
    return
  }

  uploading.value = true
  try {
    const blob = await resizeToJpeg(file)
    const uploaded = await authApi.uploadAvatar(
      new File([blob], 'avatar.jpg', { type: 'image/jpeg' }),
    )
    const url = uploaded.avatarUrl || ''
    // 上传端点已直接落库：同步 store 让导航栏/侧边栏即时刷新
    if (authStore.user) {
      authStore.user.avatarUrl = url
    }
    emit('update:modelValue', url)
    toast.success(t('profile.avatarUploadSuccess'))
  } catch (err: unknown) {
    toast.error(err instanceof Error ? err.message : t('profile.avatarUploadFailed'))
  } finally {
    uploading.value = false
  }
}
</script>

<template>
  <div class="space-y-4">
    <div>
      <label class="block text-sm font-medium text-surface-700 mb-3">
        {{ t('profile.avatarPresets') }}
      </label>
      <div class="grid grid-cols-7 gap-2">
        <button
          type="button"
          :title="t('profile.avatarDefault')"
          :class="[
            'flex aspect-square items-center justify-center rounded-full transition-all duration-200',
            isSelected('')
              ? 'bg-brand-50 ring-2 ring-brand-500'
              : 'ring-1 ring-surface-200 hover:ring-surface-300',
          ]"
          @click="pick('')"
        >
          <UserAvatar :name="t('profile.userFallback')" size="md" />
        </button>
        <button
          v-if="hasCustomAvatar"
          type="button"
          :title="t('profile.avatarCurrent')"
          class="flex aspect-square items-center justify-center rounded-full bg-brand-50 ring-2 ring-brand-500 transition-all duration-200"
          @click="pick(modelValue)"
        >
          <img
            :src="modelValue"
            :alt="t('profile.avatarCurrent')"
            class="h-10 w-10 rounded-full object-cover"
            @error="customImgBroken = true"
          />
        </button>
        <button
          v-for="url in presetUrls"
          :key="url"
          type="button"
          :title="url.replace('/avatars/', '').replace('.svg', '')"
          :class="[
            'flex aspect-square items-center justify-center rounded-full transition-all duration-200',
            isSelected(url)
              ? 'bg-brand-50 ring-2 ring-brand-500'
              : 'ring-1 ring-surface-200 hover:ring-surface-300 hover:scale-105',
          ]"
          @click="pick(url)"
        >
          <img :src="url" :alt="url" class="h-10 w-10 rounded-full object-cover" />
        </button>
      </div>
    </div>

    <!-- 从磁盘上传 -->
    <div class="flex flex-wrap items-center gap-3">
      <input
        ref="fileInput"
        type="file"
        class="hidden"
        :accept="FILE_INPUT_ACCEPT"
        @change="onFileChange"
      />
      <button
        type="button"
        :disabled="uploading"
        class="inline-flex items-center gap-1.5 rounded-lg border border-surface-300 px-3 py-1.5 text-sm font-medium text-surface-700 transition-colors hover:bg-surface-50 disabled:opacity-50"
        @click="openFilePicker"
      >
        <svg class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
          <path stroke-linecap="round" stroke-linejoin="round" d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-8l-4-4m0 0L8 8m4-4v12" />
        </svg>
        {{ uploading ? t('profile.avatarUploading') : t('profile.avatarUpload') }}
      </button>
      <span class="text-xs text-surface-400">{{ t('profile.avatarUploadHint') }}</span>
    </div>

    <UIInput
      :model-value="modelValue"
      :label="t('profile.avatarLabel')"
      placeholder="https://example.com/avatar.jpg"
      :hint="t('profile.avatarHint')"
      @update:model-value="emit('update:modelValue', $event)"
    />
  </div>
</template>
