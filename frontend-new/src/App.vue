<script setup lang="ts">
import { onMounted } from 'vue'
import { useAppStore } from '@/stores/app'
import { useAuthStore } from '@/stores/auth'
import UIToast from '@/components/ui/UIToast.vue'

const appStore = useAppStore()
const authStore = useAuthStore()

onMounted(async () => {
  appStore.initTheme()
  if (authStore.token) {
    try {
      await authStore.fetchUser()
    } catch {
      authStore.logout()
    }
  }
})
</script>

<template>
  <div :class="{ dark: appStore.darkMode }">
    <router-view v-slot="{ Component, route }">
      <transition name="page" mode="out-in">
        <component :is="Component" :key="route.path" />
      </transition>
    </router-view>
    <UIToast />
  </div>
</template>
