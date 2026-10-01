<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const { t, locale } = useI18n()
const authStore = useAuthStore()

const statsVisible = ref(false)
const counters = ref({ cities: 0, trips: 0, satisfaction: 0 })
const featuresVisible = ref(false)
const statsRef = ref<HTMLElement | null>(null)
const featuresRef = ref<HTMLElement | null>(null)

const features = [
  {
    key: 'ai',
    icon: `<svg class="w-8 h-8" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.5"><path stroke-linecap="round" stroke-linejoin="round" d="M9.813 15.904L9 18.75l-.813-2.846a4.5 4.5 0 00-3.09-3.09L2.25 12l2.846-.813a4.5 4.5 0 003.09-3.09L9 5.25l.813 2.846a4.5 4.5 0 003.09 3.09L15.75 12l-2.846.813a4.5 4.5 0 00-3.09 3.09zM18.259 8.715L18 9.75l-.259-1.035a3.375 3.375 0 00-2.455-2.456L14.25 6l1.036-.259a3.375 3.375 0 002.455-2.456L18 2.25l.259 1.035a3.375 3.375 0 002.455 2.456L21.75 6l-1.036.259a3.375 3.375 0 00-2.455 2.456zM16.894 20.567L16.5 21.75l-.394-1.183a2.25 2.25 0 00-1.423-1.423L13.5 18.75l1.183-.394a2.25 2.25 0 001.423-1.423l.394-1.183.394 1.183a2.25 2.25 0 001.423 1.423l1.183.394-1.183.394a2.25 2.25 0 00-1.423 1.423z" /></svg>`,
    gradient: 'from-brand-500 to-brand-600',
    shadow: 'shadow-brand-500/20',
  },
  {
    key: 'swap',
    icon: `<svg class="w-8 h-8" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.5"><path stroke-linecap="round" stroke-linejoin="round" d="M16.023 9.348h4.992v-.001M2.985 19.644v-4.992m0 0h4.992m-4.993 0l3.181 3.183a8.25 8.25 0 0013.803-3.7M4.031 9.865a8.25 8.25 0 0113.803-3.7l3.181 3.182M21.015 4.356v4.992" /></svg>`,
    gradient: 'from-accent-500 to-accent-600',
    shadow: 'shadow-accent-500/20',
  },
  {
    key: 'share',
    icon: `<svg class="w-8 h-8" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.5"><path stroke-linecap="round" stroke-linejoin="round" d="M18 18.72a9.094 9.094 0 003.741-.479 3 3 0 00-4.682-2.72m.94 3.198l.001.031c0 .225-.012.447-.037.666A11.944 11.944 0 0112 21c-2.17 0-4.207-.576-5.963-1.584A6.062 6.062 0 016 18.719m12 0a5.971 5.971 0 00-.941-3.197m0 0A5.995 5.995 0 0012 12.75a5.995 5.995 0 00-5.058 2.772m0 0a3 3 0 00-4.681 2.72 8.986 8.986 0 003.74.477m.94-3.197a5.971 5.971 0 00-.94 3.197M15 6.75a3 3 0 11-6 0 3 3 0 016 0zm6 3a2.25 2.25 0 11-4.5 0 2.25 2.25 0 014.5 0zm-13.5 0a2.25 2.25 0 11-4.5 0 2.25 2.25 0 014.5 0z" /></svg>`,
    gradient: 'from-success-500 to-success-600',
    shadow: 'shadow-success-500/20',
  },
]

const targetStats = { cities: 500, trips: 100000, satisfaction: 99 }

function animateCounter(key: keyof typeof counters.value, target: number, duration: number) {
  const start = 0
  const startTime = performance.now()
  function step(currentTime: number) {
    const elapsed = currentTime - startTime
    const progress = Math.min(elapsed / duration, 1)
    const eased = 1 - Math.pow(1 - progress, 3)
    counters.value[key] = Math.round(start + (target - start) * eased)
    if (progress < 1) {
      requestAnimationFrame(step)
    }
  }
  requestAnimationFrame(step)
}

function formatNumber(n: number): string {
  const div = locale.value === 'zh-CN' ? 10000 : 1000
  if (n >= div) {
    return (n / div).toFixed(n >= div * 10 ? 0 : 0) + t('landing.bigUnit')
  }
  return n.toString()
}

function formatStat(key: string, value: number): string {
  if (key === 'cities') return value + '+'
  if (key === 'trips') {
    const div = locale.value === 'zh-CN' ? 10000 : 1000
    if (value >= div) {
      const scaled = value / div
      return (scaled >= 10 ? Math.round(scaled) : scaled.toFixed(0)) + t('landing.bigUnit') + '+'
    }
    return value + '+'
  }
  if (key === 'satisfaction') return value + '%'
  return value.toString()
}

function handleCtaClick() {
  if (authStore.isLoggedIn) {
    router.push('/dashboard')
  } else {
    router.push('/trips/create')
  }
}

