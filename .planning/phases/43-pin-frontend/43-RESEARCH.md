# Phase 43: PIN Frontend - Research

**Researched:** 2026-04-18
**Domain:** Vue 3 + Quasar frontend — masked PIN input, lazy-reveal pattern with countdown timer
**Confidence:** HIGH

---

## Summary

Phase 43 is a pure frontend change: adding a masked PIN input field to each provider card in `PlatformConfigPage.vue` and to the Add Provider dialog. The pattern is a near-exact copy of the webhook secret reveal pattern already implemented in `TenantDetailPage.vue` (Phase 33), extended to support multiple providers simultaneously via per-provider keyed state.

The backend API is fully implemented (Phase 42). The UI-SPEC is approved and provides a complete interaction contract. No new npm packages are required — everything needed is already in the project (Quasar v2, Material Icons, axios).

The primary difference from the webhook secret pattern is that state is keyed per-provider (plain object maps) rather than scalar refs, and the countdown is 60 seconds (not 30). The dialog PIN field has no countdown timer — it is a simple type-toggle only.

**Primary recommendation:** Follow the TenantDetailPage.vue `toggleSecret` / `reMask` / `clearTimers` / `startAutoMask` pattern verbatim, lifted to per-provider keyed maps. Reuse every naming convention and structural pattern from that file.

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| PIN-06 | Admin sees a masked PIN input on each provider card in PlatformConfigPage.vue with Quasar eye-toggle | Implemented via `q-input` append slot pattern from TenantDetailPage.vue lines 139–156; per-provider keyed `pinValues` ref |
| PIN-07 | Eye icon calls GET /{provider}/pin, populates field, starts 60s countdown; auto-masks and clears state on expiry or on second eye-click | `togglePin(provider)` function mirrors `toggleSecret()` in TenantDetailPage.vue lines 407–420; countdown uses `setInterval`+`setTimeout` pair stored per-provider |
| PIN-08 | Save button submits MSISDN+PIN in one PUT; empty PIN preserves existing; placeholder text communicates this | `updatePlatformConfigFull` API method replaces `updatePlatformConfig`; `pin: pinValues[provider] \|\| undefined` omits empty string from JSON body |
| PIN-09 | Add Provider dialog has same masked PIN field with eye-toggle; no auto-mask timer | Dialog uses simple type-toggle only (`"password"` / `"text"`), no `setTimeout`/`setInterval`; `newProvider.pin` added to dialog state |
</phase_requirements>

---

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Quasar Framework | ^2.16.0 (installed) | UI components: `q-input`, `q-btn`, `q-notify` | Existing project choice — all provider cards already use Quasar |
| Vue 3 Composition API | ^3.5.22 (installed) | `ref`, `onMounted`, `onUnmounted` | Existing project pattern |
| axios (via boot/axios) | ^1.2.1 (installed) | HTTP calls to backend; response interceptor returns `response.data` directly | Existing project pattern |
| Material Icons | via @quasar/extras (installed) | `visibility` / `visibility_off` icons for eye-toggle | Existing project pattern (TenantDetailPage.vue lines 151–152) |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| None new | — | — | No new dependencies required |

**Installation:** None required. All dependencies already installed.

---

## Architecture Patterns

### File Inventory (files to modify)

```
src/frontend/src/
├── api/admin.api.js                           ← add 2 new methods
└── pages/admin/PlatformConfigPage.vue         ← extend template + script
```

No new files created. No router changes needed. No new components.

### Pattern 1: Per-Provider Keyed State (extends TenantDetailPage.vue scalar pattern)

**What:** TenantDetailPage uses scalar refs (`secret`, `unmasked`, `countdown`) for a single webhook secret. PlatformConfigPage must manage N providers simultaneously, so state is keyed by provider string.

**When to use:** Whenever the same reveal pattern applies to multiple independent items on a single page.

**Implementation:**

```javascript
// Reactive refs (Vue reactivity tracks property additions)
const pinValues = ref({})           // { ORANGE: '', MTN: '' }
const pinRevealed = ref({})         // { ORANGE: false, MTN: false }
const pinCountdown = ref({})        // { ORANGE: 0, MTN: 0 }

// Plain objects for timer handles (not reactive — just refs to cancel)
const pinTimers = {}                // { ORANGE: timeoutId }
const pinCountdownIntervals = {}    // { ORANGE: intervalId }
```

