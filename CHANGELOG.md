# Changelog

## Unreleased

### New features

- **Tracking sessions.** Every `start()` begins a new session: the SDK stamps a
  `sessionId` (UUID, stable across process restarts mid-run) on each location.
  The companion server groups points per session (`GET /sessions/{device}`,
  `GET /locations/{device}?session=…`; pre-session points stay reachable under
  `legacy`), and the dashboard now shows **one tracking run at a time** — the
  newest live (auto-rolling into new runs as they start), older ones as static
  history — instead of one ever-growing trace since the first day of use.
- **Dashboard redesigned.** Professional full-height sidebar (device + session
  pickers, stat tiles, speed sparkline, activity feed), floating map controls,
  refreshed dark/light themes.
- **Fixed-lag track smoothing (`smoothingLag`).** Each emitted/persisted point is
  re-estimated using up to `lag` future (and past) fixes before release —
  near-offline track quality at the cost of `lag` fixes of emission latency
  (host-tested: **~49% tighter RMSE** at lag 3 on a noisy straight track).
  Geofences, trips and driving detection always run on the live fix, never
  delayed; `stop()` flushes the held tail. Off by default.
- **GNSS signal-quality gating (`gnssQualityGating`).** The accuracy fed to the
  fusion filter is scaled by the live constellation health (satellites used +
  average C/N0), so urban-canyon and indoor fixes are trusted less even when the
  chip keeps reporting optimistic accuracy. Piggybacks on the GNSS engine the
  location request already keeps powered — no extra battery. Off by default.
- **Raw-track A/B overlay (`debugIncludeRaw`).** Every filtered location can
  carry the pre-filter chip coordinates as `raw {latitude, longitude, accuracy}`
  — the example app draws both tracks on the live map (solid = filtered,
  dashed = raw) so fusion settings like `enableCvKalman` can be validated in the
  field at a glance. Debug aid, off by default.

## 0.8.0

### New features

- **Constant-velocity Kalman model (`enableCvKalman`).** An opt-in upgrade of
  the fusion filter with a velocity state: it predicts through motion instead of
  trailing behind it, and fuses the GPS chip's doppler velocity (speed/bearing)
  — including a zero-speed pin that kills stationary wander. Host benchmarks at
  15 m/s with ±8 m noise: scalar 33.5 m RMSE → CV 8.1 m → **CV + doppler 2.2 m**
  (15×), while matching the scalar filter when parked. Off by default until
  field-validated; flip it with `enableCvKalman: true`.
- **Configurable accuracy floors** for high-precision sources: `minKalmanAccuracy`
  (default `1.0` m — guards against chips over-stating precision) can be lowered
  so a sub-meter source (RTK) keeps its real accuracy (host-tested: a ±0.2 m
  source reports 0.19 m with floor 0.1 vs 0.62 m with the default), and
  `defaultAccuracy` (default `30` m) controls what is assumed for the rare fix
  that reports no accuracy at all.
- **Activity-adaptive GPS fusion.** The Kalman filter now tunes itself from the
  detected activity: process noise drops to 0.5 m/s while `still` (host-tested
  **~51% tighter stationary RMSE** vs the one-size-fits-all 3.0) and rises to
  8 m/s `in_vehicle` so the filter stops lagging behind a car in turns. The
  outlier gate is activity-aware too (pedestrian 40 m/s — still catches
  multipath teleports — vs vehicle 100 m/s; generous on purpose, since activity
  recognition lags transitions by 10–60 s and must never reject real motion).
- **Accuracy-aware outlier gate (5σ).** A jump is now rejected only when it is
  both implausible given the two fixes' combined reported accuracy (beyond 5σ)
  *and* faster than the activity's max speed — previously a noisy pair of ±25 m
  fixes could be dropped for a 40 m apparent "jump" that was just noise the
  filter would have smoothed anyway.
- **Sync constraint options.** `syncOnWifiOnly` restricts uploads to unmetered
  networks (queued batches wait for Wi-Fi — protects capped mobile plans from
  multi-MB backlogs), and `syncRequiresBatteryNotLow` defers uploads while the
  battery is low. Both default off (current behavior).
- **Safety-net periodic drain.** While a sync `url` is configured, a 15-minute
  network-constrained periodic worker sweeps any backlog that has no pending
  one-shot work — `autoSync: false` setups, an enqueue lost to a crash, or
  points recorded offline before the process died. No-ops fast on an empty
  buffer; cancelled automatically when sync is unconfigured.
