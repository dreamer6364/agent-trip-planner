<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import UIModal from '@/components/ui/UIModal.vue'

interface Props {
  open: boolean
  oldName: string
  newName: string
}

defineProps<Props>()

const { t } = useI18n()

const emit = defineEmits<{
  confirm: [autoAdjust: boolean]
  close: []
}>()

const autoAdjust = ref(true)

function handleConfirm() {
  emit('confirm', autoAdjust.value)
}

function handleClose() {
  autoAdjust.value = true
  emit('close')
}
</script>

<template>
  <UIModal :open="open" size="sm" @close="handleClose">
    <template #header>
      <div class="flex items-center gap-2">
        <div class="flex h-8 w-8 items-center justify-center rounded-lg bg-warning-100">
          <i class="ri-question-line text-lg text-warning-600" />
        </div>
        <h3 class="text-base font-semibold text-surface-900">{{ t('swap.confirm') }}</h3>
      </div>
    </template>

    <div class="space-y-5">
      <!-- Swap Visualization -->
      <div class="flex items-center gap-3 rounded-xl bg-surface-50 p-4">
        <div class="min-w-0 flex-1">
          <p class="mb-1 text-[10px] font-medium uppercase tracking-wider text-surface-400">{{ t('swapConfirm.current') }}</p>
          <p class="truncate text-sm font-semibold text-surface-700 line-through decoration-surface-300">
            {{ oldName }}
          </p>
        </div>
        <div class="flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-full bg-brand-100">
          <i class="ri-arrow-right-s-line text-brand-600" />
        </div>
        <div class="min-w-0 flex-1">
          <p class="mb-1 text-[10px] font-medium uppercase tracking-wider text-surface-400">{{ t('swapConfirm.replacedWith') }}</p>
          <p class="truncate text-sm font-bold text-brand-700">{{ newName }}</p>
        </div>
      </div>

      <!-- Auto Adjust Checkbox -->
      <label class="flex cursor-pointer items-start gap-3 rounded-lg border border-surface-100 p-3 transition-colors hover:bg-surface-50">
        <input
          v-model="autoAdjust"
          type="checkbox"
          class="mt-0.5 h-4 w-4 rounded border-surface-300 text-brand-600 focus:ring-brand-500"
        />
        <div>
          <p class="text-sm font-medium text-surface-800">{{ t('swapConfirm.autoAdjust') }}</p>
          <p class="mt-0.5 text-xs text-surface-500">
            {{ t('swapConfirm.autoAdjustHint') }}
          </p>
        </div>
      </label>
    </div>

    <template #footer>
      <button
        class="rounded-lg border border-surface-200 bg-white px-4 py-2 text-sm font-medium text-surface-700 transition-colors hover:bg-surface-50"
        @click="handleClose"
      >
        {{ t('swap.cancel') }}
      </button>
      <button
        class="rounded-lg bg-gradient-to-r from-brand-500 to-accent-500 px-5 py-2 text-sm font-semibold text-white shadow-sm transition-all hover:from-brand-600 hover:to-accent-600 hover:shadow-md active:scale-95"
        @click="handleConfirm"
      >
        {{ t('swap.confirm') }}
      </button>
    </template>
  </UIModal>
</template>
