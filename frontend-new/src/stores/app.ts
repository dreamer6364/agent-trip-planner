import { defineStore } from 'pinia'
import { ref } from 'vue'
import { applyLocale, normalizeLocale } from '@/i18n'

export const useAppStore = defineStore('app', () => {
  const darkMode = ref(false)
  const locale = ref(normalizeLocale(localStorage.getItem('tf_locale')))
  const sidebarOpen = ref(false)
  const loading = ref(false)

  function initTheme() {
    const saved = localStorage.getItem('tf_theme')
    if (saved === 'dark') {
      darkMode.value = true
      document.documentElement.classList.add('dark')
    } else if (saved === 'light') {
      darkMode.value = false
      document.documentElement.classList.remove('dark')
    } else {
      // Follow system preference
      darkMode.value = window.matchMedia('(prefers-color-scheme: dark)').matches
      if (darkMode.value) {
        document.documentElement.classList.add('dark')
      }
    }
  }

  function toggleTheme() {
    darkMode.value = !darkMode.value
    if (darkMode.value) {
      document.documentElement.classList.add('dark')
      localStorage.setItem('tf_theme', 'dark')
    } else {
      document.documentElement.classList.remove('dark')
      localStorage.setItem('tf_theme', 'light')
    }
  }

  function setLocale(lang: string) {
    locale.value = applyLocale(lang)
  }

  function toggleSidebar() {
    sidebarOpen.value = !sidebarOpen.value
  }

  function closeSidebar() {
    sidebarOpen.value = false
  }

  return { darkMode, locale, sidebarOpen, loading, initTheme, toggleTheme, setLocale, toggleSidebar, closeSidebar }
})