Plain objects (not refs) for timer handles is intentional — mirrors TenantDetailPage.vue `let maskTimer = null` / `let countdownInterval = null` pattern. Timer handles don't need reactivity.

### Pattern 2: clearPinTimers(provider) — per-provider timer cleanup

**What:** Clears both the setTimeout (auto-mask) and setInterval (countdown decrement) for a specific provider.

```javascript
// Source: adapted from TenantDetailPage.vue lines 387–392
function clearPinTimers(provider) {
  clearTimeout(pinTimers[provider])
  clearInterval(pinCountdownIntervals[provider])
  delete pinTimers[provider]
  delete pinCountdownIntervals[provider]
}
```

### Pattern 3: reMaskPin(provider) — unified re-mask (manual + auto)

**What:** Clears timers, wipes plaintext from state, resets to masked display. Used by both IC-03 (eye click) and IC-04 (countdown expiry).

```javascript
// Source: adapted from TenantDetailPage.vue lines 394–399 (reMask)
function reMaskPin(provider) {
  clearPinTimers(provider)
  pinValues.value[provider] = ''
  pinRevealed.value[provider] = false
  pinCountdown.value[provider] = 0
}
```

### Pattern 4: togglePin(provider) — reveal / re-mask dispatch

**What:** If currently revealed → call reMaskPin. If masked → call GET /{provider}/pin, on success populate field and start countdown.

```javascript
// Source: adapted from TenantDetailPage.vue lines 407–420 (toggleSecret)
async function togglePin(provider) {
  if (pinRevealed.value[provider]) {
    reMaskPin(provider)
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
```

### Pattern 5: startPinCountdown(provider) — 60-second countdown

```javascript
// Source: adapted from TenantDetailPage.vue lines 401–405 (startAutoMask), extended to 60s
function startPinCountdown(provider) {
  pinCountdown.value[provider] = 60
  pinCountdownIntervals[provider] = setInterval(() => {
    pinCountdown.value[provider]--
  }, 1000)
  pinTimers[provider] = setTimeout(() => {
    reMaskPin(provider)
  }, 60000)
}
```

### Pattern 6: saveProvider(provider) — switch to updatePlatformConfigFull

**What:** Replace `adminApi.updatePlatformConfig(provider, msisdn)` with `adminApi.updatePlatformConfigFull(provider, msisdn, pin)`. After success, call `reMaskPin(provider)` if currently revealed.

**Key detail:** `pinValues.value[provider] || undefined` converts empty string to `undefined` so axios omits it from the JSON body, triggering PIN-08 (preserve existing PIN) semantics on the backend.

### Pattern 7: q-input with append slot for eye-toggle

**What:** Quasar `q-input` does not use the built-in `type` toggle shortcut. Uses explicit `append` slot with a `q-btn` — same as TenantDetailPage.vue lines 146–155.

```html
<!-- Source: TenantDetailPage.vue lines 139–156, adapted for per-provider keying -->
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
      flat round dense
      :icon="pinRevealed[config.provider] ? 'visibility_off' : 'visibility'"
      :aria-label="pinRevealed[config.provider] ? 'Hide PIN' : 'Reveal PIN'"
      @click="togglePin(config.provider)"
    />
  </template>
</q-input>
<div v-if="pinRevealed[config.provider]" class="text-caption text-grey q-mt-xs">
  Auto-hides in {{ pinCountdown[config.provider] }}s
</div>
```

### Pattern 8: onUnmounted cleanup

```javascript
// Source: TenantDetailPage.vue line 435
onUnmounted(() => {
  // Clear all per-provider timers to prevent interval leaks on navigation
  Object.keys(pinTimers).forEach(provider => clearPinTimers(provider))
})
```

### Pattern 9: loadConfigs — initialize pinValues/pinRevealed from list response

The `GET /v1/admin/platform-config` list response now includes `pinConfigured: boolean` per provider (Phase 42). On load, initialize per-provider state:

