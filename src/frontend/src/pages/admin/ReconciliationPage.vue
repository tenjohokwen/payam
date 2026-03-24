<template>
  <q-page padding>
    <div class="text-h5 q-mb-md">Reconciliation Reports</div>

    <q-table
      :rows="reports"
      :columns="reportColumns"
      row-key="id"
      :loading="loadingReports"
      @row-click="onReportClick"
      class="q-mb-lg"
    />

    <div v-if="selectedReport">
      <div class="text-h6 q-mb-sm">
        Discrepancies — {{ selectedReport.reportDate }} / {{ selectedReport.provider }}
        <q-btn flat dense icon="download" label="CSV" class="q-ml-sm" @click="exportReport('csv')" />
        <q-btn flat dense icon="download" label="JSON" class="q-ml-sm" @click="exportReport('json')" />
      </div>
      <q-table
        :rows="discrepancies"
        :columns="discrepancyColumns"
        row-key="id"
        :loading="loadingDiscrepancies"
      />
    </div>
  </q-page>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { adminApi } from 'src/api/admin.api.js'
import { useQuasar } from 'quasar'

const $q = useQuasar()
const reports = ref([])
const discrepancies = ref([])
const selectedReport = ref(null)
const loadingReports = ref(false)
const loadingDiscrepancies = ref(false)

const reportColumns = [
  { name: 'reportDate', label: 'Date', field: 'reportDate', align: 'left' },
  { name: 'provider', label: 'Provider', field: 'provider', align: 'left' },
  { name: 'totalChecked', label: 'Checked', field: 'totalChecked', align: 'right' },
  { name: 'totalMatched', label: 'Matched', field: 'totalMatched', align: 'right' },
  { name: 'totalDiscrepancies', label: 'Discrepancies', field: 'totalDiscrepancies', align: 'right' },
  { name: 'status', label: 'Status', field: 'status', align: 'left' },
  { name: 'runAt', label: 'Run At', field: 'runAt', align: 'left' },
]

const discrepancyColumns = [
  { name: 'payamTxId', label: 'Payam TX', field: 'payamTxId', align: 'left' },
  { name: 'providerRef', label: 'Provider Ref', field: 'providerRef', align: 'left' },
  { name: 'discrepancyType', label: 'Type', field: 'discrepancyType', align: 'left' },
  { name: 'severity', label: 'Severity', field: 'severity', align: 'left' },
  { name: 'payamStatus', label: 'Payam Status', field: 'payamStatus', align: 'left' },
  { name: 'providerStatus', label: 'Provider Status', field: 'providerStatus', align: 'left' },
  { name: 'payamAmount', label: 'Payam Amt', field: 'payamAmount', align: 'right' },
  { name: 'providerAmount', label: 'Provider Amt', field: 'providerAmount', align: 'right' },
]

async function loadReports() {
  loadingReports.value = true
  try {
    const resp = await adminApi.listReconciliationReports({ page: 0, size: 50 })
    reports.value = resp.data.content ?? []
  } catch {
    $q.notify({ type: 'negative', message: 'Failed to load reconciliation reports' })
  } finally {
    loadingReports.value = false
  }
}

async function onReportClick(_, row) {
  selectedReport.value = row
  loadingDiscrepancies.value = true
  try {
    const resp = await adminApi.getReconciliationDiscrepancies(row.id)
    discrepancies.value = resp.data
  } catch {
    $q.notify({ type: 'negative', message: 'Failed to load discrepancies' })
  } finally {
    loadingDiscrepancies.value = false
  }
}

async function exportReport(format) {
  if (!selectedReport.value) return
  try {
    const resp = await adminApi.exportReconciliationReport(selectedReport.value.id, format)
    const ext = format === 'json' ? 'json' : 'csv'
    const url = URL.createObjectURL(new Blob([resp.data]))
    const a = document.createElement('a')
    a.href = url
    a.download = `reconciliation-${selectedReport.value.reportDate}-${selectedReport.value.provider.toLowerCase()}.${ext}`
    a.click()
    URL.revokeObjectURL(url)
  } catch {
    $q.notify({ type: 'negative', message: 'Export failed' })
  }
}

onMounted(() => loadReports())
</script>