- **Expedited uploads on Android 12+.** Auto-sync work is marked expedited so
  Doze runs it within seconds instead of deferring it for minutes-to-hours
  (degrades gracefully to a normal request when the expedited quota is spent;
  skipped with `syncRequiresBatteryNotLow`, which expedited work doesn't allow).
- **Sync gives up loudly instead of retrying silently for hours.** After ~30 min
  of exponential backoff (6 attempts) against a failing backend, the worker
  emits `onError` `SYNC_ABANDONED` (`status` + `attempts`) and stops; points
  stay buffered and the next sync trigger — including the periodic drain —
  starts a fresh cycle.

### Fixes

- **The tail of a route now uploads after `stop()`.** Stopping tracking enqueues
  a final sync flush — previously the last buffered points sat in the queue
  until the app was next opened (auto-sync only fires per fix, and fixes had
  just stopped).
- **A backlog now drains after a reboot even with `startOnBoot: false`.** The
  boot receiver previously only acted when resuming tracking; un-synced points
  recorded before the reboot stayed orphaned until the app was manually opened.
- **HTTP 413 no longer discards the batch.** A too-large payload now halves the
  batch and retries immediately until it fits; only a single point that still
  413s is dropped (genuinely poisonous). Previously the whole batch was lost.
- **Transient connection blips retry in-line.** A request that got no HTTP
  response at all (DNS hiccup, socket reset) is retried once after 250 ms before
  falling back to WorkManager's 30 s+ backoff. Safe even after a read timeout —
  the dedup-by-uuid contract absorbs a re-sent batch.
- **Concurrent auth refreshes are coalesced.** A manual `sync()` racing the 401
  recovery now awaits the in-flight `registerAuthProvider` refresh instead of
  skipping it and uploading with the stale token; the provider is never invoked
  twice in parallel.
- **`track()` only sends the options you pass** instead of `undefined`
  placeholders that relied on the native merge to skip them.

## 0.7.0

### Breaking

- **`sync()` now resolves with a light `SyncResult` instead of the uploaded
  `Location[]`.** The old contract marshalled the entire uploaded batch across
  the JS bridge — up to 10k points (~8-10 MB) with `batchSync` — that most apps
  immediately discarded. It now resolves `{ count, discarded?, status? }`; pass
  `{ returnLocations: true }` to opt back into receiving the points.
  Migration: `(await sync()).length` → `(await sync()).count`.
- **A slow upload no longer blocks the other sync path.** The WorkManager drain
  and manual `sync()` still serialize their claim/delete steps, but the HTTP
  request itself runs outside the lock (previously a 30 s server stall held it
  for the whole request). If the two paths ever overlap on in-flight rows, the
  server-side dedup-by-`uuid` absorbs the duplicate — backends MUST dedup by
  `uuid` per the documented contract (the companion server always did).

### Performance

- **Battery reads are cached (30 s TTL).** The per-fix battery payload and the
  low-battery auto-degrade check each made 1–3 binder IPC calls to
  `BatteryManager` on every fix; they now share one cached read
  (`BatteryReader`), eliminating hours of redundant IPC on long tracking
  sessions. Worst case, a plug/unplug is noticed 30 s late — harmless to both.
- **SQLite buffer uses WAL + `synchronous=NORMAL`.** The sync drain (reads +
  deletes) no longer serializes against per-fix inserts on the rollback journal,
  and commits skip the per-insert fsync (still durable under WAL).
- **Dropped the redundant index on `id`** (schema v3 migration): `INTEGER
  PRIMARY KEY` *is* the rowid B-tree, so the extra index only taxed every
  insert. Buffered points survive the migration (host-tested).
- **Right-sized the gzip output buffer** (~25% of input instead of 50%),
  trimming allocation churn on large batch uploads.
- **Companion server ingests each batch in ONE transaction.** `insert_locations`
  replaces the per-point insert+commit (a full journal fsync per point) — order
  of magnitude faster ingestion for the SDK's batched uploads, same idempotent
  dedup and only-fresh-points broadcast semantics (host-tested).
- **Buffer row count is cached in memory.** `count()` was a full-table `COUNT(*)`
  called on every insert under `dropNewest` and on every fix when
  `autoSyncThreshold > 0`; the count is now seeded once and maintained by the
  store's mutators (exact: the idempotent-insert no-op case doesn't increment).
