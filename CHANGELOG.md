# Changelog

## 0.1.0 — Unreleased

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
