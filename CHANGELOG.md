# Changelog

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
