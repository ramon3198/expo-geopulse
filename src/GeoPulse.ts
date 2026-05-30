import type { EventSubscription } from 'expo-modules-core';

import NativeModule from './ExpoGeopulseModule';
import type {
  GeoPulseConfig,
  GeoPulseState,
  Location,
  CurrentPositionOptions,
  Geofence,
  PermissionStatus,
  MotionChangeEvent,
  ActivityChangeEvent,
  GeofenceEvent,
  ProviderChangeEvent,
  HeartbeatEvent,
  GeoPulseError,
} from './ExpoGeopulse.types';

/**
 * High-level, promise-based facade over the native module.
 *
 * ```ts
 * import GeoPulse, { Accuracy } from 'expo-geopulse';
 *
 * await GeoPulse.ready({ desiredAccuracy: Accuracy.High, distanceFilter: 10 });
 * const sub = GeoPulse.onLocation((loc) => console.log(loc.coords));
 * await GeoPulse.start();
 * // ...later
 * sub.remove();
 * await GeoPulse.stop();
 * ```
 */
class GeoPulse {
  // ---- lifecycle / tracking ----

  /** Apply configuration and prepare the SDK. Call once before {@link start}. */
  ready(config: GeoPulseConfig = {}): Promise<GeoPulseState> {
    return NativeModule.ready(config);
  }

  /** Merge new configuration while running. */
  setConfig(config: GeoPulseConfig): Promise<GeoPulseState> {
    return NativeModule.setConfig(config);
  }

  /** Begin background tracking (starts the foreground service). */
  start(): Promise<GeoPulseState> {
    return NativeModule.start();
  }

  /** Stop background tracking. */
  stop(): Promise<GeoPulseState> {
    return NativeModule.stop();
  }

  /** Current SDK state snapshot. */
  getState(): Promise<GeoPulseState> {
    return NativeModule.getState();
  }

  /** Request a single, fresh location fix. */
  getCurrentPosition(options: CurrentPositionOptions = {}): Promise<Location> {
    return NativeModule.getCurrentPosition(options);
  }

  // ---- permissions ----

  requestPermissions(): Promise<PermissionStatus> {
    return NativeModule.requestPermissions();
  }

  getProviderState(): Promise<PermissionStatus> {
    return NativeModule.getProviderState();
  }

  /** Whether the app is exempt from Doze battery optimization. */
  isIgnoringBatteryOptimizations(): Promise<boolean> {
    return NativeModule.isIgnoringBatteryOptimizations();
  }

  /** Opens the system dialog asking the user to exempt the app from Doze. Resolves with the new state. */
  requestIgnoreBatteryOptimizations(): Promise<boolean> {
    return NativeModule.requestIgnoreBatteryOptimizations();
  }

  // ---- geofences ----

  addGeofence(geofence: Geofence): Promise<void> {
    return NativeModule.addGeofence(geofence);
  }

  addGeofences(geofences: Geofence[]): Promise<void> {
    return NativeModule.addGeofences(geofences);
  }

  removeGeofence(identifier: string): Promise<void> {
    return NativeModule.removeGeofence(identifier);
  }

  removeGeofences(): Promise<void> {
    return NativeModule.removeGeofences();
  }

  getGeofences(): Promise<Geofence[]> {
    return NativeModule.getGeofences();
  }

  // ---- persistence + sync ----

  getLocations(): Promise<Location[]> {
    return NativeModule.getLocations();
  }

  getCount(): Promise<number> {
    return NativeModule.getCount();
  }

  destroyLocations(): Promise<void> {
    return NativeModule.destroyLocations();
  }

  sync(): Promise<Location[]> {
    return NativeModule.sync();
  }

  // ---- odometer ----

  getOdometer(): Promise<number> {
    return NativeModule.getOdometer();
  }

  setOdometer(value: number): Promise<Location> {
    return NativeModule.setOdometer(value);
  }

  // ---- events ----

  onLocation(listener: (location: Location) => void): EventSubscription {
    return NativeModule.addListener('onLocation', listener);
  }

  onMotionChange(listener: (event: MotionChangeEvent) => void): EventSubscription {
    return NativeModule.addListener('onMotionChange', listener);
  }

  onActivityChange(listener: (event: ActivityChangeEvent) => void): EventSubscription {
    return NativeModule.addListener('onActivityChange', listener);
  }

  onGeofence(listener: (event: GeofenceEvent) => void): EventSubscription {
    return NativeModule.addListener('onGeofence', listener);
  }

  onProviderChange(listener: (event: ProviderChangeEvent) => void): EventSubscription {
    return NativeModule.addListener('onProviderChange', listener);
  }

  onHeartbeat(listener: (event: HeartbeatEvent) => void): EventSubscription {
    return NativeModule.addListener('onHeartbeat', listener);
  }

  onError(listener: (error: GeoPulseError) => void): EventSubscription {
    return NativeModule.addListener('onError', listener);
  }

  // ---- debug ----

  /** Emit a synthetic `onLocation` event. Useful to validate wiring end-to-end. */
  emitTestLocation(): void {
    NativeModule.emitTestLocation();
  }
}

export default new GeoPulse();