- **Upload body built in a single pre-sized pass.** The join-then-wrap string
  concatenation re-copied the whole payload several times (multi-MB transient
  strings for a large `batchSync` upload); now one `StringBuilder` of the right
  capacity.
- **Events stop crossing the JS bridge when nothing listens.** The module now
  tracks per-event listener presence (`OnStartObserving`/`OnStopObserving`) and
  skips serializing unobserved events — previously every fix's `onLocation`
  payload was marshalled to JS just to be dropped there. Observable behavior is
  identical; high-rate tracking with listener-less screens just stops paying for
  it.
- **The per-fix JS payload isn't built at all when nothing consumes it.** With no
  JS runtime attached, headless off and no sync `url`, the UUID + battery +
  nested-map construction is skipped entirely (odometer, geofences, trips and
  driving detection still run on the raw fix).

### Fixes

- **`detach()` is now seen promptly by the location worker** — the JS event
  dispatcher reference is `@Volatile`, so a torn-down runtime can't receive a
  stale dispatch from a concurrently-running fix.

## 0.6.0

### New features

- **Documented HTTP sync contract + status-code policy.** Sync now classifies each
  response: `2xx` deletes the batch; `400/413/422` (and any `discardStatusCodes`)
  **discard** it and emit `onError` `BATCH_REJECTED`; everything else
  (`401/403/408/429/5xx`, network errors, and any `retryStatusCodes`) **retries**
  with backoff. `Retry-After` on `429`/`503` is honored. A new **`onSyncError`**
  event reports `{ status, count }` for every failed attempt.
- **Runtime auth headers + refresh.** `GeoPulse.setAuthHeaders(headers)` and
  `GeoPulse.registerAuthProvider(() => Promise<headers>)` keep uploads
  authenticated: a `401` emits `AUTH_FAILED` and — when the app is alive — the SDK
  refreshes the token and re-syncs once (throttled) before backoff. Headers are
  persisted natively, so background/headless sync uses the last token even after
  the app is killed.
- **Buffer overflow policy.** When the buffer reaches `maxRecordsToPersist` (the
  sync cap, default 10000) a new `bufferOverflowPolicy` controls what gives:
  `dropOldest` (default, right for route tracking) or `dropNewest`. Either way an
  `onError` `BUFFER_OVERFLOW` fires with the `dropped` count (coalesced so a full
  buffer can't spam one event per fix). Uploads stay paginated to `maxBatchSize`
  per request, so a 10k backlog never becomes one giant payload.
- **Partial (foreground-only) location permission is a first-class state.**
  `getProviderState()` / `ensurePermissions()` now report a coarse `level`
  (`none` | `foregroundOnly` | `background`). Starting with `foregroundOnly` keeps
  tracking while the app is visible and emits `onError`
  `BACKGROUND_PERMISSION_MISSING` instead of silently assuming background — the
  consumer can continue degraded or guide the user to "Allow all the time".
- **Headless event coalescing.** With `enableHeadless`, `headlessCoalesceWindow`
  (seconds) and/or `headlessCoalesceCount` (fixes) batch `onLocation` deliveries to
  the headless task — the handler receives a `Location[]` once per window/count
  instead of spawning an ephemeral JS context per fix (battery saver on a highway
  with a low `distanceFilter`). Off by default; the SQLite sync pipeline still
  stores every fix individually. Batching logic is host-tested
  (`tools/host-test/HeadlessCoalescerTest.kt`).
- **Debug testing hooks.** `simulateProviderFailure('gms')` forces the GMS-free
  `LocationManager` fallback (so you can exercise it without a GMS-free device),
  and `simulateOutage(durationMs)` fires the `onProviderChange` outage/recovery
  events the signal-loss watchdog would. Both are debug-only aids documented in the
  Testing section.

### Fixes

- **Kalman filter resets when leaving a stationary stop.** With `stopOnStationary`,
  GPS is off while the device is still; on resume the first fix could sit far from
  the filter's stale pre-stop position and be smoothed against it (or rejected by
  the speed-outlier gate). The filter is now reset on the stationary→moving
  transition, so the first post-resume fix seeds a fresh state and is accepted
  cleanly — no false jump or gap. Covered by a new fusion host test.
- **Trip distance ignores stationary GPS gaps.** `Trip.distanceMeters` no longer
  adds the straight-line jump across a `stopOnStationary` stop (the first
  post-resume fix starts a fresh segment), so routes with long stops aren't
  over-counted. Documented how `distanceMeters` is computed; covered by a new
  `TripVisitManager` host test.

