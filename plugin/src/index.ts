import {
  AndroidConfig,
  type ConfigPlugin,
  createRunOncePlugin,
} from 'expo/config-plugins';

// Local declaration avoids needing @types/node just for this one require.
declare const require: (id: string) => unknown;
const pkg = require('../../package.json') as { name: string; version: string };

export interface GeoPulsePluginProps {
  /**
   * Request `ACCESS_BACKGROUND_LOCATION` ("Allow all the time"). This triggers
   * extra Google Play review, so it is opt-in. Default: `false`.
   */
  requestBackgroundLocation?: boolean;
  /**
   * Add `ACTIVITY_RECOGNITION` for motion-based battery savings. Default: `true`.
   */
  enableActivityRecognition?: boolean;
  /**
   * Add `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. Use sparingly — Play Store
   * restricts it to apps whose core function needs exact background execution.
   * Default: `false`.
   */
  allowBatteryOptimizationExemption?: boolean;
}

/**
 * Config plugin for `expo-geopulse`.
 *
 * The foreground service, boot receiver and core permissions are merged from the
 * library's own AndroidManifest automatically. This plugin only manages the
 * sensitive / opt-in permissions so consumers get a clean one-line setup:
 *
 * ```json
 * { "plugins": [["expo-geopulse", { "requestBackgroundLocation": true }]] }
 * ```
 */
const withGeoPulse: ConfigPlugin<GeoPulsePluginProps | void> = (config, props) => {
  const {
    requestBackgroundLocation = false,
    enableActivityRecognition = true,
    allowBatteryOptimizationExemption = false,
  } = props ?? {};

  const permissions: string[] = [
    'android.permission.ACCESS_FINE_LOCATION',
    'android.permission.ACCESS_COARSE_LOCATION',
    'android.permission.FOREGROUND_SERVICE',
    'android.permission.FOREGROUND_SERVICE_LOCATION',
    'android.permission.POST_NOTIFICATIONS',
    'android.permission.INTERNET',
    'android.permission.ACCESS_NETWORK_STATE',
    'android.permission.WAKE_LOCK',
    'android.permission.RECEIVE_BOOT_COMPLETED',
  ];

  if (requestBackgroundLocation) {
    permissions.push('android.permission.ACCESS_BACKGROUND_LOCATION');
  }
  if (enableActivityRecognition) {
    permissions.push('android.permission.ACTIVITY_RECOGNITION');
  }
  if (allowBatteryOptimizationExemption) {
    permissions.push('android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS');
  }

  return AndroidConfig.Permissions.withPermissions(config, permissions);
};

export default createRunOncePlugin(withGeoPulse, pkg.name, pkg.version);
