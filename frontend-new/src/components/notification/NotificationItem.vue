<script setup lang="ts">
import { computed } from 'vue'
import dayjs from 'dayjs'
import relativeTime from 'dayjs/plugin/relativeTime'
import 'dayjs/locale/zh-cn'
import type { Notification } from '@/api/types'

dayjs.extend(relativeTime)
dayjs.locale('zh-cn')

interface Props {
  notification: Notification
}

const props = defineProps<Props>()

const emit = defineEmits<{
  read: [id: string]
}>()

const iconConfig = computed(() => {
  const map: Record<string, { icon: string; color: string; bg: string }> = {
    PROGRESS: {
      icon: 'spinner',
      color: 'text-brand-500',
      bg: 'bg-brand-100',
    },
    COMPLETED: {
      icon: 'check',
      color: 'text-success-600',
      bg: 'bg-success-100',
    },
    FAILED: {
      icon: 'x',
      color: 'text-danger-600',
      bg: 'bg-danger-100',
    },
    SYSTEM: {
      icon: 'bell',
      color: 'text-surface-500',
      bg: 'bg-surface-100',
    },
    SHARE: {
      icon: 'share',
      color: 'text-accent-600',
      bg: 'bg-accent-100',
    },
    REPLAN: {
      icon: 'refresh',
      color: 'text-warning-600',
      bg: 'bg-warning-100',
    },
  }
  return map[props.notification.type] || map.SYSTEM
})

const relativeTimeText = computed(() => {
  const time = dayjs(props.notification.createdAt)
  const now = dayjs()
  const diffMinutes = now.diff(time, 'minute')

  if (diffMinutes < 1) return '刚刚'
  if (diffMinutes < 60) return `${diffMinutes}分钟前`
  if (diffMinutes < 1440) return `${Math.floor(diffMinutes / 60)}小时前`
  if (diffMinutes < 10080) return `${Math.floor(diffMinutes / 1440)}天前`
  return time.format('M月D日')
})

function handleClick() {
  if (!props.notification.read) {
    emit('read', props.notification.id)
  }
}
</script>

<template>
  <div
    :class="[
      'flex items-start gap-3 px-4 py-3 cursor-pointer transition-colors duration-150',
      notification.read
        ? 'hover:bg-surface-50'
        : 'bg-brand-50/50 hover:bg-brand-50',
    ]"
    @click="handleClick"
  >
    <div
      :class="[
        'relative flex h-9 w-9 shrink-0 items-center justify-center rounded-full',
        iconConfig.bg,
      ]"
    >
      <svg
        v-if="iconConfig.icon === 'spinner'"
        :class="['h-4 w-4 animate-spin', iconConfig.color]"
        fill="none"
        viewBox="0 0 24 24"
      >
        <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4" />
        <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
      </svg>

      <svg
        v-else-if="iconConfig.icon === 'check'"
        :class="['h-4 w-4', iconConfig.color]"
        fill="none"
        viewBox="0 0 24 24"
        stroke="currentColor"
        stroke-width="2.5"
      >
        <path stroke-linecap="round" stroke-linejoin="round" d="M5 13l4 4L19 7" />
      </svg>

      <svg
        v-else-if="iconConfig.icon === 'x'"
        :class="['h-4 w-4', iconConfig.color]"
        fill="none"
        viewBox="0 0 24 24"
        stroke="currentColor"
        stroke-width="2.5"
      >
        <path stroke-linecap="round" stroke-linejoin="round" d="M6 18L18 6M6 6l12 12" />
      </svg>

      <svg
        v-else-if="iconConfig.icon === 'bell'"
        :class="['h-4 w-4', iconConfig.color]"
        fill="none"
        viewBox="0 0 24 24"
        stroke="currentColor"
        stroke-width="2"
      >
        <path stroke-linecap="round" stroke-linejoin="round" d="M15 17h5l-1.405-1.405A2.032 2.032 0 0118 14.158V11a6.002 6.002 0 00-4-5.659V5a2 2 0 10-4 0v.341C7.67 6.165 6 8.388 6 11v3.159c0 .538-.214 1.055-.595 1.436L4 17h5m6 0v1a3 3 0 11-6 0v-1m6 0H9" />
      </svg>

      <svg
        v-else-if="iconConfig.icon === 'share'"
        :class="['h-4 w-4', iconConfig.color]"
        fill="none"
        viewBox="0 0 24 24"
        stroke="currentColor"
        stroke-width="2"
      >
        <path stroke-linecap="round" stroke-linejoin="round" d="M8.684 13.342C8.886 12.938 9 12.482 9 12c0-.482-.114-.938-.316-1.342m0 2.684a3 3 0 110-2.684m0 2.684l6.632 3.316m-6.632-6l6.632-3.316m0 0a3 3 0 105.367-2.684 3 3 0 00-5.367 2.684zm0 9.316a3 3 0 105.368 2.684 3 3 0 00-5.368-2.684z" />
      </svg>

      <svg
        v-else-if="iconConfig.icon === 'refresh'"
        :class="['h-4 w-4', iconConfig.color]"
        fill="none"
        viewBox="0 0 24 24"
        stroke="currentColor"
        stroke-width="2"
      >
        <path stroke-linecap="round" stroke-linejoin="round" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
      </svg>

      <span
        v-if="!notification.read"
        class="absolute -right-0.5 -top-0.5 h-2.5 w-2.5 rounded-full border-2 border-white bg-brand-500"
      />
    </div>

    <div class="flex-1 min-w-0">
      <div class="flex items-center justify-between gap-2">
        <h4
          :class="[
            'text-sm truncate',
            notification.read
              ? 'font-medium text-surface-700'
              : 'font-semibold text-surface-900',
          ]"
        >
          {{ notification.title }}
        </h4>
        <span class="text-xs text-surface-400 shrink-0">{{ relativeTimeText }}</span>
      </div>
      <p class="text-xs text-surface-500 mt-0.5 line-clamp-2 leading-relaxed">
        {{ notification.content }}
      </p>
    </div>
  </div>
</template>

<style scoped>
.line-clamp-2 {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
</style>