### Docs

- **Documented the idempotent-sync contract and the full HTTP contract** (body
  format, headers, status-code → action table, auth) for custom backends in the
  README. Every uploaded location carries a stable `uuid` (UUID v4, generated
  on-device at capture time) that is identical across retries, so a backend can
  dedup a re-sent batch (e.g. after a lost `2xx`) and never store duplicate points.

### Internal / quality

- **Companion server dedups via `UNIQUE(device, uuid)` + `ON CONFLICT DO NOTHING`**
  instead of a select-then-insert check — one statement, no race, and it models
  the contract above. `init_db` upgrades the old non-unique index in place
  (de-duping any pre-existing rows first; NULL-uuid rows are kept).
- **Sync logic centralized in `SyncEngine` / `SyncPolicy`** so the manual `sync()`
  and the WorkManager worker apply the exact same policy. `SyncPolicy` is pure and
  host-tested (`tools/host-test/SyncPolicyTest.kt`) alongside `GeoMath`.
- **Data-preserving SQLite migrations.** The buffer DB now versions its schema via
  `PRAGMA user_version` and runs incremental, **non-destructive** migrations on
  open (previously a version bump dropped the table, losing un-synced points). A
  failed migration rolls back instead of wiping and emits `onError`
  `DB_MIGRATION_FAILED`. Schema **v2** makes the local buffer idempotent on `uuid`
  (UNIQUE index + `INSERT OR IGNORE`, mirroring the server — the deferred half of
  the idempotency work). Migration logic is host-tested
  (`tools/host-test/migration_sql_test.py`), including the rollback-keeps-data path.
- **New `server` CI job** runs stdlib-only idempotency tests (`server/test_db.py`)
  for the dedup behavior, gating releases like the existing C++/Kotlin host tests.

## 0.5.1

### Fixes

- **Headless `onError` no longer dropped on a cold sync.** When WorkManager ran
  `SyncWorker` in a fresh process (app killed/rebooted) and the backend returned a
  terminal HTTP error, the emitted `onError` was discarded because the controller
  had a default config and no context. The worker now initializes the controller
  first, so the headless task receives the event.
- **`batchSync: true` now really uploads the whole backlog.** With unlimited
  persistence (`maxRecordsToPersist: 0`) the batch size was clamped to a single
  row, so `sync()` and the background worker uploaded one location at a time.
  Fixed in both the manual and WorkManager paths.

## 0.5.0

### Performance

- **Sync uploads are gzip-compressed** (`Content-Encoding: gzip`) above ~256 bytes,
  cutting upload size ~80–90% for batched locations — less data and battery. The
  companion server transparently decodes it.

### New features

- **`params` implemented.** Custom key/values are merged into the root of each
  sync request body (`{ "locations": [...], ...params }`) — e.g. an auth/device
  token. No longer a reserved no-op.
- **`batchSync` implemented.** `true` uploads the whole queued backlog in one
  request; `false` (default) keeps chunking by `maxBatchSize`.

### Internal / quality

- Extracted pure geo math into `GeoMath` (no Android deps) with a JVM unit-test
  suite, and added a **Kotlin CI job** that runs those tests + **ktlint**, so the
  kind of logic regressions found in review are caught automatically.

## 0.4.0

### New features

- **Headless JS task.** `GeoPulse.registerHeadlessTask(handler)` plus
  `enableHeadless: true` runs a registered JS task for events while the app is
  killed (a `GeoPulseHeadlessService` spawns a short-lived RN context per event).
  Previously only the native data pipeline was headless-safe; now custom JS can
  run too. Register the task at your app's entry point, outside any component.
  Verified on-device: the native HeadlessJsTaskService spins up a JS context and
  runs the registered task end-to-end. A `GeoPulseNativeModule.simulateHeadless()`
  debug helper dispatches the task on demand so you can test your handler without
  killing the app.
  - The headless service holds the wakelock only after the start is accepted, so
    a blocked start can't leak it forever.
  - `GeoPulseController.ensureInitialized(context)` restores the persisted config
    (and app context) in a cold process woken by a receiver/worker/sticky service,
    so headless events aren't lost in exactly the "app killed" case — wired into
    `GeofenceReceiver` and the service's `onStartCommand`.

## 0.3.0

