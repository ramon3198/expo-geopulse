# Changelog

## Unreleased

### Performance

- **Location callbacks now run on a dedicated background thread.** The native
  location stream was delivered on the main/UI looper, so the whole
  fusion → persistence → event pipeline ran on the main thread. It now runs on a
  dedicated `HandlerThread` (`geopulse-location`), torn down on `stop()`, keeping
  the app's main thread free. `lastLocation` is now `@Volatile` for safe
  cross-thread reads.

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
