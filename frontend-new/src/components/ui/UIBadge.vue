<script setup lang="ts">
defineProps<{
  variant?: 'default' | 'success' | 'warning' | 'danger' | 'brand'
  size?: 'sm' | 'md'
}>()
</script>

<template>
  <span
    :class="[
      'inline-flex items-center font-medium rounded-full',
      {
        'px-2 py-0.5 text-xs': size === 'sm' || !size,
        'px-3 py-1 text-sm': size === 'md',
      },
      {
        'bg-surface-100 text-surface-600 dark:bg-surface-700 dark:text-surface-300': variant === 'default' || !variant,
        'badge-success': variant === 'success',
        'badge-warning': variant === 'warning',
        'badge-danger': variant === 'danger',
        'badge-brand': variant === 'brand',
      },
    ]"
  >
    <slot />
  </span>
</template>