```javascript
async function loadConfigs() {
  isLoading.value = true
  try {
    const resp = await adminApi.getPlatformConfig()
    configs.value = resp
    for (const config of resp) {
      editValues.value[config.provider] = config.platformMsisdn
      pinValues.value[config.provider] = ''      // empty on load (PIN-06)
      pinRevealed.value[config.provider] = false
      pinCountdown.value[config.provider] = 0
    }
  } catch {
    $q.notify({ type: 'negative', message: 'Failed to load platform configuration' })
  } finally {
    isLoading.value = false
  }
}
```

### Pattern 10: Dialog PIN field — type-toggle only, no countdown

```javascript
// newProvider state extended:
const newProvider = ref({ name: '', msisdn: '', pin: '' })
const dialogPinVisible = ref(false)  // simple type toggle, no timer
```

```html
<!-- Dialog PIN field — same q-input pattern, no countdown div -->
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
      flat round dense
      :icon="dialogPinVisible ? 'visibility_off' : 'visibility'"
      :aria-label="dialogPinVisible ? 'Hide PIN' : 'Reveal PIN'"
      @click="dialogPinVisible = !dialogPinVisible"
    />
  </template>
</q-input>
```

On dialog close / add success: reset `newProvider.value = { name: '', msisdn: '', pin: '' }` and `dialogPinVisible.value = false`.

### Anti-Patterns to Avoid

- **Reactive refs for timer handles:** Do NOT wrap `pinTimers` or `pinCountdownIntervals` in `ref()`. Timer handles are plain values — adding reactivity is wasteful and not the established project pattern.
- **`v-model` on type attribute:** Do NOT use `v-model` to control `type="password"/"text"`. The `type` attribute is derived from `pinRevealed[provider]` — computed inline in the template as `:type`.
- **Sending empty string PIN in PUT body:** Must use `pinValues.value[provider] || undefined` to omit empty string. If you send `pin: ""`, the backend regex `^$|^[a-zA-Z0-9]{4,8}$` accepts it (empty string allowed), but the service-layer `StringUtils.isNotBlank()` guard preserves existing PIN. Either works, but `|| undefined` is cleaner (field omitted from JSON).
- **Changing `updatePlatformConfig`:** The existing method (admin.api.js line 59) is used by other callers. Do not modify it — add `updatePlatformConfigFull` as a new method.
- **Pre-fetching PIN on page load:** PIN-06 explicitly requires the field to be empty on page load. Do not call the reveal endpoint in `loadConfigs()`.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Eye icon toggle | Custom CSS show/hide trick | `q-btn` in `#append` slot | Already in codebase; screen reader accessible via `:aria-label` |
| Countdown display | Custom component | Inline `<div class="text-caption text-grey q-mt-xs">` | TenantDetailPage.vue line 157 pattern is 3 lines |
| Error notifications | Alert div | `$q.notify({ type: ... })` | Quasar notify is already the established pattern |
| API method body construction | Inline object spread | `updatePlatformConfigFull` method in admin.api.js | Keep body construction in API layer, not component |

---

## Common Pitfalls

### Pitfall 1: Interval Leak on Navigation
**What goes wrong:** User navigates away while a PIN countdown is running; setInterval keeps firing after component is destroyed.
**Why it happens:** Vue does not auto-clear timers on component unmount.
**How to avoid:** `onUnmounted()` hook clears all `pinTimers` and `pinCountdownIntervals` — mirrors TenantDetailPage.vue line 435.
**Warning signs:** Console errors after navigation; countdown ticks visible in devtools.

### Pitfall 2: Countdown Goes Negative
**What goes wrong:** `pinCountdown[provider]` decrements below zero if `reMaskPin` is not called promptly by the setTimeout.
**Why it happens:** `setInterval` fires at 1000ms; setTimeout fires at 60000ms; slight timing drift means interval may tick once more after mask.
**How to avoid:** `reMaskPin` calls `clearPinTimers` first (which calls `clearInterval`), then sets `pinCountdown[provider] = 0`. This is exactly the established pattern.
**Warning signs:** Countdown shows negative number briefly.

