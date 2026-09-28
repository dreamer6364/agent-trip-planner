<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { useAppStore } from '@/stores/app'

const { t } = useI18n()
const appStore = useAppStore()
</script>

<template>
  <div class="min-h-screen flex bg-surface-50 dark:bg-surface-900">
    <!-- Left: Branding -->
    <div class="hidden lg:flex lg:w-1/2 relative overflow-hidden">
      <div class="absolute inset-0 gradient-bg opacity-90"></div>
      <div class="absolute inset-0 bg-[url('data:image/svg+xml;base64,PHN2ZyB3aWR0aD0iNjAiIGhlaWdodD0iNjAiIHZpZXdCb3g9IjAgMCA2MCA2MCIgeG1sbnM9Imh0dHA6Ly93d3cudzMub3JnLzIwMDAvc3ZnIj48ZyBmaWxsPSJub25lIiBmaWxsLXJ1bGU9ImV2ZW5vZGQiPjxnIGZpbGw9IiNmZmZmZmYiIGZpbGwtb3BhY2l0eT0iMC4wNSI+PHBhdGggZD0iTTM2IDM0djItSDI0di0yaDEyem0wLTRWMjhIMjR2Mmgxem0tMi0ydi0ySDE0djJoMjB6Ii8+PC9nPjwvZz48L3N2Zz4=')] opacity-30"></div>
      <div class="relative z-10 flex flex-col items-center justify-center w-full p-12 text-white">
        <div class="animate-float mb-8">
          <div class="w-24 h-24 rounded-3xl bg-white/20 backdrop-blur-sm flex items-center justify-center text-5xl font-extrabold shadow-glow">
            TF
          </div>
        </div>
        <h1 class="text-4xl font-bold mb-4">{{ t('app.name') }}</h1>
        <p class="text-xl text-white/80 text-center max-w-sm">
          {{ t('app.slogan') }}
        </p>
      </div>
    </div>

    <!-- Right: Form -->
    <div class="flex-1 flex items-center justify-center p-6 sm:p-12">
      <div class="w-full max-w-md">
        <router-view />
      </div>
    </div>
  </div>
</template>
