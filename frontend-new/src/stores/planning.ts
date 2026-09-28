import { defineStore } from 'pinia'
import { ref } from 'vue'
import { planApi } from '@/api/plan'
import type { PlanTaskProgress, ParsePreviewResponse } from '@/api/types'

export const usePlanningStore = defineStore('planning', () => {
  const currentTask = ref<PlanTaskProgress | null>(null)
  const tasks = ref<PlanTaskProgress[]>([])
  const parseResult = ref<ParsePreviewResponse | null>(null)
  const loading = ref(false)
  const planning = ref(false)

  const progress = ref(0)
  const stage = ref('')
  const stageMessage = ref('')

  async function parsePreview(data: Parameters<typeof planApi.parsePreview>[0]) {
    loading.value = true
    try {
      parseResult.value = await planApi.parsePreview(data)
      return parseResult.value
    } finally {
      loading.value = false
    }
  }

  async function fetchTaskProgress(taskId: string) {
    currentTask.value = await planApi.getTaskProgress(taskId)
    progress.value = currentTask.value.progress
    stage.value = currentTask.value.stage || ''
    stageMessage.value = currentTask.value.message || ''
    return currentTask.value
  }

  async function fetchTripTasks(tripId: string) {
    tasks.value = await planApi.getTripTasks(tripId)
    return tasks.value
  }

  function updateFromProgress(data: { percent: number; stage?: string; message?: string }) {
    progress.value = data.percent
    if (data.stage) stage.value = data.stage
    if (data.message) stageMessage.value = data.message
  }

  function reset() {
    currentTask.value = null
    tasks.value = []
    parseResult.value = null
    progress.value = 0
    stage.value = ''
    stageMessage.value = ''
  }

  return {
    currentTask, tasks, parseResult, loading, planning,
    progress, stage, stageMessage,
    parsePreview, fetchTaskProgress, fetchTripTasks,
    updateFromProgress, reset,
  }
})
