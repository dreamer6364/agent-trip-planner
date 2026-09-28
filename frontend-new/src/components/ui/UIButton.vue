<script setup lang="ts">
defineProps<{
  variant?: 'primary' | 'secondary' | 'ghost' | 'danger' | 'success'
  size?: 'sm' | 'md' | 'lg'
  loading?: boolean
  disabled?: boolean
  icon?: boolean
}>()

defineEmits<{
  click: [event: MouseEvent]
}>()
</script>

<template>
  <button
    :class="[
      'inline-flex items-center justify-center gap-2 font-semibold rounded-xl transition-all duration-200 ease-out active:scale-[0.98]',
      {
        'btn-primary': variant === 'primary' || !variant,
        'btn-secondary': variant === 'secondary',
        'btn-ghost': variant === 'ghost',
        'bg-danger-500 hover:bg-danger-600 text-white shadow-lg shadow-danger-500/25': variant === 'danger',
        'bg-success-500 hover:bg-success-600 text-white shadow-lg shadow-success-500/25': variant === 'success',
      },
      {
        'px-3 py-1.5 text-xs': size === 'sm',
        'px-5 py-2.5 text-sm': size === 'md' || !size,
        'px-7 py-3.5 text-base': size === 'lg',
      },
      {
        'px-3 py-3': icon,
        'opacity-50 cursor-not-allowed pointer-events-none': disabled || loading,
      },
    ]"
    :disabled="disabled || loading"
    @click="$emit('click', $event)"
  >
    <svg v-if="loading" class="animate-spin -ml-1 h-4 w-4" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24">
      <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
      <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"></path>
    </svg>
    <slot />
  </button>
</template>
