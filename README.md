# expo-geopulse

> Open-source background geolocation SDK for **React Native + Expo** (Android), with **C++/NDK Kalman sensor fusion**, battery-smart motion detection, geofencing, and offline sync.

[![license: MIT](https://img.shields.io/badge/license-MIT-green.svg)](./LICENSE)
[![platform: Android](https://img.shields.io/badge/platform-Android-3ddc84.svg)](#)
[![New Architecture](https://img.shields.io/badge/React%20Native-New%20Architecture-61dafb.svg)](#)

GeoPulse is a free, fully open-source alternative to commercial background-geolocation
libraries. It is built on the **Expo Modules API** (Kotlin-first), targets React Native's
**New Architecture**, and drops into an Expo app with a single config-plugin line.

> **Status:** Android only (by design, for now). iOS is not yet implemented.

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

### Config plugin options

| Option | Default | Description |
|---|---|---|
| `requestBackgroundLocation` | `false` | Adds `ACCESS_BACKGROUND_LOCATION` ("Allow all the time"). Triggers extra Play Store review. |
| `enableActivityRecognition` | `true` | Adds `ACTIVITY_RECOGNITION` for motion-based battery savings. |
| `allowBatteryOptimizationExemption` | `false` | Adds `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. Use sparingly. |

The foreground service, boot receiver and core permissions are merged automatically — so GeoPulse also works in **bare React Native** without the plugin.

---

## Quickstart

```ts
import GeoPulse, { Accuracy } from 'expo-geopulse';

// 1. Configure
await GeoPulse.ready({
  desiredAccuracy: Accuracy.High,
  distanceFilter: 10,
  stopOnStationary: true,
  enableKalman: true,
  url: 'https://api.example.com/locations', // optional auto-upload endpoint
  autoSync: true,
  notification: { title: 'Tracking active', text: 'Recording your route' },
});

// 2. Ask for permissions (shows the system dialog)
await GeoPulse.requestPermissions();

// 3. Subscribe to events
const sub = GeoPulse.onLocation((location) => {
  console.log(location.coords.latitude, location.coords.longitude, location.provider);
});
GeoPulse.onMotionChange((e) => console.log('moving:', e.isMoving));
GeoPulse.onGeofence((e) => console.log(e.action, e.identifier));

// 4. Start / stop background tracking
await GeoPulse.start();
// ...
await GeoPulse.stop();
sub.remove();
```

---

## API

### Lifecycle & tracking
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

### Persistence & sync
- `getLocations(): Promise<Location[]>` · `getCount(): Promise<number>` · `destroyLocations()` · `sync(): Promise<Location[]>`

### Events (each returns an `EventSubscription`)
`onLocation` · `onMotionChange` · `onActivityChange` · `onGeofence` · `onProviderChange` · `onHeartbeat` · `onError`

### Config highlights
`desiredAccuracy` (`Accuracy.High|Balanced|Low|Passive`) · `distanceFilter` · `locationUpdateInterval` · `stopOnStationary` · `stationaryRadius` · `enableKalman` · `accuracyFilter` · `startOnBoot` · `url` / `httpMethod` / `headers` / `params` / `autoSync` / `maxBatchSize` / `maxRecordsToPersist` · `notification`.

---

## How it works

- **Foreground service** keeps tracking alive in the background and posts the required ongoing notification.
- **LocationEngine** prefers `FusedLocationProviderClient`; on GMS-free devices it falls back to `LocationManager`.
- **Kalman fusion (C++/NDK)** runs every fix through `libgeopulse-fusion.so`: accuracy gating, speed-based outlier rejection, and a scalar GPS Kalman filter. Emitted locations carry `filtered: true` and `provider: "kalman"`.
- **MotionManager** uses Activity Recognition transitions + the significant-motion sensor; when `stopOnStationary` is on, GPS is stopped while still and resumed on movement.
- **Offline pipeline**: every location is buffered in SQLite; a WorkManager job uploads batches to `url` with retry/backoff and deletes them on success.
- **Boot**: the last config is persisted; if `startOnBoot` is set, tracking resumes after a reboot without opening the app.

---

## Limitations

- **Android only** (for now).
- Requires a **development build** — not Expo Go.
- A full **headless JS task** (running JS callbacks while the app is killed) is not yet implemented; the *data pipeline* (buffer + HTTP sync) is already headless-safe.

## License

MIT © contributors. See [LICENSE](./LICENSE).
