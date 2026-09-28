<script setup lang="ts">
defineProps<{
  width?: string
  height?: string
  rounded?: string
}>()
</script>

<template>
  <div
    class="skeleton"
    :style="{
      width: width || '100%',
      height: height || '20px',
      borderRadius: rounded || '0.75rem',
    }"
  ></div>
</template>