A broad reliability/hardening release: a multi-subsystem audit plus the fixes
that built up since 0.2.1 (the 0.2.2 work was never published separately and is
included here).

### Hardening pass (multi-subsystem audit)

- **Fusion core rejects bad inputs.** A single NaN/Inf fix used to poison the
  Kalman state forever; duplicate or out-of-order timestamps collapsed the
  variance (false over-confidence) and bypassed the speed gate. The fusion core
  now drops non-finite inputs and any fix whose timestamp doesn't advance. The
  JNI bridge also handles an OOM array allocation instead of crashing.
- **`INTERNET` permission is now declared** (library manifest + config plugin).
  HTTP sync silently failed on any consumer that didn't already carry it.
- **Geofences keep reconciling after a process restart.** The reconciler is only
  built on the JS path, so the boot/restart tracking path never re-selected the
  nearest geofences. The location pipeline now drives it (lazily, only when
  geofences exist), and reconciliation runs serialized on one thread, awaiting
  the stale-removal before the add (no transient breach of the 100-geofence cap).
- **Polygon geofence EXIT events fire again.** The point-in-polygon refinement
  was applied to EXIT too, but a point leaving the bounding circle is outside the
  polygon by definition — so every polygon EXIT was dropped. EXIT now passes through.
- **Single SQLite store + serialized sync.** The controller and the WorkManager
  worker each opened their own `LocationStore`, so the per-method locks didn't
  mutually exclude them (SQLITE_BUSY, lost writes). The store is now a process
  singleton, and the manual `sync()` and the worker share a lock so they can't
  upload the same rows twice. Permanent HTTP 4xx no longer spins on backoff forever.
- **Battery auto-degrade now recovers.** Eco mode stuck for the whole session;
  it now restores the prior config once charging or back above the threshold
  (with hysteresis).
- **No hung `requestEnableLocation`.** A runtime teardown or a second call while
  the system dialog was open left the promise (and `track()`) pending forever.
- **`setOdometer` is implemented** (was a `NOT_IMPLEMENTED` stub).
- **`track({ distanceFilter })` is honored** instead of being overwritten by the
  preset.

### Concurrency fixes

- `@Volatile` on cross-thread state that lacked it: the outage-watchdog fields,
  `lastAndroidLocation`, and the driving detector's speed/brake trend (these were
  read on a different thread than they were written, risking stale reads or torn
  64-bit values). The motion and driving detectors now also fully reset on
  `stop()` and guard against a sensor callback that fires after stop.
