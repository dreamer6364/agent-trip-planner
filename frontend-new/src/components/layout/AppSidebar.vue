<script setup lang="ts">
import { Dialog, DialogPanel, TransitionChild, TransitionRoot } from '@headlessui/vue'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import UserAvatar from './UserAvatar.vue'

interface Props {
  open: boolean
}

defineProps<Props>()

const emit = defineEmits<{
  close: []
}>()

const router = useRouter()
const authStore = useAuthStore()
const { t } = useI18n()

const navLinks = computed(() => [
  { label: t('nav.dashboard'), to: '/dashboard', icon: '📊' },
  { label: t('nav.newTrip'), to: '/trips/new', icon: '✨' },
  { label: t('nav.myTrips'), to: '/trips', icon: '🗺️' },
  { label: t('nav.settings'), to: '/settings', icon: '⚙️' },
])

function navigate(to: string) {
  router.push(to)
  emit('close')
}

function handleLogout() {
  // 与 AppNavbar 一致：必须清 tf_token / tf_refresh，否则路由守卫仍视为已登录
  authStore.logout()
  emit('close')
  router.push('/login')
}
</script>

<template>
  <TransitionRoot :show="open" as="template">
    <Dialog as="div" class="relative z-50" @close="emit('close')">
      <!-- Backdrop -->
      <TransitionChild
        as="template"
        enter="transition-opacity duration-300 ease-out"
        enter-from="opacity-0"
        enter-to="opacity-100"
        leave="transition-opacity duration-200 ease-in"
        leave-from="opacity-100"
        leave-to="opacity-0"
      >
        <div class="fixed inset-0 bg-black/40 backdrop-blur-sm" />
      </TransitionChild>

      <!-- Panel -->
      <div class="fixed inset-0 overflow-hidden">
        <div class="absolute inset-0 overflow-hidden">
          <TransitionChild
            as="template"
            enter="transition-transform duration-300 ease-out"
            enter-from="-translate-x-full"
            enter-to="translate-x-0"
            leave="transition-transform duration-200 ease-in"
            leave-from="translate-x-0"
            leave-to="-translate-x-full"
          >
            <DialogPanel class="pointer-events-auto fixed inset-y-0 left-0 flex w-full max-w-xs flex-col bg-white shadow-2xl">
              <!-- Header -->
              <div class="flex items-center justify-between border-b border-gray-100 px-6 py-4">
                <div class="flex items-center gap-2">
                  <div class="flex h-8 w-8 items-center justify-center rounded-lg bg-gradient-to-br from-brand-500 to-brand-600 shadow-md shadow-brand-500/25">
                    <svg class="h-4 w-4 text-white" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                      <path stroke-linecap="round" stroke-linejoin="round" d="M3.055 11H5a2 2 0 012 2v1a2 2 0 002 2 2 2 0 012 2v2.945M8 3.935V5.5A2.5 2.5 0 0010.5 8h.5a2 2 0 012 2 2 2 0 104 0 2 2 0 012-2h1.064M15 20.488V18a2 2 0 012-2h3.064M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
                    </svg>
                  </div>
                  <span class="bg-gradient-to-r from-brand-600 to-brand-500 bg-clip-text text-lg font-bold text-transparent">
                    TripForge
                  </span>
                </div>
                <button
                  class="flex h-8 w-8 items-center justify-center rounded-lg text-gray-400 transition-colors hover:bg-gray-100 hover:text-gray-600"
                  @click="emit('close')"
                >
                  <svg class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                    <path stroke-linecap="round" stroke-linejoin="round" d="M6 18L18 6M6 6l12 12" />
                  </svg>
                </button>
              </div>

              <!-- User Info -->
              <div class="border-b border-gray-100 px-6 py-4">
                <div class="flex items-center gap-3">
                  <UserAvatar name="User" size="md" />
                  <div>
                    <p class="text-sm font-semibold text-gray-900">User</p>
                    <p class="text-xs text-gray-500">user@example.com</p>
                  </div>
                </div>
              </div>

              <!-- Navigation -->
              <div class="flex-1 overflow-y-auto px-3 py-4">
                <nav class="space-y-1">
                  <button
                    v-for="link in navLinks"
                    :key="link.to"
                    class="flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-sm font-medium text-gray-600 transition-all hover:bg-brand-50 hover:text-brand-600"
                    @click="navigate(link.to)"
                  >
                    <span class="text-lg">{{ link.icon }}</span>
                    {{ link.label }}
                  </button>
                </nav>
              </div>

              <!-- Footer -->
              <div class="border-t border-gray-100 px-3 py-3">
                <button
                  class="flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-sm font-medium text-red-600 transition-all hover:bg-red-50"
                  @click="handleLogout"
                >
                  <span class="text-lg">🚪</span>
                  {{ t('nav.logout') }}
                </button>
              </div>
            </DialogPanel>
          </TransitionChild>
        </div>
      </div>
    </Dialog>
  </TransitionRoot>
</template>