let statsObserver: IntersectionObserver | null = null
let featuresObserver: IntersectionObserver | null = null

onMounted(() => {
  statsObserver = new IntersectionObserver(
    (entries) => {
      entries.forEach((entry) => {
        if (entry.isIntersecting) {
          statsVisible.value = true
          animateCounter('cities', targetStats.cities, 2000)
          animateCounter('trips', targetStats.trips, 2000)
          animateCounter('satisfaction', targetStats.satisfaction, 2000)
          statsObserver?.disconnect()
        }
      })
    },
    { threshold: 0.3 }
  )

  featuresObserver = new IntersectionObserver(
    (entries) => {
      entries.forEach((entry) => {
        if (entry.isIntersecting) {
          featuresVisible.value = true
          featuresObserver?.disconnect()
        }
      })
    },
    { threshold: 0.2 }
  )

  if (statsRef.value) statsObserver.observe(statsRef.value)
  if (featuresRef.value) featuresObserver.observe(featuresRef.value)
})

onUnmounted(() => {
  statsObserver?.disconnect()
  featuresObserver?.disconnect()
})
</script>

<template>
  <div class="relative overflow-hidden">
    <!-- Floating Decorative Elements -->
    <div class="pointer-events-none absolute inset-0" aria-hidden="true">
      <div class="absolute -top-32 -right-32 h-96 w-96 rounded-full bg-gradient-to-br from-brand-400/20 to-accent-400/20 blur-3xl animate-float" />
      <div class="absolute top-1/3 -left-48 h-80 w-80 rounded-full bg-gradient-to-br from-accent-400/15 to-brand-400/15 blur-3xl animate-float" style="animation-delay: -2s;" />
      <div class="absolute bottom-20 right-1/4 h-64 w-64 rounded-full bg-gradient-to-br from-success-400/15 to-brand-400/15 blur-3xl animate-float" style="animation-delay: -4s;" />
      <div class="absolute top-2/3 left-1/3 h-48 w-48 rounded-full bg-gradient-to-br from-warning-400/10 to-accent-400/10 blur-3xl animate-float" style="animation-delay: -1s;" />
    </div>

    <!-- Hero Section -->
    <section class="relative min-h-screen flex items-center justify-center px-4 sm:px-6 lg:px-8">
      <div class="relative z-10 mx-auto max-w-4xl text-center">
        <!-- Badge -->
        <div class="mb-8 inline-flex items-center gap-2 rounded-full bg-brand-50 px-4 py-2 text-sm font-medium text-brand-600 ring-1 ring-brand-100 animate-fade-in">
          <span class="relative flex h-2 w-2">
            <span class="absolute inline-flex h-full w-full animate-ping rounded-full bg-brand-400 opacity-75" />
            <span class="relative inline-flex h-2 w-2 rounded-full bg-brand-500" />
          </span>
          {{ t('app.name') }} v1.0
        </div>

        <!-- Main Heading -->
        <h1 class="mb-6 text-5xl sm:text-6xl lg:text-7xl font-extrabold tracking-tight animate-slide-up">
          <span class="gradient-text">{{ t('landing.hero.title') }}</span>
        </h1>

        <!-- Subtitle -->
        <p class="mx-auto mb-10 max-w-2xl text-lg sm:text-xl text-surface-600 leading-relaxed animate-slide-up" style="animation-delay: 100ms;">
          {{ t('landing.hero.subtitle') }}
        </p>

        <!-- CTA Buttons -->
        <div class="flex flex-col sm:flex-row items-center justify-center gap-4 animate-slide-up" style="animation-delay: 200ms;">
          <button class="btn-primary text-lg px-8 py-4 group" @click="handleCtaClick">
            <span>{{ authStore.isLoggedIn ? t('landing.hero.ctaDashboard') : t('landing.hero.cta') }}</span>
            <svg class="h-5 w-5 transition-transform group-hover:translate-x-1" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
              <path stroke-linecap="round" stroke-linejoin="round" d="M13.5 4.5L21 12m0 0l-7.5 7.5M21 12H3" />
            </svg>
          </button>
          <a href="#features" class="btn-secondary text-lg px-8 py-4">
            {{ t('landing.hero.ctaSecondary') }}
          </a>
        </div>

        <!-- Scroll Indicator -->
        <div class="mt-16 animate-bounce">
          <svg class="mx-auto h-6 w-6 text-surface-400" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
            <path stroke-linecap="round" stroke-linejoin="round" d="M19.5 8.25l-7.5 7.5-7.5-7.5" />
          </svg>
        </div>
      </div>
    </section>

    <!-- Features Section -->
    <section id="features" ref="featuresRef" class="relative py-24 sm:py-32 px-4 sm:px-6 lg:px-8">
      <div class="mx-auto max-w-6xl">
        <!-- Section Title -->
        <div class="mb-16 text-center">
          <h2 class="mb-4 text-3xl sm:text-4xl font-bold text-surface-900 dark:text-white">
            {{ t('landing.features.title') }}
          </h2>
          <div class="mx-auto h-1 w-20 rounded-full bg-gradient-to-r from-brand-500 to-accent-500" />
        </div>

        <!-- Feature Cards -->
        <div class="grid grid-cols-1 md:grid-cols-3 gap-8">
          <div
            v-for="(feature, index) in features"
            :key="feature.key"
            :class="[
              'relative group rounded-3xl p-8 transition-all duration-500 ease-out',
              'bg-white/80 backdrop-blur-xl border border-white/60',
              'hover:shadow-xl hover:-translate-y-1',
              featuresVisible ? 'opacity-100 translate-y-0' : 'opacity-0 translate-y-8',
            ]"
            :style="{ transitionDelay: `${index * 150}ms` }"
          >
            <!-- Icon -->
            <div :class="[
              'mb-6 inline-flex h-16 w-16 items-center justify-center rounded-2xl text-white transition-transform duration-300 group-hover:scale-110 group-hover:rotate-3',
              'bg-gradient-to-br',
              feature.gradient,
              'shadow-lg',
              feature.shadow,
            ]">
              <div v-html="feature.icon" />
            </div>

            <!-- Content -->
            <h3 class="mb-3 text-xl font-bold text-surface-900 dark:text-white">
              {{ t(`landing.features.${feature.key}.title`) }}
            </h3>
            <p class="text-surface-500 leading-relaxed">
              {{ t(`landing.features.${feature.key}.desc`) }}
            </p>

            <!-- Hover gradient border -->
            <div class="absolute inset-0 rounded-3xl opacity-0 group-hover:opacity-100 transition-opacity duration-300 pointer-events-none ring-2 ring-brand-500/20" />
          </div>
        </div>
      </div>
    </section>

    <!-- Stats Section -->
    <section ref="statsRef" class="relative py-24 sm:py-32 px-4 sm:px-6 lg:px-8">
      <div class="mx-auto max-w-4xl">
        <div class="relative rounded-3xl overflow-hidden">
          <!-- Background -->
          <div class="absolute inset-0 gradient-bg opacity-90" />
          <div class="absolute inset-0 bg-[url('data:image/svg+xml;base64,PHN2ZyB3aWR0aD0iNjAiIGhlaWdodD0iNjAiIHZpZXdCb3g9IjAgMCA2MCA2MCIgeG1sbnM9Imh0dHA6Ly93d3cudzMub3JnLzIwMDAvc3ZnIj48ZyBmaWxsPSJub25lIiBmaWxsLXJ1bGU9ImV2ZW5vZGQiPjxnIGZpbGw9IiNmZmZmZmYiIGZpbGwtb3BhY2l0eT0iMC4wNSI+PHBhdGggZD0iTTM2IDM0djItSDI0di0yaDEyem0wLTRWMjhIMjR2Mmgxem0tMi0ydi0ySDE0djJoMjB6Ii8+PC9nPjwvZz48L3N2Zz4=')] opacity-30" />

          <!-- Content -->
          <div class="relative z-10 grid grid-cols-1 sm:grid-cols-3 gap-8 sm:gap-4 p-8 sm:p-12 text-center text-white">
            <div
              v-for="(statKey, index) in ['cities', 'trips', 'satisfaction']"
              :key="statKey"
              class="flex flex-col items-center"
            >
              <div :class="[
                'text-4xl sm:text-5xl font-extrabold mb-2 transition-all duration-700',
                statsVisible ? 'opacity-100 scale-100' : 'opacity-0 scale-90',
              ]" :style="{ transitionDelay: `${index * 200}ms` }">
                {{ formatStat(statKey, counters[statKey as keyof typeof counters]) }}
              </div>
              <div class="text-sm sm:text-base text-white/80 font-medium">
                {{ t(`landing.stats.${statKey}`) }}
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- Footer -->
    <footer class="relative py-12 px-4 sm:px-6 lg:px-8 border-t border-surface-100 dark:border-surface-800">
      <div class="mx-auto max-w-6xl text-center">
        <div class="flex items-center justify-center gap-2 mb-4">
          <div class="h-8 w-8 rounded-lg bg-gradient-to-br from-brand-500 to-brand-600 flex items-center justify-center">
            <svg class="h-4 w-4 text-white" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
              <path stroke-linecap="round" stroke-linejoin="round" d="M3.055 11H5a2 2 0 012 2v1a2 2 0 002 2 2 2 0 012 2v2.945M8 3.935V5.5A2.5 2.5 0 0010.5 8h.5a2 2 0 012 2 2 2 0 104 0 2 2 0 012-2h1.064M15 20.488V18a2 2 0 012-2h3.064M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
            </svg>
          </div>
          <span class="text-lg font-bold text-surface-700 dark:text-surface-300">TripForge</span>
        </div>
        <p class="text-sm text-surface-400">{{ t('landing.footer') }}</p>
      </div>
    </footer>
  </div>
</template>
