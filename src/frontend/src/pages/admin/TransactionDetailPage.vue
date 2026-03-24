<template>
  <q-page padding>
    <q-btn flat icon="arrow_back" label="Back to Search" to="/admin/transactions" class="q-mb-md" />

    <div v-if="loading" class="text-center q-pa-lg">
      <q-spinner size="50px" />
    </div>

    <template v-else-if="detail">
      <q-card class="q-mb-md">
        <q-card-section>
          <div class="text-h6">{{ detail.summary.transactionId }}</div>
          <div class="row q-col-gutter-md q-mt-sm">
            <div class="col-12 col-md-3">
              <div class="text-overline text-grey">Status</div>
              <q-badge :color="statusColor(detail.summary.txStatus)">{{ detail.summary.txStatus }}</q-badge>
            </div>
            <div class="col-12 col-md-3">
              <div class="text-overline text-grey">Provider</div>
              <div>{{ detail.summary.provider }}</div>
            </div>
            <div class="col-12 col-md-3">
              <div class="text-overline text-grey">Phone</div>
              <div>{{ detail.summary.externalReference }}</div>
            </div>
            <div class="col-12 col-md-3">
              <div class="text-overline text-grey">Risk Score</div>
              <div :class="riskClass(detail.summary.riskScore)">{{ detail.summary.riskScore ?? 'N/A' }}</div>
            </div>
          </div>
          <div class="text-caption text-grey q-mt-sm">
            Trace ID: {{ detail.summary.traceId }}
          </div>
        </q-card-section>
      </q-card>

      <div class="text-subtitle1 q-mb-sm">Event Timeline</div>
      <q-timeline color="primary">
        <q-timeline-entry
          v-for="(event, idx) in detail.events"
          :key="idx"
          :title="event.eventType"
          :subtitle="new Date(event.createdDate).toLocaleString()"
          :icon="'circle'"
        >
          <div class="text-caption">
            <span class="text-grey">{{ event.statusFrom }} &rarr; {{ event.statusTo }}</span>
            <span class="q-ml-md text-grey-7">actor: {{ event.actor }}</span>
          </div>
          <div v-if="event.metadata" class="text-caption q-mt-xs">
            <code>{{ event.metadata }}</code>
          </div>
        </q-timeline-entry>
      </q-timeline>
    </template>

    <div v-else class="text-grey text-center q-pa-lg">Transaction not found.</div>
  </q-page>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { adminApi } from 'src/api/admin.api.js'
import { useQuasar } from 'quasar'

const $q = useQuasar()
const route = useRoute()
const loading = ref(true)
const detail = ref(null)

function statusColor(status) {
  if (status === 'SUCCESS') return 'positive'
  if (status === 'FAILED') return 'negative'
  if (status === 'PROCESSING') return 'primary'
  return 'grey'
}

function riskClass(score) {
  if (!score && score !== 0) return ''
  if (score >= 70) return 'text-negative text-weight-bold'
  if (score >= 40) return 'text-warning'
  return 'text-positive'
}

onMounted(async () => {
  try {
    const response = await adminApi.getTransactionDetail(route.params.transactionId)
    detail.value = response.data
  } catch (err) {
    $q.notify({ type: 'negative', message: 'Failed to load transaction: ' + (err.message ?? 'Unknown error') })
  } finally {
    loading.value = false
  }
})
</script>