### Pitfall 3: Stale Plaintext After Save
**What goes wrong:** Admin saves config while PIN field shows revealed plaintext; field continues showing revealed state after save.
**Why it happens:** `saveProvider` does not re-mask on save success.
**How to avoid:** After a successful save, call `reMaskPin(provider)` if `pinRevealed.value[provider] === true` (per IC-05 step 4 in UI-SPEC).

### Pitfall 4: Vue Reactivity — New Keys on Plain Refs
**What goes wrong:** `pinValues.value[newProvider] = 'x'` does not trigger template re-render in Vue 2. In Vue 3 with Proxy-based reactivity, direct property assignment on a `ref({})` object IS reactive — no special handling needed.
**Why it happens:** This was a Vue 2 gotcha; Vue 3 Proxy tracks new property additions.
**How to avoid:** Direct assignment `pinValues.value[provider] = ''` works correctly in Vue 3. No `Vue.set()` or spread needed. Verified: project uses Vue 3.5.22.

### Pitfall 5: axios Interceptor Returns response.data
**What goes wrong:** Treating API responses as axios `{data, status}` objects.
**Why it happens:** The boot/axios.js interceptor (line 113–114) unwraps `response.data` before returning. So `resp` in `const resp = await adminApi.getPlatformConfigPin(provider)` IS the data object directly.
**How to avoid:** Access `resp.pin`, not `resp.data.pin`. Established pattern in TenantDetailPage.vue line 413 (`resp.webhookSecret`).

### Pitfall 6: 404 Error Shape
**What goes wrong:** Catching the 404 from `GET /{provider}/pin` and showing a generic error instead of the "No PIN configured" message.
**Why it happens:** axios rejects on non-2xx; the error object is `{ response: { status: 404, ... } }`.
**How to avoid:** In the catch block: `if (err.response?.status === 404)` → show warning; else → show negative notify.

---

## Code Examples

### admin.api.js additions

```javascript
// Source: UI-SPEC.md API Client Additions section
getPlatformConfigPin(provider) {
  return api.get(`/v1/admin/platform-config/${provider}/pin`)
},

updatePlatformConfigFull(provider, platformMsisdn, pin) {
  const body = { provider, platformMsisdn }
  if (pin !== undefined && pin !== '') body.pin = pin
  return api.put(`/v1/admin/platform-config/${provider}`, body)
},
```

### Save button call site (PlatformConfigPage.vue)

```javascript
// Replaces: await adminApi.updatePlatformConfig(provider, editValues.value[provider])
const updated = await adminApi.updatePlatformConfigFull(
  provider,
  editValues.value[provider],
  pinValues.value[provider] || undefined
)
// After success:
if (pinRevealed.value[provider]) {
  reMaskPin(provider)
}
$q.notify({ type: 'positive', message: `${provider} configuration saved` })
```

### addProvider() call site

```javascript
// Replaces: await adminApi.updatePlatformConfig(provider, msisdn)
const updated = await adminApi.updatePlatformConfigFull(
  provider,
  msisdn,
  newProvider.value.pin || undefined
)
// On close:
newProvider.value = { name: '', msisdn: '', pin: '' }
dialogPinVisible.value = false
showAddDialog.value = false
```

### Save button label per UI-SPEC Copywriting Contract

```html
<!-- CTA: "Save {{ config.provider }} Config" — e.g. "Save ORANGE Config" -->
<q-btn
  :label="`Save ${config.provider} Config`"
  color="primary"
  :loading="savingProvider === config.provider"
  @click="saveProvider(config.provider)"
/>
```

Note: The existing code uses `label="Save"` — this must be updated to match the UI-SPEC copywriting contract.

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `updatePlatformConfig(provider, msisdn)` | `updatePlatformConfigFull(provider, msisdn, pin)` | Phase 43 | Old method kept for non-PIN callers; new method used in saveProvider + addProvider |
| `label="Save"` on provider card CTA | `label="Save {PROVIDER} Config"` | Phase 43 | UI-SPEC copywriting contract |
| No PIN state in loadConfigs init | Initialize `pinValues[provider] = ''` etc. per provider | Phase 43 | Ensures per-provider state is always pre-seeded before template renders |

