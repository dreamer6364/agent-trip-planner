<script setup lang="ts">
import { Menu, MenuButton, MenuItems, MenuItem } from '@headlessui/vue'

interface DropdownItem {
  label: string
  icon?: string
  action?: () => void
  danger?: boolean
}

interface Props {
  items: DropdownItem[]
}

defineProps<Props>()

const emit = defineEmits<{
  select: [item: DropdownItem]
}>()

function handleSelect(item: DropdownItem) {
  emit('select', item)
  item.action?.()
}
</script>

<template>
  <Menu as="div" class="relative inline-flex text-left">
    <MenuButton class="outline-none">
      <slot name="trigger">
        <button
          class="inline-flex items-center justify-center rounded-xl border border-gray-200 bg-white px-4 py-2 text-sm font-medium text-gray-700 shadow-sm transition-all hover:bg-gray-50 focus:outline-none focus:ring-4 focus:ring-brand-50"
        >
          Options
          <svg class="ml-2 h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
            <path stroke-linecap="round" stroke-linejoin="round" d="M19 9l-7 7-7-7" />
          </svg>
        </button>
      </slot>
    </MenuButton>

    <Transition
      enter-active-class="transition duration-100 ease-out"
      enter-from-class="scale-95 opacity-0"
      enter-to-class="scale-100 opacity-100"
      leave-active-class="transition duration-75 ease-in"
      leave-from-class="scale-100 opacity-100"
      leave-to-class="scale-95 opacity-0"
    >
      <MenuItems
        class="absolute right-0 z-50 mt-2 w-56 origin-top-right rounded-xl border border-gray-100 bg-white p-1.5 shadow-xl ring-1 ring-black/5 focus:outline-none"
      >
        <MenuItem v-for="(item, index) in items" :key="index" v-slot="{ active }">
          <button
            :class="[
              'flex w-full items-center gap-2.5 rounded-lg px-3 py-2 text-sm transition-colors',
              active ? 'bg-gray-50' : '',
              item.danger ? 'text-red-600' : 'text-gray-700',
            ]"
            @click="handleSelect(item)"
          >
            <span v-if="item.icon" class="text-base">{{ item.icon }}</span>
            {{ item.label }}
          </button>
        </MenuItem>
      </MenuItems>
    </Transition>
  </Menu>
</template>
