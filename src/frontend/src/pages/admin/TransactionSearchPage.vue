<template>
  <q-page padding>
    <div class="text-h5 q-mb-md">Transaction Search</div>

    <q-card class="q-mb-md">
      <q-card-section>
        <div class="row q-col-gutter-md">
          <div class="col-12 col-md-4">
            <q-input v-model="filters.transactionId" label="Transaction ID" dense outlined clearable />
          </div>
          <div class="col-12 col-md-4">
            <q-input v-model="filters.traceId" label="Trace ID" dense outlined clearable />
          </div>
          <div class="col-12 col-md-4">
            <q-input v-model="filters.externalReference" label="Phone Number" dense outlined clearable />
          </div>
        </div>
        <div class="row q-mt-sm">
          <q-btn color="primary" label="Search" :loading="loading" @click="search" />
          <q-btn flat label="Clear" class="q-ml-sm" @click="clearFilters" />
        </div>
      </q-card-section>
    </q-card>

    <q-table
      :rows="rows"
      :columns="columns"
      row-key="transactionId"
      :loading="loading"
      :pagination="{ rowsPerPage: 20 }"
      @row-click="onRowClick"
    />
  </q-page>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { adminApi } from 'src/api/admin.api.js'
import { useQuasar } from 'quasar'

const $q = useQuasar()
const router = useRouter()
const loading = ref(false)
const rows = ref([])

const filters = reactive({
  transactionId: '',
  traceId: '',
  externalReference: '',
})

const columns = [
  { name: 'transactionId', label: 'Transaction ID', field: 'transactionId', align: 'left' },
  { name: 'provider', label: 'Provider', field: 'provider', align: 'left' },
  { name: 'txStatus', label: 'Status', field: 'txStatus', align: 'left' },
  { name: 'externalReference', label: 'Phone', field: 'externalReference', align: 'left' },
  { name: 'riskScore', label: 'Risk', field: 'riskScore', align: 'right' },
  { name: 'createdDate', label: 'Created', field: 'createdDate', align: 'left',
    format: (val) => val ? new Date(val).toLocaleString() : '' },
]

async function search() {
  loading.value = true
  try {
    const params = {}
    if (filters.transactionId) params.transactionId = filters.transactionId
    if (filters.traceId) params.traceId = filters.traceId
    if (filters.externalReference) params.externalReference = filters.externalReference
    const response = await adminApi.searchTransactions(params)
    rows.value = response.data.content ?? []
  } catch (err) {
    $q.notify({ type: 'negative', message: 'Search failed: ' + (err.message ?? 'Unknown error') })
  } finally {
    loading.value = false
  }
}

function clearFilters() {
  filters.transactionId = ''
  filters.traceId = ''
  filters.externalReference = ''
  rows.value = []
}

function onRowClick(evt, row) {
  router.push(`/admin/transactions/${row.transactionId}/events`)
}
</script>
