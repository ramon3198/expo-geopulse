import { registerRootComponent } from 'expo';
import GeoPulse from 'expo-geopulse';

import Root from './Root';

// --- Headless JS proof ---------------------------------------------------------
// Registered at the top level so it also runs when the OS spawns a headless JS
// context after the app is killed. It re-posts each location to the backend under
// a DISTINCT device id, so the dashboard's "headless-proof" device only grows
// while the app is closed — unambiguous evidence the JS task ran with no UI alive.
// (The native pipeline keeps syncing under the normal device id either way.)
const HEADLESS_PROOF_URL =
  (process.env.EXPO_PUBLIC_API_URL ?? 'https://your-server.example.com') + '/locations';
GeoPulse.registerHeadlessTask(async ({ event, data }) => {
  if (event !== 'onLocation') return;
  try {
    await fetch(HEADLESS_PROOF_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'x-device-id': 'headless-proof' },
      body: JSON.stringify(data),
    });
  } catch {
    // best-effort; nothing to do in a headless context
  }
});

// registerRootComponent calls AppRegistry.registerComponent('main', () => Root);
// It also ensures that whether you load the app in Expo Go or in a native build,
// the environment is set up appropriately
registerRootComponent(Root);
