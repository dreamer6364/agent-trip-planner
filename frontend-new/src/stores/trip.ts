import { defineStore } from 'pinia'
import { ref } from 'vue'
import { tripApi } from '@/api/trip'
import type { Trip, TripVersion } from '@/api/types'

export const useTripStore = defineStore('trip', () => {
  const trips = ref<Trip[]>([])
  const currentTrip = ref<Trip | null>(null)
  const currentVersion = ref<TripVersion | null>(null)
  const total = ref(0)
  const loading = ref(false)
  const creating = ref(false)

  async function fetchTrips(params?: { page?: number; size?: number; keyword?: string; status?: string }) {
    loading.value = true
    try {
      const data = await tripApi.list(params)
      trips.value = data.items
      total.value = data.total
    } finally {
      loading.value = false
    }
  }

  async function fetchTrip(id: string) {
    loading.value = true
    try {
      currentTrip.value = await tripApi.get(id)
      if (currentTrip.value?.latestVersion) {
        currentVersion.value = currentTrip.value.latestVersion
      }
    } finally {
      loading.value = false
    }
  }

  async function createTrip(data: Parameters<typeof tripApi.create>[0]) {
    creating.value = true
    try {
      const trip = await tripApi.create(data)
      return trip
    } finally {
      creating.value = false
    }
  }

  async function updateTrip(id: string, data: Parameters<typeof tripApi.update>[1]) {
    const trip = await tripApi.update(id, data)
    if (currentTrip.value?.id === id) {
      currentTrip.value = { ...currentTrip.value, ...trip }
    }
    const idx = trips.value.findIndex((t) => t.id === id)
    if (idx !== -1) {
      trips.value[idx] = { ...trips.value[idx], ...trip }
    }
    return trip
  }

  async function deleteTrip(id: string) {
    await tripApi.remove(id)
    trips.value = trips.value.filter((t) => t.id !== id)
    total.value = Math.max(0, total.value - 1)
    if (currentTrip.value?.id === id) {
      currentTrip.value = null
      currentVersion.value = null
    }
  }

  async function fetchVersion(id: string, versionNum: number) {
    currentVersion.value = await tripApi.getVersion(id, versionNum)
  }

  async function fetchVersions(id: string) {
    return tripApi.getVersions(id)
  }

  function updateActivitiesFromVersion() {
    // Refresh trip to get updated version
    if (currentTrip.value) {
      fetchTrip(currentTrip.value.id)
    }
  }

  return {
    trips, currentTrip, currentVersion, total, loading, creating,
    fetchTrips, fetchTrip, createTrip, updateTrip, deleteTrip,
    fetchVersion, fetchVersions, updateActivitiesFromVersion,
  }
})
