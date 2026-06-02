# expo-geopulse

> Open-source background geolocation SDK for **React Native + Expo** (Android), with **C++/NDK Kalman sensor fusion**, battery-smart motion detection, geofencing, and offline sync.

[![CI](https://github.com/ramon3198/expo-geopulse/actions/workflows/ci.yml/badge.svg)](https://github.com/ramon3198/expo-geopulse/actions/workflows/ci.yml)
[![npm](https://img.shields.io/npm/v/expo-geopulse.svg)](https://www.npmjs.com/package/expo-geopulse)
[![license: MIT](https://img.shields.io/badge/license-MIT-green.svg)](./LICENSE)
[![platform: Android](https://img.shields.io/badge/platform-Android-3ddc84.svg)](#)
[![New Architecture](https://img.shields.io/badge/React%20Native-New%20Architecture-61dafb.svg)](#)

GeoPulse is a free, fully open-source alternative to commercial background-geolocation
libraries. It is built on the **Expo Modules API** (Kotlin-first), targets React Native's
**New Architecture**, and drops into an Expo app with a single config-plugin line.

> **Status:** Android only (by design, for now). iOS is not yet implemented.

## Screenshots

The example app (dark & light themes, animated droplet theme reveal) and the
companion real-time dashboard:

| Example app (dark) | Example app (light) | Permissions & tools |
|:---:|:---:|:---:|
| <img src="docs/screenshots/app-dark.png" width="220" alt="App dark theme" /> | <img src="docs/screenshots/app-light.png" width="220" alt="App light theme" /> | <img src="docs/screenshots/app-permissions.png" width="220" alt="Permissions and tools" /> |

**Companion dashboard** — live map (MapLibre), route trace, visits, trips and driving events:

<img src="docs/screenshots/dashboard-phone.png" width="320" alt="Companion dashboard" />

---

## Why GeoPulse?

| | GeoPulse | Typical commercial SDK |
|---|---|---|
| License | **MIT, 100% open source** | Proprietary, paid for release builds |
| Source visibility | **Full native source** | Closed native binaries |
| Accuracy | **Kalman fusion in C++/NDK** (jitter ↓, outliers rejected) | Raw fixes, no smoothing |
| Polygon geofencing | **Built-in, free** | Often a paid add-on |
| GMS-free devices | **FusedLocation → LocationManager fallback** | GMS-only |
| Battery | **Stationary detection stops GPS** (Activity Recognition + significant-motion) | Varies |
| Expo integration | **One-line config plugin** | Manual setup |

---

## Features

- Continuous background tracking via a **foreground service** (`foregroundServiceType="location"`, Android 14/15 ready)
- **Fused location** (Google Play Services) with automatic **GMS-free fallback** (`LocationManager.FUSED_PROVIDER`)
- **Kalman sensor fusion in C++/NDK** — smooths GPS jitter, rejects outliers, tightens accuracy (≈80% RMSE reduction when stationary, ≈47% while walking in our synthetic-track tests)
- **Battery intelligence** — Activity Recognition + significant-motion sensor stop GPS while still, resume on movement
- **Adaptive accuracy presets** (`eco` / `standard` / `high`) with optional **auto-degrade on low battery**
- **Reliability signals** — mock-location (spoofing) detection, a per-fix **confidence score (0–100)**, and **signal-outage events**
- **Trip & visit detection** (on-device stay-point algorithm) — turns raw points into `onVisit` (arrive/depart with dwell) and `onTrip` (with real travelled distance) events
- **Driving-behaviour events** (telematics-style, on-device) — harsh braking, harsh acceleration, speeding and idling from the accelerometer + GPS, with a severity score
- **Geofencing** — circular *and* polygon geofences, "infinite" geofences (nearest-100 reconciliation), ENTER / EXIT / DWELL
- **Offline persistence** (SQLite) + **batched HTTP sync** with retry/backoff (WorkManager)
- **Restart on boot** (persisted config) and a headless-safe data pipeline (buffer + sync without a JS runtime)
- **TypeScript-first API** with a one-line **Expo config plugin** that wires up all permissions

---

## Requirements

- **Expo SDK 53+** / React Native **0.76+** (New Architecture — the default today)
- **Android only** (minSdk 24, targets Android 15 / API 35)
- A **development build** (Dev Client) or a bare RN app — **GeoPulse cannot run in Expo Go** because it ships custom native code.

---

## Installation

```bash
npm install expo-geopulse
```

Add the config plugin in `app.json` (this injects the required permissions; the foreground service merges automatically):

```json
{
  "expo": {
    "plugins": [
      ["expo-geopulse", { "requestBackgroundLocation": true }]
    ]
  }
}
```

Then generate native code and run a development build:

```bash
npx expo prebuild --platform android
npx expo run:android
```

> **Building the bundled `example/` in release mode (repo only):** the example
> resolves `expo-geopulse` from the parent dir via Metro's `extraNodeModules`,
> which works for debug but not for the release JS bundler. If you build a
> *release* APK of the example, first link the package into the example so the
> bundler can resolve it:
> ```bash
> # from expo-geopulse/example
> cmd /c mklink /J node_modules\expo-geopulse ..   # Windows (junction)
> # ln -s .. node_modules/expo-geopulse            # macOS/Linux
> ```
> This is only needed for the in-repo example; consumers installing from npm
> don't need it.

### Config plugin options

| Option | Default | Description |
|---|---|---|
| `requestBackgroundLocation` | `false` | Adds `ACCESS_BACKGROUND_LOCATION` ("Allow all the time"). Triggers extra Play Store review. |
| `enableActivityRecognition` | `true` | Adds `ACTIVITY_RECOGNITION` for motion-based battery savings. |
| `allowBatteryOptimizationExemption` | `false` | Adds `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. Use sparingly. |

The foreground service, boot receiver and core permissions are merged automatically — so GeoPulse also works in **bare React Native** without the plugin.

---

## Quickstart

**One line to start tracking** — `track()` requests permissions, turns on GPS,
configures, and starts the background service for you:

```ts
import GeoPulse from 'expo-geopulse';

const tracker = await GeoPulse.track((location) => {
  console.log(location.coords.latitude, location.coords.longitude);
});

// ...later
await tracker.stop();
```

Tune it with friendly options (no enums needed):

```ts
const tracker = await GeoPulse.track(onLocation, {
  mode: 'high',                 // 'eco' | 'balanced' | 'high'
  background: true,             // "Allow all the time"
  trips: true,                  // emit onVisit / onTrip
  driving: true,                // emit harsh-braking / speeding / ...
  url: 'https://api.example.com/locations',  // auto-upload
  notification: { title: 'Tracking', text: 'Recording your route' },
});
```

Subscribe to anything with a single `on(...)`, and handle errors by `code`:

```ts
const sub = GeoPulse.on('trip', (e) => console.log(e.action, e.trip.distanceMeters));
GeoPulse.on('driving', (e) => console.log(e.type, e.severity));
sub.remove();

try {
  await GeoPulse.track(onLocation);
} catch (e) {
  if (e.code === 'PERMISSION_DENIED') promptUser();
  if (e.code === 'LOCATION_OFF') askToEnableGps();
}

// A single fresh fix:
const here = await GeoPulse.currentPosition();
```

<details>
<summary>Prefer fine-grained control? The full API is still there.</summary>

```ts
import GeoPulse, { Accuracy } from 'expo-geopulse';

await GeoPulse.ready({ desiredAccuracy: Accuracy.High, distanceFilter: 10, enableKalman: true });
await GeoPulse.requestPermissions();
const sub = GeoPulse.onLocation((loc) => console.log(loc.coords));
await GeoPulse.start();
// ...
await GeoPulse.stop();
sub.remove();
```
</details>

---

## API

### High-level (recommended)
The "just works" layer — most apps only need these:
- `track(onLocation, options?): Promise<Tracker>` — request permissions + turn on GPS + configure + start, in one call. Returns `{ stop() }`. Throws coded errors (`PERMISSION_DENIED` / `LOCATION_OFF`).
- `ensurePermissions({ background? }): Promise<PermissionResult>` — run the full foreground → GPS → background flow; returns `{ granted, reason, ... }`.
- `on(event, cb): EventSubscription` — one typed subscriber for every event: `'location' | 'motion' | 'activity' | 'geofence' | 'provider' | 'heartbeat' | 'error' | 'visit' | 'trip' | 'driving'`.
- `currentPosition(): Promise<Location>` — a single fresh fix (alias of `getCurrentPosition`).
- `TrackOptions`: `mode` (`'eco' | 'balanced' | 'high'`) · `background` · `trips` · `driving` · `url` · `headers` · `notification` · `distanceFilter`.

### Lifecycle & tracking (low-level)
- `ready(config?): Promise<GeoPulseState>` — apply config; call once before `start()`
- `setConfig(config): Promise<GeoPulseState>` — update config while running
- `start(): Promise<GeoPulseState>` / `stop(): Promise<GeoPulseState>`
- `getState(): Promise<GeoPulseState>`
- `getCurrentPosition(options?): Promise<Location>` — single fresh fix

### Permissions & battery
- `requestPermissions(): Promise<PermissionStatus>`
- `getProviderState(): Promise<PermissionStatus>`
- `isIgnoringBatteryOptimizations(): Promise<boolean>`
- `requestIgnoreBatteryOptimizations(): Promise<boolean>`

### Geofences
- `addGeofence(geofence)` · `addGeofences(list)` · `removeGeofence(id)` · `removeGeofences()` · `getGeofences()`
- A geofence is a **circle** (`latitude`, `longitude`, `radius`) or a **polygon** (`vertices: [lat, lng][]`).

### Trips & visits
- Enable with `enableTripDetection: true` (tune via `visitRadius`, `minVisitDwell`).
- `getActiveTrip(): Promise<Trip | null>` — the trip currently in progress.
- Subscribe with `onVisit` (`{ action: 'arrive' | 'depart', visit }`) and `onTrip` (`{ action: 'start' | 'end', trip }`).

### Driving events
- Enable with `enableDrivingEvents: true` (tune `harshAccelThreshold`, `harshBrakeThreshold`, `speedLimit`, `idleTimeout`).
- Subscribe with `onDrivingEvent` — `{ type: 'harsh_braking' | 'harsh_acceleration' | 'speeding' | 'idling', severity, magnitude, speed, location }`.

### Persistence & sync
- `getLocations(): Promise<Location[]>` · `getCount(): Promise<number>` · `destroyLocations()` · `sync(): Promise<Location[]>`

### Testing
- `simulateLocation({ latitude, longitude, accuracy?, speed?, timestamp? })` — inject a fix through the full pipeline (fusion, geofences, trips/visits) to test from your desk without walking a route. Pass increasing `timestamp` values to simulate motion.

### Events (each returns an `EventSubscription`)
`onLocation` · `onMotionChange` · `onActivityChange` · `onGeofence` · `onProviderChange` · `onHeartbeat` · `onError` · `onVisit` · `onTrip` · `onDrivingEvent`

### Config highlights
`desiredAccuracy` (`Accuracy.High|Balanced|Low|Passive`) · `preset` (`eco|standard|high`) · `lowBatteryThreshold` · `distanceFilter` · `locationUpdateInterval` · `stopOnStationary` · `disableMockLocations` · `outageThreshold` · `enableTripDetection` / `visitRadius` / `minVisitDwell` · `enableKalman` · `accuracyFilter` · `startOnBoot` · `url` / `httpMethod` / `headers` / `params` / `autoSync` / `maxBatchSize` · `notification`.

---

## How it works

- **Foreground service** keeps tracking alive in the background and posts the required ongoing notification.
- **LocationEngine** prefers `FusedLocationProviderClient`; on GMS-free devices it falls back to `LocationManager`.
- **Kalman fusion (C++/NDK)** runs every fix through `libgeopulse-fusion.so`: accuracy gating, speed-based outlier rejection, and a scalar GPS Kalman filter. Emitted locations carry `filtered: true` and `provider: "kalman"`.
- **MotionManager** uses Activity Recognition transitions + the significant-motion sensor; when `stopOnStationary` is on, GPS is stopped while still and resumed on movement.
- **Offline pipeline**: every location is buffered in SQLite; a WorkManager job uploads batches to `url` with retry/backoff and deletes them on success.
- **Boot**: the last config is persisted; if `startOnBoot` is set, tracking resumes after a reboot without opening the app.

---

## Companion backend & dashboard (optional)

The repo ships an optional open-source real-time backend and dashboard so you can
*see* tracking on a live map — point the SDK's `url` at it and you're done.

- **`server/`** — a **FastAPI + WebSocket** backend (SQLite storage). Receives the
  SDK's batches, stores them, and streams every location/event to connected
  dashboards. See [server/README.md](server/README.md).
- **`dashboard/`** — a **React + Vite + MapLibre** dashboard (free OpenStreetMap
  tiles, no API key) that shows the device moving live, the route trace, and a
  feed of trips / visits / driving events.

```bash
# 1. Backend
cd server && pip install -r requirements.txt && uvicorn main:app --port 8787
# 2. Dashboard
cd dashboard && npm install && npm run dev
# 3. Test without a phone
cd server && python simulate.py        # watch the map move

# Or point the SDK at it:
# await GeoPulse.ready({ url: 'http://YOUR_PC_IP:8787/locations', autoSync: true })
```

---

## Headless JS task

Set `enableHeadless: true` and register a task at your app's entry point (top of
`index.js`, **outside any component**) to run JS for events even while the app is
killed — the foreground service keeps tracking and spawns a short-lived JS
context per event:

```ts
// index.js
import GeoPulse from 'expo-geopulse';

GeoPulse.registerHeadlessTask(async ({ event, data }) => {
  if (event === 'onLocation') {
    await fetch('https://api.example.com/loc', { method: 'POST', body: JSON.stringify(data) });
  }
});
```

Without it, the *data pipeline* (buffer + HTTP sync via the foreground service)
is still headless-safe on its own — locations are recorded and uploaded to your
`url` even when the app is killed; only custom JS callbacks need the headless task.

## Limitations

- **Android only** (for now).
- Requires a **development build** — not Expo Go.

## License

MIT © contributors. See [LICENSE](./LICENSE).
