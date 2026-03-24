<template>
  <q-page padding>
    <div class="text-h5 q-mb-md">Admin Dashboard</div>

    <div class="row q-col-gutter-md q-mb-md">
      <div class="col-12 col-sm-6 col-md-3">
        <q-card>
          <q-card-section>
            <div class="text-overline text-grey">Success</div>
            <div class="text-h4">{{ store.paymentSuccessTotal }}</div>
          </q-card-section>
        </q-card>
      </div>
      <div class="col-12 col-sm-6 col-md-3">
        <q-card>
          <q-card-section>
            <div class="text-overline text-grey">Failed</div>
            <div class="text-h4 text-negative">{{ store.paymentFailedTotal }}</div>
          </q-card-section>
        </q-card>
      </div>
      <div class="col-12 col-sm-6 col-md-3">
        <q-card>
          <q-card-section>
            <div class="text-overline text-grey">Fraud Blocked</div>
            <div class="text-h4 text-warning">{{ store.fraudBlockedTotal }}</div>
          </q-card-section>
        </q-card>
      </div>
      <div class="col-12 col-sm-6 col-md-3">
        <q-card>
          <q-card-section>
            <div class="text-overline text-grey">TPS (last 5s)</div>
            <div class="text-h4">{{ store.tpsLast5s.toFixed(2) }}</div>
          </q-card-section>
        </q-card>
      </div>
    </div>

    <div class="row q-col-gutter-md q-mb-md">
      <div class="col-12 col-md-4">
        <q-card>
          <q-card-section>
            <div class="text-overline text-grey">In-Flight (PROCESSING)</div>
            <div class="text-h4">{{ store.processingCount }}</div>
          </q-card-section>
        </q-card>
      </div>
      <div class="col-12 col-md-4">
        <q-card>
          <q-card-section>
            <div class="text-overline text-grey">Stream Status</div>
            <q-badge :color="store.connected ? 'positive' : 'negative'">
              {{ store.connected ? 'Live' : 'Disconnected' }}
            </q-badge>
            <div class="text-caption text-grey q-mt-sm" v-if="store.lastUpdated">
              Last update: {{ new Date(store.lastUpdated).toLocaleTimeString() }}
            </div>
          </q-card-section>
        </q-card>
      </div>
    </div>

    <div class="text-subtitle1 q-mb-sm">Provider Latency</div>
    <div class="row q-col-gutter-md q-mb-md">
      <div
        v-for="(latencyMs, providerName) in store.providerLatencyMs"
        :key="providerName"
        class="col-12 col-sm-6 col-md-3"
      >
        <q-card>
          <q-card-section>
            <div class="text-overline text-grey">{{ providerName }} Latency</div>
            <div class="text-h4">{{ latencyMs }} ms</div>
          </q-card-section>
        </q-card>
      </div>
      <div
        v-if="Object.keys(store.providerLatencyMs).length === 0"
        class="col-12"
      >
        <div class="text-grey text-caption">No provider latency data yet.</div>
      </div>
    </div>
  </q-page>
</template>

<script setup>
import { onMounted, onUnmounted } from 'vue'
import { useAdminMetricsStore } from 'src/stores/admin-metrics.store.js'

const store = useAdminMetricsStore()
let eventSource = null

onMounted(() => {
  eventSource = new EventSource('/v1/admin/metrics/stream', { withCredentials: true })
  eventSource.onmessage = (event) => {
    store.updateMetrics(JSON.parse(event.data))
  }
  eventSource.onerror = () => {
    store.setDisconnected()
    // Browser EventSource spec handles automatic reconnect — no manual retry needed
  }
})

onUnmounted(() => {
  eventSource?.close()
})
</script>
