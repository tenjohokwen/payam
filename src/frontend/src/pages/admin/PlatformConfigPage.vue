<template>
  <q-page padding>
    <div class="row items-center justify-between q-mb-md">
      <div class="text-h5">Platform Configuration</div>
      <q-btn
        label="Add Provider"
        color="secondary"
        icon="add"
        @click="showAddDialog = true"
      />
    </div>

    <q-inner-loading :showing="isLoading" />

    <div v-if="!isLoading">
      <div v-if="configs.length === 0" class="text-center q-pa-lg">
        <q-banner class="bg-grey-3 text-grey-8">
          No platform configurations found. Please add a provider configuration.
        </q-banner>
      </div>

      <q-card v-for="config in configs" :key="config.provider" class="q-mb-md">
        <q-card-section>
          <div class="text-h6 q-mb-sm">{{ config.provider }}</div>
          <q-input
            v-model="editValues[config.provider]"
            label="Platform MSISDN"
            outlined
            dense
            class="q-mb-sm"
          />
          <q-btn
            label="Save"
            color="primary"
            :loading="savingProvider === config.provider"
            @click="saveProvider(config.provider)"
          />
        </q-card-section>
      </q-card>
    </div>

    <!-- Add Provider Dialog -->
    <q-dialog v-model="showAddDialog">
      <q-card style="min-width: 350px">
        <q-card-section>
          <div class="text-h6">Add Provider</div>
        </q-card-section>

        <q-card-section class="q-pt-none">
          <q-select
            v-model="newProvider.name"
            :options="['ORANGE', 'MTN']"
            label="Provider"
            outlined
            dense
            use-input
            new-value-mode="add-unique"
            class="q-mb-sm"
          />
          <q-input
            v-model="newProvider.msisdn"
            label="Platform MSISDN"
            outlined
            dense
            class="q-mb-sm"
          />
        </q-card-section>

        <q-card-actions align="right" class="text-primary">
          <q-btn flat label="Cancel" v-close-popup />
          <q-btn flat label="Add" @click="addProvider" :disable="!newProvider.name || !newProvider.msisdn" />
        </q-card-actions>
      </q-card>
    </q-dialog>
  </q-page>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useQuasar } from 'quasar'
import { adminApi } from 'src/api/admin.api'

const $q = useQuasar()
const configs = ref([])
const isLoading = ref(false)
const savingProvider = ref(null)
const editValues = ref({})

// Add Dialog State
const showAddDialog = ref(false)
const newProvider = ref({ name: '', msisdn: '' })

onMounted(async () => {
  await loadConfigs()
})

async function loadConfigs() {
  isLoading.value = true
  try {
    const resp = await adminApi.getPlatformConfig()
    configs.value = resp
    for (const config of resp) {
      editValues.value[config.provider] = config.platformMsisdn
    }
  } catch {
    $q.notify({ type: 'negative', message: 'Failed to load platform configuration' })
  } finally {
    isLoading.value = false
  }
}

async function saveProvider(provider) {
  savingProvider.value = provider
  try {
    const updated = await adminApi.updatePlatformConfig(provider, editValues.value[provider])
    const idx = configs.value.findIndex((c) => c.provider === provider)
    if (idx !== -1) {
      configs.value[idx] = updated
    }
    $q.notify({ type: 'positive', message: `${provider} MSISDN updated successfully` })
  } catch {
    $q.notify({ type: 'negative', message: `Failed to update ${provider} MSISDN` })
  } finally {
    savingProvider.value = null
  }
}

async function addProvider() {
  const provider = newProvider.value.name.toUpperCase()
  const msisdn = newProvider.value.msisdn
  
  try {
    const updated = await adminApi.updatePlatformConfig(provider, msisdn)
    
    // Check if it already exists in our list
    const idx = configs.value.findIndex((c) => c.provider === provider)
    if (idx !== -1) {
      configs.value[idx] = updated
    } else {
      configs.value.push(updated)
    }
    
    editValues.value[provider] = msisdn
    showAddDialog.value = false
    newProvider.value = { name: '', msisdn: '' }
    
    $q.notify({ type: 'positive', message: `${provider} configuration added` })
  } catch {
    $q.notify({ type: 'negative', message: `Failed to add ${provider} configuration` })
  }
}
</script>
