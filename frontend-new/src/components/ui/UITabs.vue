<script setup lang="ts">
import { ref, watch, onMounted, onUnmounted, nextTick } from 'vue'

interface Tab {
  key: string
  label: string
}

interface Props {
  tabs: Tab[]
  modelValue: string
}

const props = defineProps<Props>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

const tabRefs = ref<HTMLElement[]>([])
const rootRef = ref<HTMLElement | null>(null)
const indicatorStyle = ref<{ left: string; width: string }>({ left: '0px', width: '0px' })

function updateIndicator() {
  const index = props.tabs.findIndex((t) => t.key === props.modelValue)
  if (index === -1 || !tabRefs.value[index] || !rootRef.value) return

  const el = tabRefs.value[index]
  // 以组件根节点为定位基准：根节点必须是 relative，
  // 否则 absolute 指示条会逃逸到页面级包含块，出现「下划线跑到页面左侧」的偏移
  const rootRect = rootRef.value.getBoundingClientRect()
  const elRect = el.getBoundingClientRect()

  indicatorStyle.value = {
    left: `${elRect.left - rootRect.left}px`,
    width: `${elRect.width}px`,
  }
}

function selectTab(key: string) {
  emit('update:modelValue', key)
}

watch(
  () => props.modelValue,
  () => nextTick(updateIndicator)
)

watch(
  () => props.tabs,
  () => nextTick(updateIndicator)
)

onMounted(() => {
  nextTick(updateIndicator)
  window.addEventListener('resize', updateIndicator)
})

onUnmounted(() => {
  window.removeEventListener('resize', updateIndicator)
})
</script>

<template>
  <div ref="rootRef" class="relative border-b border-gray-200">
    <nav class="-mb-px flex gap-1" role="tablist">
      <button
        v-for="tab in tabs"
        :key="tab.key"
        :ref="(el) => { if (el) tabRefs[tabs.indexOf(tab)] = el as HTMLElement }"
        :class="[
          'relative px-4 py-2.5 text-sm font-medium transition-colors duration-200',
          modelValue === tab.key
            ? 'text-brand-600'
            : 'text-gray-500 hover:text-gray-700',
        ]"
        role="tab"
        :aria-selected="modelValue === tab.key"
        @click="selectTab(tab.key)"
      >
        {{ tab.label }}
      </button>
    </nav>

    <!-- Animated underline indicator -->
    <div
      class="pointer-events-none absolute bottom-0 h-0.5 rounded-full bg-gradient-to-r from-brand-500 to-brand-600 transition-all duration-300 ease-out"
      :style="indicatorStyle"
    />
  </div>
</template>