- `launchService()` (config re-apply / battery degrade) is wrapped so a
  background `ForegroundServiceStartNotAllowedException` can't crash the worker —
  and now emits `onError(SERVICE_START_FAILED)` on failure. `start()` also flips
  `enabled` back to `false` when the launch is rejected, so the reported state is
  honest even at boot (where there's no JS dispatcher to receive the error). And
  when the service stops *itself* (e.g. it finds location permission was revoked),
  it now tells the controller, so `getState()` no longer reports tracking active
  with no service running.

### Trip accuracy

- Trips now end at the arrival moment/place (not ~`minVisitDwell` later), and the
  GPS jitter accumulated while parked is rolled back, so trip distance and
  duration aren't inflated.

### Bug fixes

- **Geofences survive a process restart.** The geofence registry lived only in
  memory, so after the app was killed (or rebooted) an OS-delivered transition
  hit a fresh process with an empty registry and was silently dropped. The
  registry (and the registered-id set) is now persisted and restored, so
  transitions are still refined and forwarded after a restart.
- **Geofence reconciliation now removes stale geofences.** As the device moved,
  reconcile registered the nearest 100 but never removed the ones that dropped
  out, so the OS-registered set could grow past the 100-geofence cap and start
  failing. It now removes geofences that leave the nearest set. The
  registered-set state (and `lastReg` position) is also recorded only after
  `addGeofences` actually succeeds — an async failure no longer leaves the SDK
  believing a failed registration succeeded, and emits an `onError`
  (`GEOFENCE_ERROR`) instead.
- **Large `maxBatchSize` no longer breaks sync deletes.** `deleteByIds` built one
  `IN (?)` placeholder per id; a `maxBatchSize` above SQLite's ~999 variable cap
  threw, so uploaded rows were never deleted (and re-uploaded forever). Deletes
  are now chunked.
- **Buffered locations sync after a process restart.** When WorkManager ran the
  sync worker in a fresh process (after an app kill or reboot), the in-memory
  config was still default (`url == null`), so the worker reported success and
  dropped the upload. It now falls back to the persisted config, so a backlog
  buffered before the restart still uploads.
- **GPS can't get stuck paused.** With stop-on-stationary disabled, a re-launch
  while flagged paused could leave GPS off (motion never resumes it in that mode).
  The service now always resumes GPS when stop-on-stationary is off.
- **Battery auto-degrade is now reversible.** Dropping to eco on low battery
  remembered nothing, so the original accuracy was lost. The pre-degrade config
  is now restored on the next `start()` (and superseded by an explicit
  `ready()` / `setConfig()`); auto-degrade re-applies if the battery is still low.
  `setConfig` also merges onto the pre-degrade config rather than the temporary
  eco one, so a partial update during a degrade can't freeze eco in place. All
  config + degrade-state transitions (`ready` / `setConfig` / `start` vs. the
  auto-degrade on the worker thread) are serialized under a lock, so they can't
  interleave into an inconsistent, unrecoverable state. `ready`/`setConfig`
  persist the exact config they set (captured under the lock), so a concurrent
  degrade can't cause the transient eco config to be saved and restored on the
  next launch.
- **`setConfig` now actually applies while tracking is running.** It updated the
  stored config but never reconfigured the live foreground service, so changing
  `preset` / intervals / `distanceFilter` left the real GPS request — and
  enabling/disabling `enableDrivingEvents` — on the value captured at `start()`.
  `setConfig` now re-launches the service to re-issue the location request and
  reconcile driving detection (honouring the stationary pause). Driving and
  motion detectors are torn down before re-creation, fixing a duplicate-detector
  leak on the battery auto-degrade restart too.
- **Fusion native handle is now race-free.** Rebuilding the Kalman engine (on a
  `setConfig` that changes accuracy) freed the native handle while the location
  worker thread could be mid-`process()` — a use-after-free / native crash. All
  native-handle access (create / process / reset / destroy) is now serialized
  under a single lock. The engine is also (re)built from the *current* config, so
  an in-flight fix carrying an older config snapshot can't recreate it with stale
  accuracy/Kalman settings.
- **`setConfig` no longer mutates the live config in place.** It merged onto the
  shared config object and returned the same instance, so the location worker
  thread could read a half-applied (or, for 64-bit fields, torn) config. Changes
  are now computed on a copy and published atomically via a `@Volatile`
  reference. The battery auto-degrade path got the same treatment.
- **No stray fix after `stop()`.** The location engine stops with `quitSafely()`,
  which can still deliver an already-queued fix; the service now drops fixes once
  tracking is disabled, so nothing is emitted or persisted after `stop()`.
- **Presets and manual tuning no longer fight.** `setConfig({ distanceFilter })`
  while a preset was active was silently overwritten by the preset. Hand-tuning a
  preset-controlled field now drops to manual mode so the change sticks;
  `setConfig({ preset })` still applies the preset as before.

### Performance

- **No stale fixes after a pause or live reconfigure.** `LocationEngine.stop()`
  drains already-queued fixes via `quitSafely()`, which fired the old callback
  after a stationary pause or a `setConfig`-driven re-launch (the `enabled` gate
  didn't catch these — tracking was still enabled). Each resume now tags its
  callback with a generation counter and ignores fixes from a superseded one.
- **Location callbacks now run on a dedicated background thread.** The native
  location stream was delivered on the main/UI looper, so the whole
  fusion → persistence → event pipeline ran on the main thread. It now runs on a
  dedicated `HandlerThread` (`geopulse-location`), torn down on `stop()`, keeping
  the app's main thread free. `lastLocation` is now `@Volatile` for safe
  cross-thread reads.
- **`SyncWorker` no longer applies retry backoff when it's just paging.** On
  hitting the per-run batch cap with rows still pending (but no upload error), it
  enqueued a `retry()`, triggering WorkManager's exponential backoff and slowing
  a large backlog. It now enqueues a fresh continuation (no backoff) and reports
  success; backoff stays reserved for real HTTP failures.

## 0.2.2

### Bug fixes

- **`setConfig` now merges instead of replacing.** Previously it overwrote the
  entire native config, so e.g. `setConfig({ preset: 'eco' })` silently wiped
  `url` / `autoSync` / trip & driving detection. Only the fields you pass are now
  applied; everything else is preserved.
- **MapView (dashboard):** fully re-apply state when the map finishes loading —
  not just the route/visits sources but the live marker, heading and
  `fitBounds`. A fix that arrived before `load` (or after a style switch) now
  places the marker and frames the track instead of being silently dropped.

### Others

- `ensurePermissions().granted` documented: it means foreground + GPS are ready
  (tracking can start); background ("Allow all the time") is reported separately.
- Dashboard lint is clean (typed WS events; refs updated in effects, not render).

## 0.2.0

High-level, "just works" developer experience (inspired by Python SDKs), layered
on top of the full API — nothing removed, everything backward-compatible.

### New features

- **`GeoPulse.track(onLocation, options?)`** — start tracking in one call: requests
  permissions, turns on GPS, configures, starts the foreground service and streams
  locations. Returns `{ stop() }`. Throws coded errors (`PERMISSION_DENIED` / `LOCATION_OFF`).
- **`GeoPulse.ensurePermissions({ background })`** — runs the full foreground → GPS →
  background permission flow and reports `{ granted, reason, ... }`.
- **`GeoPulse.on(event, cb)`** — one typed subscriber for every event
  (`'location' | 'motion' | 'activity' | 'geofence' | 'provider' | 'heartbeat' | 'error' | 'visit' | 'trip' | 'driving'`).
- **`GeoPulse.currentPosition()`** — short alias for `getCurrentPosition()`.
- **Friendly presets** — `mode: 'eco' | 'balanced' | 'high'` instead of the `Accuracy` enum.

## 0.1.0

First public preview. Android only.

### New features

- **Background tracking** via a foreground service (`foregroundServiceType="location"`, Android 14/15 ready).
- **Fused location** (Google Play Services) with automatic **GMS-free fallback** to `LocationManager`.
- **Kalman sensor fusion in C++/NDK** (`libgeopulse-fusion.so`): accuracy gating, speed-based outlier rejection, scalar GPS Kalman smoothing.
- **Battery intelligence**: Activity Recognition transitions + significant-motion sensor stop GPS while stationary and resume on movement.
- **Adaptive accuracy presets** (`eco` / `standard` / `high`) and optional auto-degrade to `eco` on low battery (`lowBatteryThreshold`).
- **Reliability signals**: mock-location (spoofing) detection (`isMock`, `MOCK_LOCATION` error, optional `disableMockLocations`), a per-fix `confidence` score (0–100) on locations and geofence events, and signal-outage events (`onProviderChange` with `outage` / `outageDuration`).
- **Trip & visit detection** (on-device stay-point algorithm): `onVisit` (arrive/depart with dwell time) and `onTrip` (start/end with real travelled distance) events, plus `getActiveTrip()`. Tunable via `enableTripDetection` / `visitRadius` / `minVisitDwell`.
- **Driving-behaviour events** (telematics-style, on-device from accelerometer + GPS): `onDrivingEvent` for `harsh_braking`, `harsh_acceleration`, `speeding` and `idling`, with a `severity` score. Tunable via `enableDrivingEvents` / `harshAccelThreshold` / `harshBrakeThreshold` / `speedLimit` / `idleTimeout`.
- **Geofencing**: circular and polygon geofences, nearest-100 reconciliation ("infinite" geofences), ENTER / EXIT / DWELL.
- **Offline persistence** (SQLite) and **batched HTTP sync** with retry/backoff via WorkManager.
- **Restart on boot** from persisted config; headless-safe buffer + sync pipeline.
- **Expo config plugin** that injects permissions; **TypeScript-first** public API with strongly-typed events.

### Performance

- `SyncWorker` now drains the whole backlog in one run (looping over batches) and reserves WorkManager's exponential backoff for real HTTP failures, instead of treating "more rows pending" as a retry.
- SQLite trimming runs periodically (every 50 inserts) rather than after every insert.
- `getLocations(limit?)` is capped (default 1000) and now returns the **most recent** locations (chronological), via a dedicated `getLatest()` query, rather than the oldest backlog. The sync worker keeps using FIFO order.

### Others

- Targets the React Native New Architecture (Expo SDK 53+ / RN 0.76+).
- C++ fusion core validated by a host unit test (≈80% RMSE reduction when stationary, ≈47% while walking).

### Known limitations

- Android only; iOS not yet implemented.
- Requires a development build (not Expo Go).
- Full headless **JS** task (JS callbacks while the app is killed) not yet implemented; the data pipeline is already headless-safe.
