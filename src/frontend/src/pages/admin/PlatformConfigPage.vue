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
          <q-input
            v-model="pinValues[config.provider]"
            :type="pinRevealed[config.provider] ? 'text' : 'password'"
            label="Provider PIN"
            outlined
            dense
            class="q-mb-sm"
            :placeholder="config.pinConfigured ? 'Leave blank to keep existing PIN' : 'Optional — 4-8 alphanumeric characters'"
          >
            <template #append>
              <q-btn
                flat
                round
                dense
                :icon="pinRevealed[config.provider] ? 'visibility_off' : 'visibility'"
                :aria-label="pinRevealed[config.provider] ? 'Hide PIN' : 'Reveal PIN'"
                @click="togglePin(config.provider)"
              />
            </template>
          </q-input>
          <div v-if="pinRevealed[config.provider]" class="text-caption text-grey q-mt-xs">
            Auto-hides in {{ pinCountdown[config.provider] }}s
          </div>
          <q-btn
            :label="`Save ${config.provider} Config`"
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
          <q-input
            v-model="newProvider.pin"
            :type="dialogPinVisible ? 'text' : 'password'"
            label="Provider PIN"
            outlined
            dense
            class="q-mb-sm"
            placeholder="Optional — 4-8 alphanumeric characters"
          >
            <template #append>
              <q-btn
                flat
                round
                dense
                :icon="dialogPinVisible ? 'visibility_off' : 'visibility'"
                :aria-label="dialogPinVisible ? 'Hide PIN' : 'Reveal PIN'"
                @click="dialogPinVisible = !dialogPinVisible"
              />
            </template>
          </q-input>
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
import { ref, onMounted, onUnmounted } from 'vue'
import { useQuasar } from 'quasar'
import { adminApi } from 'src/api/admin.api'

const $q = useQuasar()
const configs = ref([])
const isLoading = ref(false)
const savingProvider = ref(null)
const editValues = ref({})

// PIN reveal state (PIN-06, PIN-07) — keyed by provider
const pinValues = ref({})              // { ORANGE: '', MTN: '' } — v-model per card
const pinRevealed = ref({})            // { ORANGE: false, MTN: false } — controls type=password/text
const pinCountdown = ref({})           // { ORANGE: 0, MTN: 0 } — displayed seconds
const pinTimers = {}                   // setTimeout handles (plain object — not reactive)
const pinCountdownIntervals = {}       // setInterval handles (plain object — not reactive)

// Add Dialog State
const showAddDialog = ref(false)
const newProvider = ref({ name: '', msisdn: '', pin: '' })
const dialogPinVisible = ref(false)    // dialog eye-toggle — simple type flip, no timer (PIN-09)

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
      pinValues.value[config.provider] = ''          // PIN-06: empty on page load, do NOT pre-fetch
      pinRevealed.value[config.provider] = false
      pinCountdown.value[config.provider] = 0
    }
  } catch {
    $q.notify({ type: 'negative', message: 'Failed to load platform configuration' })
  } finally {
    isLoading.value = false
  }
}

// PIN reveal helpers (PIN-07) — mirror TenantDetailPage.vue pattern, keyed per provider
function clearPinTimers(provider) {
  clearTimeout(pinTimers[provider])
  clearInterval(pinCountdownIntervals[provider])
  delete pinTimers[provider]
  delete pinCountdownIntervals[provider]
}

function reMaskPin(provider) {
  clearPinTimers(provider)
  pinValues.value[provider] = ''
  pinRevealed.value[provider] = false
  pinCountdown.value[provider] = 0
}

function startPinCountdown(provider) {
  pinCountdown.value[provider] = 60
  pinCountdownIntervals[provider] = setInterval(() => {
    pinCountdown.value[provider]--
  }, 1000)
  pinTimers[provider] = setTimeout(() => {
    reMaskPin(provider)
  }, 60000)
}

async function togglePin(provider) {
  if (pinRevealed.value[provider]) {
    reMaskPin(provider)   // IC-03: second click immediately re-masks, no API call
    return
  }
  try {
    const resp = await adminApi.getPlatformConfigPin(provider)
    pinValues.value[provider] = resp.pin
    pinRevealed.value[provider] = true
    startPinCountdown(provider)
  } catch (err) {
    if (err.response?.status === 404) {
      $q.notify({ type: 'warning', message: `No PIN configured for ${provider}` })
    } else {
      $q.notify({ type: 'negative', message: 'Failed to retrieve PIN. Please try again.' })
    }
  }
}

async function saveProvider(provider) {
  savingProvider.value = provider
  try {
    const updated = await adminApi.updatePlatformConfigFull(
      provider,
      editValues.value[provider],
      pinValues.value[provider] || undefined     // empty string → undefined → omitted from JSON body (PIN-08)
    )
    const idx = configs.value.findIndex((c) => c.provider === provider)
    if (idx !== -1) {
      configs.value[idx] = updated
    }
    if (pinRevealed.value[provider]) {
      reMaskPin(provider)     // after save, clear any revealed plaintext
    }
    $q.notify({ type: 'positive', message: `${provider} configuration saved` })
  } catch (err) {
    if (err.response?.status === 400) {
      $q.notify({ type: 'negative', message: 'PIN must be 4–8 alphanumeric characters.' })
    } else {
      $q.notify({ type: 'negative', message: `Failed to save ${provider} configuration. Please try again.` })
    }
  } finally {
    savingProvider.value = null
  }
}

async function addProvider() {
  const provider = newProvider.value.name.toUpperCase()
  const msisdn = newProvider.value.msisdn
  const pin = newProvider.value.pin

  try {
    const updated = await adminApi.updatePlatformConfigFull(provider, msisdn, pin || undefined)

    const idx = configs.value.findIndex((c) => c.provider === provider)
    if (idx !== -1) {
      configs.value[idx] = updated
    } else {
      configs.value.push(updated)
    }

    editValues.value[provider] = msisdn
    pinValues.value[provider] = ''                // initialize new provider's PIN state
    pinRevealed.value[provider] = false
    pinCountdown.value[provider] = 0

    showAddDialog.value = false
    newProvider.value = { name: '', msisdn: '', pin: '' }
    dialogPinVisible.value = false

    $q.notify({ type: 'positive', message: `${provider} configuration added` })
  } catch (err) {
    if (err.response?.status === 400) {
      $q.notify({ type: 'negative', message: 'PIN must be 4–8 alphanumeric characters.' })
    } else {
      $q.notify({ type: 'negative', message: `Failed to add ${provider} configuration` })
    }
  }
}

onUnmounted(() => {
  // Prevent setInterval/setTimeout leaks on navigation (Pitfall 1 in RESEARCH.md)
  Object.keys(pinTimers).forEach((provider) => clearPinTimers(provider))
  Object.keys(pinCountdownIntervals).forEach((provider) => clearPinTimers(provider))
})
</script>
