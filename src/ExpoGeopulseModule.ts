import { NativeModule, requireNativeModule } from 'expo';

import type {
  GeoPulseConfig,
  GeoPulseState,
  Location,
  CurrentPositionOptions,
  Geofence,
  PermissionStatus,
  GeoPulseEvents,
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
  setConfig(config: GeoPulseConfig): Promise<GeoPulseState>;
  start(): Promise<GeoPulseState>;
  stop(): Promise<GeoPulseState>;
  getState(): Promise<GeoPulseState>;
  getCurrentPosition(options: CurrentPositionOptions): Promise<Location>;

  // permissions
  requestPermissions(): Promise<PermissionStatus>;
  getProviderState(): Promise<PermissionStatus>;

  // battery / doze
  isIgnoringBatteryOptimizations(): Promise<boolean>;
  requestIgnoreBatteryOptimizations(): Promise<boolean>;

  // geofences (M6)
  addGeofence(geofence: Geofence): Promise<void>;
  addGeofences(geofences: Geofence[]): Promise<void>;
  removeGeofence(identifier: string): Promise<void>;
  removeGeofences(): Promise<void>;
  getGeofences(): Promise<Geofence[]>;

  // persistence + sync (M5)
  getLocations(): Promise<Location[]>;
  getCount(): Promise<number>;
  destroyLocations(): Promise<void>;
  sync(): Promise<Location[]>;

  // odometer
  getOdometer(): Promise<number>;
  setOdometer(value: number): Promise<Location>;

  // debug — emits a synthetic `onLocation` to validate the JS event pipeline
  emitTestLocation(): void;
}

export default requireNativeModule<ExpoGeopulseModule>('ExpoGeopulse');
