import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useAdminMetricsStore = defineStore('adminMetrics', () => {
  const paymentSuccessTotal = ref(0)
  const paymentFailedTotal = ref(0)
  const fraudBlockedTotal = ref(0)
  const tpsLast5s = ref(0)
  const processingCount = ref(0)
  const providerLatencyMs = ref({})   // Map<providerName, latencyMs>
  const lastUpdated = ref(null)
  const connected = ref(false)

  function updateMetrics(snapshot) {
    paymentSuccessTotal.value = snapshot.paymentSuccessTotal
    paymentFailedTotal.value = snapshot.paymentFailedTotal
    fraudBlockedTotal.value = snapshot.fraudBlockedTotal
    tpsLast5s.value = snapshot.tpsLast5s
    processingCount.value = snapshot.processingCount
    providerLatencyMs.value = snapshot.providerLatencyMs ?? {}
    lastUpdated.value = snapshot.timestamp
    connected.value = true
  }

  function setDisconnected() {
    connected.value = false
  }

  return {
    paymentSuccessTotal,
    paymentFailedTotal,
    fraudBlockedTotal,
    tpsLast5s,
    processingCount,
    providerLatencyMs,
    lastUpdated,
    connected,
    updateMetrics,
    setDisconnected,
  }
})
