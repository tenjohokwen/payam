<template>
  <q-page padding>
    <div class="text-h5 q-mb-md">Tenants</div>

    <q-card class="q-mb-md">
      <q-card-section>
        <div class="row items-center q-col-gutter-md">
          <div class="col-auto">
            <q-select
              v-model="statusFilter"
              :options="statusOptions"
              outlined
              dense
              emit-value
              map-options
              style="min-width: 200px"
            />
          </div>
          <div class="col-auto">
            <q-btn color="primary" label="Search" @click="onSearch" />
          </div>
        </div>
      </q-card-section>
    </q-card>

    <q-table
      :rows="rows"
      :columns="columns"
      row-key="tenantRef"
      :loading="loading"
      flat
      bordered
      :pagination="pagination"
      @request="onRequest"
      @row-click="onRowClick"
    >
      <template #body-cell-tenantStatus="props">
        <q-td :props="props">
          <q-chip
            :color="props.row.tenantStatus === 'ACTIVE' ? 'positive' : props.row.tenantStatus === 'SUSPENDED' ? 'negative' : 'grey'"
            text-color="white"
            dense
          >
            {{ props.row.tenantStatus }}
          </q-chip>
        </q-td>
      </template>

      <template #body-cell-createdAt="props">
        <q-td :props="props">
          {{ props.row.createdAt ? new Date(props.row.createdAt).toLocaleDateString() : '' }}
        </q-td>
      </template>

      <template #no-data>
        <div class="full-width column flex-center q-pa-lg">
          <div class="text-h6 q-mb-sm">No tenants found</div>
          <div class="text-body2 text-grey-6">
            No tenants match your current filter. Try changing the status filter or add a tenant via the API.
          </div>
        </div>
      </template>
    </q-table>
  </q-page>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useQuasar } from 'quasar'
import { adminApi } from 'src/api/admin.api.js'

const $q = useQuasar()
const router = useRouter()

const statusFilter = ref('ALL')

const statusOptions = [
  { label: 'All', value: 'ALL' },
  { label: 'Active', value: 'ACTIVE' },
  { label: 'Suspended', value: 'SUSPENDED' },
]

const loading = ref(false)
const rows = ref([])
const pagination = ref({ page: 1, rowsPerPage: 20, rowsNumber: 0 })

const columns = [
  { name: 'name', label: 'Tenant Name', field: 'name', align: 'left', sortable: false },
  { name: 'tenantRef', label: 'Ref', field: 'tenantRef', align: 'left', sortable: false },
  { name: 'email', label: 'Email', field: 'email', align: 'left', sortable: false },
  { name: 'tenantStatus', label: 'Status', field: 'tenantStatus', align: 'center', sortable: false },
  { name: 'createdAt', label: 'Created', field: 'createdAt', align: 'left', sortable: false },
]

async function onRequest({ pagination: p }) {
  loading.value = true
  try {
    const resp = await adminApi.listTenants({
      status: statusFilter.value === 'ALL' ? undefined : statusFilter.value,
      page: p.page - 1,
      size: p.rowsPerPage,
    })
    rows.value = resp.data.content
    pagination.value = { ...p, rowsNumber: resp.data.totalElements }
  } catch (err) {
    $q.notify({ type: 'negative', message: 'Failed to load tenants' })
  } finally {
    loading.value = false
  }
}

function onSearch() {
  onRequest({ pagination: { ...pagination.value, page: 1 } })
}

function onRowClick(evt, row) {
  router.push('/admin/tenants/' + row.tenantRef)
}

onMounted(() => {
  onRequest({ pagination: pagination.value })
})
</script>
