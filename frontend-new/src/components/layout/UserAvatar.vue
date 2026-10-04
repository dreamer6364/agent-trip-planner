<script setup lang="ts">
import { computed, ref, watch } from 'vue'

interface Props {
  name: string
  src?: string
  size?: 'sm' | 'md' | 'lg'
}

const props = withDefaults(defineProps<Props>(), {
  size: 'md',
  src: undefined,
})

const imgFailed = ref(false)

watch(
  () => props.src,
  () => {
    imgFailed.value = false
  },
)

const initial = computed(() => {
  return props.name?.charAt(0)?.toUpperCase() || '?'
})

const showImg = computed(() => !!props.src && !imgFailed.value)

const sizeClasses = computed(() => {
  const map = {
    sm: 'h-8 w-8 text-xs',
    md: 'h-10 w-10 text-sm',
    lg: 'h-14 w-14 text-lg',
  }
  return map[props.size]
})

const gradients = [
  'from-brand-400 to-brand-600',
  'from-purple-400 to-purple-600',
  'from-pink-400 to-pink-600',
  'from-blue-400 to-blue-600',
  'from-emerald-400 to-emerald-600',
  'from-amber-400 to-amber-600',
]

const gradientClass = computed(() => {
  const hash = props.name.split('').reduce((acc, char) => acc + char.charCodeAt(0), 0)
  return gradients[hash % gradients.length]
})
</script>

<template>
  <div
    :class="[
      'flex items-center justify-center overflow-hidden rounded-full bg-gradient-to-br font-semibold text-white shadow-md',
      sizeClasses,
      gradientClass,
    ]"
  >
    <img
      v-if="showImg"
      :src="props.src"
      :alt="initial"
      class="h-full w-full rounded-full object-cover"
      @error="imgFailed = true"
    />
    <span v-else>{{ initial }}</span>
  </div>
</template>
