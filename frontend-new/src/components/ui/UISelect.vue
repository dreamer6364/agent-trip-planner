<script setup lang="ts">
import { computed, ref } from 'vue'

interface Option {
  value: string | number
  label: string
}

interface Props {
  modelValue: string | number | null
  options: Option[]
  label?: string
  placeholder?: string
  disabled?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  label: '',
  placeholder: 'Select an option',
  disabled: false,
})

const emit = defineEmits<{
  'update:modelValue': [value: string | number]
}>()

const focused = ref(false)

const selectedLabel = computed(() => {
  const found = props.options.find((o) => o.value === props.modelValue)
  return found ? found.label : ''
})

function onChange(event: Event) {
  const target = event.target as HTMLSelectElement
  emit('update:modelValue', target.value)
}
</script>

<template>
  <div class="w-full">
    <label
      v-if="label"
      class="mb-1.5 block text-sm font-medium text-gray-700"
    >
      {{ label }}
    </label>
    <div class="relative">
      <select
        :value="modelValue ?? ''"
        :disabled="disabled"
        :class="[
          'w-full appearance-none rounded-xl border bg-white px-4 py-2.5 pr-10 text-sm text-gray-900 shadow-sm transition-all duration-200',
          'outline-none',
          focused
            ? 'border-brand-400 ring-4 ring-brand-50'
            : 'border-gray-200 hover:border-gray-300',
          disabled ? 'cursor-not-allowed opacity-50' : 'cursor-pointer',
        ]"
        @change="onChange"
        @focus="focused = true"
        @blur="focused = false"
      >
        <option value="" disabled hidden>{{ placeholder }}</option>
        <option
          v-for="option in options"
          :key="option.value"
          :value="option.value"
        >
          {{ option.label }}
        </option>
      </select>
      <div class="pointer-events-none absolute inset-y-0 right-0 flex items-center pr-3">
        <svg
          class="h-4 w-4 text-gray-400 transition-transform duration-200"
          :class="{ 'rotate-180': focused }"
          fill="none"
          viewBox="0 0 24 24"
          stroke="currentColor"
          stroke-width="2"
        >
          <path stroke-linecap="round" stroke-linejoin="round" d="M19 9l-7 7-7-7" />
        </svg>
      </div>
    </div>
  </div>
</template>
