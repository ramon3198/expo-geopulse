import { NativeModule, requireNativeModule } from 'expo';

import type {
  GeoPulseConfig,
  GeoPulseState,
  Location,
  CurrentPositionOptions,
  Geofence,
  PermissionStatus,
  GeoPulseEvents,
  Trip,
  SyncOptions,
  SyncResult,
} from './ExpoGeopulse.types';

/**
 * Low-level binding to the native `ExpoGeopulse` module.
 *
 * Most apps should use the high-level {@link GeoPulse} singleton instead
 * (the default export of the package). This is exported as `GeoPulseNativeModule`
 * for advanced use (e.g. `useEvent(GeoPulseNativeModule, 'onLocation')`).
 *
 * The full method surface is declared up front; methods belonging to a
 * not-yet-shipped milestone reject with a `NOT_IMPLEMENTED` coded error.
 */
declare class ExpoGeopulseModule extends NativeModule<GeoPulseEvents> {
  // lifecycle / tracking
  ready(config: GeoPulseConfig): Promise<GeoPulseState>;
  setConfig(config: Partial<GeoPulseConfig>): Promise<GeoPulseState>;
  start(): Promise<GeoPulseState>;
  stop(): Promise<GeoPulseState>;
  getState(): Promise<GeoPulseState>;
  getCurrentPosition(options: CurrentPositionOptions): Promise<Location>;

  // permissions
  requestPermissions(): Promise<PermissionStatus>;
  getProviderState(): Promise<PermissionStatus>;
  requestEnableLocation(): Promise<boolean>;
  requestBackgroundPermission(): Promise<PermissionStatus>;
  openAppSettings(): Promise<void>;

  // battery / doze
  isIgnoringBatteryOptimizations(): Promise<boolean>;
  requestIgnoreBatteryOptimizations(): Promise<boolean>;

  // geofences (M6)
  addGeofence(geofence: Geofence): Promise<void>;
  addGeofences(geofences: Geofence[]): Promise<void>;
  removeGeofence(identifier: string): Promise<void>;
  removeGeofences(): Promise<void>;
  getGeofences(): Promise<Geofence[]>;

  // trip & visit
  getActiveTrip(): Promise<Trip | null>;

  // persistence + sync (M5)
  getLocations(limit: number): Promise<Location[]>;
  getCount(): Promise<number>;
  destroyLocations(): Promise<void>;
  sync(options: SyncOptions): Promise<SyncResult>;
  setAuthHeaders(headers: Record<string, string>): Promise<void>;

  // odometer
  getOdometer(): Promise<number>;
  setOdometer(value: number): Promise<Location | null>;

  // debug — emits a synthetic `onLocation` to validate the JS event pipeline
  emitTestLocation(): void;
  // testing — inject a fix through the full pipeline (fusion, trips, geofences)
  simulateLocation(location: {
    latitude: number;
    longitude: number;
    accuracy?: number;
    speed?: number;
    timestamp?: number;
  }): Promise<void>;
  // testing — directly run the registered headless task (validates the headless
  // wiring without having to kill the app)
  simulateHeadless(): Promise<boolean>;
  // testing (debug-only) — force the GMS-free LocationManager fallback (P2-9)
  simulateProviderFailure(provider: 'gms'): Promise<void>;
  // testing (debug-only) — simulate a signal outage of `durationMs` (P2-9)
  simulateOutage(durationMs: number): Promise<void>;
}

export default requireNativeModule<ExpoGeopulseModule>('ExpoGeopulse');
