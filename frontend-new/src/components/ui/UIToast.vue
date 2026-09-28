<script setup lang="ts">
import { useToast } from '@/composables/useToast'

const { toasts, dismiss } = useToast()

const typeStyles: Record<string, string> = {
  success: 'bg-success-500',
  error: 'bg-danger-500',
  info: 'bg-brand-500',
  warning: 'bg-warning-500',
}

const typeIcons: Record<string, string> = {
  success: '✓',
  error: '✕',
  info: 'ℹ',
  warning: '⚠',
}
</script>

<template>
  <Teleport to="body">
    <div class="fixed bottom-6 left-1/2 -translate-x-1/2 z-[100] flex flex-col gap-3 pointer-events-none">
      <TransitionGroup name="slide-up">
        <div
          v-for="toast in toasts"
          :key="toast.id"
          :class="[
            'pointer-events-auto flex items-center gap-3 px-5 py-3 rounded-2xl text-white font-medium shadow-panel',
            'backdrop-blur-xl animate-bounce-in cursor-pointer',
            typeStyles[toast.type],
          ]"
          @click="dismiss(toast.id)"
        >
          <span class="text-lg">{{ typeIcons[toast.type] }}</span>
          <span class="text-sm">{{ toast.message }}</span>
        </div>
      </TransitionGroup>
    </div>
  </Teleport>
</template>
