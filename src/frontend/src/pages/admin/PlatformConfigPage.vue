<template>
  <q-page padding>
    <div class="text-h5 q-mb-md">Platform Configuration</div>

    <q-inner-loading :showing="isLoading" />

    <div v-if="!isLoading">
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

onMounted(async () => {
  isLoading.value = true
  try {
    const resp = await adminApi.getPlatformConfig()
    configs.value = resp.data
    for (const config of resp.data) {
      editValues.value[config.provider] = config.platformMsisdn
    }
  } catch {
    $q.notify({ type: 'negative', message: 'Failed to load platform configuration' })
  } finally {
    isLoading.value = false
  }
})

async function saveProvider(provider) {
  savingProvider.value = provider
  try {
    const resp = await adminApi.updatePlatformConfig(provider, editValues.value[provider])
    const updated = resp.data
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
</script>
