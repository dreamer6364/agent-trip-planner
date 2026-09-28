<script setup lang="ts">
defineProps<{
  modelValue: string
  label?: string
  placeholder?: string
  type?: string
  error?: string
  hint?: string
  disabled?: boolean
}>()

defineEmits<{
  'update:modelValue': [value: string]
}>()
</script>

<template>
  <div class="space-y-1.5">
    <label v-if="label" class="block text-sm font-medium text-surface-700 dark:text-surface-300">
      {{ label }}
    </label>
    <input
      :type="type || 'text'"
      :value="modelValue"
      :placeholder="placeholder"
      :disabled="disabled"
      :class="[
        'input-base',
        error && 'border-danger-400 focus:ring-danger-500/30 focus:border-danger-500',
        disabled && 'opacity-50 cursor-not-allowed',
      ]"
      @input="$emit('update:modelValue', ($event.target as HTMLInputElement).value)"
    />
    <p v-if="error" class="text-xs text-danger-500">{{ error }}</p>
    <p v-else-if="hint" class="text-xs text-surface-400">{{ hint }}</p>
  </div>
</template>
