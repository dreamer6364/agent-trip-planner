import { createI18n } from 'vue-i18n'
import dayjs from 'dayjs'
import 'dayjs/locale/zh-cn'
import 'dayjs/locale/en'
import zhCN from './zh-CN.json'
import enUS from './en-US.json'

export const SUPPORTED_LOCALES = ['zh-CN', 'en-US'] as const
export type AppLocale = (typeof SUPPORTED_LOCALES)[number]

/** 兼容历史值（'zh'/'en'）与非法值，统一到注册的 locale 码 */
export function normalizeLocale(value: string | null | undefined): AppLocale {
  if (value === 'en' || value === 'en-US') return 'en-US'
  return 'zh-CN'
}

const i18n = createI18n({
  legacy: false,
  locale: normalizeLocale(localStorage.getItem('tf_locale')),
  fallbackLocale: 'zh-CN',
  messages: {
    'zh-CN': zhCN,
    'en-US': enUS,
  },
})

/**
 * 全局应用语言：vue-i18n / html lang / dayjs 三处同步，
 * 并持久化到 localStorage('tf_locale')。main.ts 启动时与 store.setLocale 均走此入口。
 */
export function applyLocale(lang: string | null | undefined): AppLocale {
  const locale = normalizeLocale(lang)
  i18n.global.locale.value = locale
  document.documentElement.lang = locale
  localStorage.setItem('tf_locale', locale)
  dayjs.locale(locale === 'en-US' ? 'en' : 'zh-cn')
  return locale
}

export default i18n
