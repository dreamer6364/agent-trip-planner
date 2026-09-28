<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '@/stores/auth'
import NotificationBell from './NotificationBell.vue'
import UserAvatar from './UserAvatar.vue'
import AppSidebar from './AppSidebar.vue'

const router = useRouter()
const authStore = useAuthStore()
const { t, locale } = useI18n()

const mobileMenuOpen = ref(false)
const userDropdownOpen = ref(false)
const darkMode = ref(false)

const navLinks = computed(() => [
  { label: t('nav.dashboard'), to: '/dashboard', icon: 'ri-route-line' },
  { label: t('nav.create'), to: '/trips/create', icon: 'ri-add-circle-line' },
  { label: t('nav.explore'), to: '/explore', icon: 'ri-compass-3-line' },
])

const userMenuItems = computed(() => [
  { label: t('nav.profile'), icon: '👤', action: () => router.push('/profile') },
  { label: t('nav.logout'), icon: '🚪', danger: true, action: () => handleLogout() },
])

function toggleDarkMode() {
  darkMode.value = !darkMode.value
  document.documentElement.classList.toggle('dark')
}

function toggleLanguage() {
  locale.value = locale.value === 'zh' ? 'en' : 'zh'
}

function handleLogout() {
  // 退出登录必须清掉本应用真实使用的 tf_token / tf_refresh 并通知后端，
  // 否则路由守卫仍认为已登录，push('/login') 会被立刻重定向回 /dashboard
  authStore.logout()
  userDropdownOpen.value = false
  router.push('/login')
}
</script>

<template>
  <nav class="sticky top-0 z-40 border-b border-white/20 bg-white/80 backdrop-blur-xl">
    <div class="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
      <div class="flex h-16 items-center justify-between">
        <!-- Left: Logo + Desktop Nav -->
        <div class="flex items-center gap-8">
          <!-- Logo -->
          <router-link to="/" class="flex items-center gap-2">
            <div class="flex h-9 w-9 items-center justify-center rounded-xl bg-gradient-to-br from-brand-500 to-brand-600 shadow-lg shadow-brand-500/25">
              <svg class="h-5 w-5 text-white" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                <path stroke-linecap="round" stroke-linejoin="round" d="M3.055 11H5a2 2 0 012 2v1a2 2 0 002 2 2 2 0 012 2v2.945M8 3.935V5.5A2.5 2.5 0 0010.5 8h.5a2 2 0 012 2 2 2 0 104 0 2 2 0 012-2h1.064M15 20.488V18a2 2 0 012-2h3.064M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
              </svg>
            </div>
            <span class="bg-gradient-to-r from-brand-600 to-brand-500 bg-clip-text text-xl font-bold text-transparent">
              TripForge
            </span>
          </router-link>

          <!-- Desktop Nav Links -->
          <div class="hidden items-center gap-1 md:flex">
            <router-link
              v-for="link in navLinks"
              :key="link.to"
              :to="link.to"
              class="flex items-center gap-2 rounded-xl px-4 py-2 text-sm font-medium text-gray-600 transition-all hover:bg-brand-50 hover:text-brand-600"
              active-class="!bg-brand-50 !text-brand-600"
            >
              <i :class="link.icon" class="text-base" />
              {{ link.label }}
            </router-link>
          </div>
        </div>

        <!-- Right: Actions -->
        <div class="flex items-center gap-2">
          <!-- Language Toggle -->
          <button
            class="flex h-9 w-9 items-center justify-center rounded-xl text-sm font-semibold text-gray-600 transition-all hover:bg-gray-100"
            @click="toggleLanguage"
          >
            {{ locale === 'zh' ? '中' : 'EN' }}
          </button>

          <!-- Dark Mode Toggle -->
          <button
            class="flex h-9 w-9 items-center justify-center rounded-xl text-gray-600 transition-all hover:bg-gray-100"
            @click="toggleDarkMode"
          >
            <svg
              v-if="!darkMode"
              class="h-5 w-5 transition-transform duration-300"
              fill="none"
              viewBox="0 0 24 24"
              stroke="currentColor"
              stroke-width="2"
            >
              <path stroke-linecap="round" stroke-linejoin="round" d="M20.354 15.354A9 9 0 018.646 3.646 9.003 9.003 0 0012 21a9.003 9.003 0 008.354-5.646z" />
            </svg>
            <svg
              v-else
              class="h-5 w-5 transition-transform duration-300 rotate-180"
              fill="none"
              viewBox="0 0 24 24"
              stroke="currentColor"
              stroke-width="2"
            >
              <path stroke-linecap="round" stroke-linejoin="round" d="M12 3v1m0 16v1m9-9h-1M4 12H3m15.364 6.364l-.707-.707M6.343 6.343l-.707-.707m12.728 0l-.707.707M6.343 17.657l-.707.707M16 12a4 4 0 11-8 0 4 4 0 018 0z" />
            </svg>
          </button>

          <!-- Notification Bell -->
          <NotificationBell />

          <!-- User Avatar -->
          <div class="relative hidden md:block">
            <button
              class="flex items-center gap-2 rounded-xl p-1.5 transition-all hover:bg-gray-100"
              @click="userDropdownOpen = !userDropdownOpen"
            >
              <UserAvatar name="User" size="sm" />
            </button>

            <!-- User Dropdown -->
            <Transition
              enter-active-class="transition duration-100 ease-out"
              enter-from-class="scale-95 opacity-0"
              enter-to-class="scale-100 opacity-100"
              leave-active-class="transition duration-75 ease-in"
              leave-from-class="scale-100 opacity-100"
              leave-to-class="scale-95 opacity-0"
            >
              <div
                v-if="userDropdownOpen"
                class="absolute right-0 z-50 mt-2 w-56 origin-top-right rounded-xl border border-gray-100 bg-white p-1.5 shadow-xl ring-1 ring-black/5"
              >
                <button
                  v-for="item in userMenuItems"
                  :key="item.label"
                  :class="[
                    'flex w-full items-center gap-2.5 rounded-lg px-3 py-2 text-sm transition-colors hover:bg-gray-50',
                    item.danger ? 'text-red-600' : 'text-gray-700',
                  ]"
                  @click="item.action?.(); userDropdownOpen = false"
                >
                  <span>{{ item.icon }}</span>
                  {{ item.label }}
                </button>
              </div>
            </Transition>
          </div>

          <!-- Mobile Menu Button -->
          <button
            class="flex h-9 w-9 items-center justify-center rounded-xl text-gray-600 transition-all hover:bg-gray-100 md:hidden"
            @click="mobileMenuOpen = true"
          >
            <svg class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
              <path stroke-linecap="round" stroke-linejoin="round" d="M4 6h16M4 12h16M4 18h16" />
            </svg>
          </button>
        </div>
      </div>
    </div>

    <!-- Mobile Sidebar -->
    <AppSidebar
      :open="mobileMenuOpen"
      @close="mobileMenuOpen = false"
    />
  </nav>

  <!-- Backdrop for user dropdown -->
  <div
    v-if="userDropdownOpen"
    class="fixed inset-0 z-30"
    @click="userDropdownOpen = false"
  />
</template>
