<script setup lang="ts">
defineProps<{
  padding?: 'none' | 'sm' | 'md' | 'lg'
  hover?: boolean
}>()
</script>

<template>
  <div
    :class="[
      'card-base',
      {
        'p-0': padding === 'none' || !padding,
        'p-3': padding === 'sm',
        'p-5': padding === 'md',
        'p-7': padding === 'lg',
      },
      hover && 'hover:shadow-card-hover hover:-translate-y-0.5 cursor-pointer',
    ]"
  >
    <slot />
  </div>
</template>