---

## Environment Availability

Step 2.6: SKIPPED (no external dependencies identified — phase is pure frontend code changes using already-installed packages).

---

## Validation Architecture

`workflow.nyquist_validation` key is absent from config.json — treated as enabled.

### Test Framework

| Property | Value |
|----------|-------|
| Framework | None installed — `package.json` `test` script is `echo "No test specified" && exit 0` |
| Config file | None |
| Quick run command | N/A |
| Full suite command | `cd src/frontend && npm run lint` (ESLint only) |

No JavaScript/Vue unit test framework is installed in this project. All frontend verification is manual (visual inspection in dev server).

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | Notes |
|--------|----------|-----------|-------------------|-------|
| PIN-06 | PIN input renders masked with eye icon on each provider card | manual | — | No test framework |
| PIN-07 | Eye click calls GET pin, populates field, starts 60s countdown, auto-masks on expiry | manual | — | Timer behavior is inherently interactive |
| PIN-08 | Save sends PUT with empty pin omitted; placeholder text visible | manual | — | |
| PIN-09 | Dialog PIN field renders with eye toggle, no countdown timer | manual | — | |

### Sampling Rate

- **Per task commit:** `cd /Users/mokwen/dev/gitrepos/bluegithub/payam/src/frontend && npm run lint` (ESLint — catches obvious syntax errors in .vue files)
- **Per wave merge:** Manual smoke test in dev server
- **Phase gate:** Manual verification against all 5 success criteria before `/gsd:verify-work`

### Wave 0 Gaps

None — no test infrastructure needed. ESLint is the only automated check and it is already configured.

---

## Open Questions

1. **Save button label update risk**
   - What we know: Existing label is `"Save"` (PlatformConfigPage.vue line 37); UI-SPEC requires `"Save {PROVIDER} Config"`
   - What's unclear: Whether any test or E2E check asserts on the exact label text
   - Recommendation: Check the test suite for any selector on `"Save"` button text; no tests found for frontend, so change is safe.

2. **`pinConfigured` field on list response**
   - What we know: Phase 42 added `pinConfigured: boolean` to the single-provider GET endpoint. The list endpoint (`GET /v1/admin/platform-config`) must also return `pinConfigured` for the placeholder to be dynamic.
   - What's unclear: Whether the list endpoint was updated in Phase 42 to include `pinConfigured` per item.
   - Recommendation: Plan should verify the list endpoint response shape (from Phase 42-03-PLAN.md) before writing the conditional placeholder logic. If `pinConfigured` is absent from list items, use a static placeholder `"Leave blank to keep existing PIN"` for all cards.

---

## Sources

### Primary (HIGH confidence)
- Direct file read: `src/frontend/src/pages/admin/PlatformConfigPage.vue` — current component state (154 lines)
- Direct file read: `src/frontend/src/pages/admin/TenantDetailPage.vue` — canonical reveal/timer pattern (lines 130–160, 380–436)
- Direct file read: `src/frontend/src/api/admin.api.js` — existing API client (132 lines)
- Direct file read: `src/frontend/src/boot/axios.js` — interceptor unwraps `response.data` (line 113–114)
- Direct file read: `.planning/phases/43-pin-frontend/43-UI-SPEC.md` — approved interaction contract
- Direct file read: `.planning/REQUIREMENTS.md` — PIN-06 through PIN-09 definitions
- Direct file read: `src/frontend/package.json` — dependency versions, no test framework installed

### Secondary (MEDIUM confidence)
- STATE.md accumulated context: Phase 33 clearTimers()/onUnmounted pattern, axios interceptor `resp.data.X` corrected to `resp.X`

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all packages verified from package.json
- Architecture patterns: HIGH — all patterns directly read from existing production code (TenantDetailPage.vue, PlatformConfigPage.vue)
- Pitfalls: HIGH — derived from actual code and established project context in STATE.md
- Open questions: LOW — one unresolved question about list endpoint `pinConfigured` field

**Research date:** 2026-04-18
**Valid until:** 2026-05-18 (stable — no external dependencies)
