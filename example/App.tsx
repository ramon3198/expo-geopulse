import GeoPulse, {
  Accuracy,
  GeoPulseNativeModule,
  type Location,
  type PermissionStatus,
} from 'expo-geopulse';
import { useEffect, useRef, useState } from 'react';
import {
  Pressable,
  SafeAreaView,
  ScrollView,
  StatusBar,
  StyleSheet,
  Switch,
  Text,
  View,
  type StyleProp,
  type ViewStyle,
} from 'react-native';
import Animated, {
  Easing,
  useAnimatedStyle,
  useSharedValue,
  withRepeat,
  withTiming,
} from 'react-native-reanimated';

import { MiniMap } from './MiniMap';
import { ThemeToggle } from './ThemeToggle';
import { useTheme } from './ThemeProvider';

// Configure these in example/.env.local (gitignored). EXPO_PUBLIC_* vars are
// inlined by Expo at build time; the defaults are placeholders so the public
// repo never points at a real backend.
const API = process.env.EXPO_PUBLIC_API_URL ?? 'https://your-server.example.com';
const DEVICE_ID = process.env.EXPO_PUBLIC_DEVICE_ID ?? 'demo-device';

type Mode = 'eco' | 'standard' | 'high';

export default function App() {
  const { theme } = useTheme();
  const [ready, setReady] = useState(false);
  const [enabled, setEnabled] = useState(false);
  const [status, setStatus] = useState('Starting…');
  const [last, setLast] = useState<Location | null>(null);
  const [perms, setPerms] = useState<PermissionStatus | null>(null);
  const [mode, setMode] = useState<Mode>('high');
  const [queued, setQueued] = useState(0);
  const [backendUp, setBackendUp] = useState<boolean | null>(null);
  const [log, setLog] = useState<string[]>([]);

  // Path for the mini-map, kept as [lng,lat] and capped for performance.
  const pathRef = useRef<Array<[number, number]>>([]);
  const [path, setPath] = useState<Array<[number, number]>>([]);

  const append = (line: string) =>
    setLog((p) => [`${new Date().toLocaleTimeString()}  ${line}`, ...p].slice(0, 40));

  const refreshPerms = async () => {
    try {
      const s = await GeoPulse.getProviderState();
      setPerms(s);
      return s;
    } catch {
      return null;
    }
  };

  useEffect(() => {
    const subs = [
      GeoPulse.onLocation((loc) => {
        setLast(loc);
        pathRef.current.push([loc.coords.longitude, loc.coords.latitude]);
        if (pathRef.current.length > 2000) pathRef.current.shift();
        append(
          `fix  ${loc.coords.latitude.toFixed(5)}, ${loc.coords.longitude.toFixed(5)}  ±${Math.round(
            loc.coords.accuracy
          )}m  ${loc.provider}`
        );
      }),
      GeoPulse.onError((e) => append(`error  ${e.code}: ${e.message}`)),
      GeoPulse.onDrivingEvent((e) =>
        append(`drive  ${e.type} (${e.severity}) ${e.magnitude.toFixed(1)}`)
      ),
      GeoPulse.onTrip((e) => append(`trip  ${e.action} · ${Math.round(e.trip.distanceMeters)} m`)),
      GeoPulse.onVisit((e) => append(`visit  ${e.action}`)),
    ];

    GeoPulse.ready({
      desiredAccuracy: Accuracy.High,
      preset: 'high',
      distanceFilter: 10,
      stopOnStationary: true,
      enableKalman: true,
      enableTripDetection: true,
      enableDrivingEvents: true,
      enableHeadless: true,
      url: `${API}/locations`,
      autoSync: true,
      autoSyncThreshold: 5,
      headers: { 'x-device-id': DEVICE_ID },
      notification: { title: 'GeoPulse', text: 'Recording your location in the background' },
    })
      .then((state) => {
        setReady(true);
        setEnabled(state.enabled);
        setStatus('Ready');
        refreshPerms();
      })
      .catch((e) => setStatus(`Init failed: ${String(e)}`));

    // Backend health.
    fetch(`${API}/health`)
      .then((r) => r.ok)
      .then(setBackendUp)
      .catch(() => setBackendUp(false));

    return () => subs.forEach((s) => s.remove());
  }, []);

  // Throttle map path updates (every ~2s) so frequent fixes don't thrash the WebView.
  useEffect(() => {
    const id = setInterval(() => {
      if (pathRef.current.length !== path.length) setPath([...pathRef.current]);
    }, 2000);
    return () => clearInterval(id);
  }, [path.length]);

  // Poll the local sync queue size while tracking.
  useEffect(() => {
    if (!enabled) return;
    const id = setInterval(async () => {
      try {
        setQueued(await GeoPulse.getCount());
      } catch {
        /* ignore */
      }
    }, 3000);
    return () => clearInterval(id);
  }, [enabled]);

  const handleStart = async () => {
    try {
      // The whole permission flow (foreground -> GPS -> background) in one call.
      setStatus('Checking permissions…');
      const perm = await GeoPulse.ensurePermissions({ background: true });
      await refreshPerms();
      if (!perm.granted) {
        const msg = perm.reason === 'location_off' ? 'Location is off' : 'Permission denied';
        setStatus(msg);
        append(`error  ${perm.reason}`);
        return;
      }
      const state = await GeoPulse.start();
      setEnabled(state.enabled);
      setStatus('Tracking');
      append('tracking started');
    } catch (e) {
      setStatus(`Start failed: ${String(e)}`);
    }
  };

  const handleStop = async () => {
    const state = await GeoPulse.stop();
    setEnabled(state.enabled);
    setStatus('Stopped');
    append('tracking stopped');
  };

  const changeMode = async (m: Mode) => {
    setMode(m);
    await GeoPulse.setConfig({ preset: m });
    append(`mode  ${m}`);
  };

  // Guided background-location flow. Step 1: ensure foreground. Step 2: request
  // background — if the OS won't grant it via dialog, send the user to Settings.
  const [bgAttempts, setBgAttempts] = useState(0);
  const handleBackground = async () => {
    try {
      let p = perms ?? (await GeoPulse.getProviderState());
      if (!p.fine && !p.coarse) {
        append('bg  requesting foreground first');
        p = await GeoPulse.requestPermissions();
        setPerms(p);
        if (!p.fine && !p.coarse) {
          append('error  foreground location denied');
          return;
        }
      }
      // After 1+ failed dialog attempts, jump straight to Settings.
      if (bgAttempts >= 1) {
        append('bg  opening app settings (choose "Allow all the time")');
        await GeoPulse.openAppSettings();
        return;
      }
      append('bg  requesting background');
      const result = await GeoPulse.requestBackgroundPermission();
      setPerms(result);
      setBgAttempts((n) => n + 1);
      if (result.background) {
        append('bg  granted — Allow all the time');
      } else {
        append('bg  not granted; use App settings next');
      }
    } catch (e) {
      append(`error  ${String(e)}`);
    }
  };

  const bgHint =
    bgAttempts === 0
      ? 'Tap below, then choose “Allow all the time”.'
      : 'If no dialog appears, open Settings → Permissions → Location → “Allow all the time”.';
  const bgBtnLabel = bgAttempts === 0 ? 'Allow all the time' : 'Open app settings';

  const speedKmh = last?.coords.speed != null ? (last.coords.speed * 3.6).toFixed(1) : '—';

  return (
    <SafeAreaView style={[styles.container, { backgroundColor: theme.bg }]}>
      <StatusBar
        barStyle={theme.name === 'dark' ? 'light-content' : 'dark-content'}
        backgroundColor={theme.bg}
      />
      <ScrollView contentContainerStyle={styles.content}>
        {/* App bar */}
        <View style={styles.appBar}>
          <View style={[styles.logo, { backgroundColor: theme.primary }]}>
            <Text style={styles.logoGlyph}>◉</Text>
          </View>
          <View>
            <Text style={[styles.brand, { color: theme.text }]}>GeoPulse</Text>
            <Text style={[styles.brandSub, { color: theme.textFaint }]}>SDK test console</Text>
          </View>
          <View style={styles.appBarActions}>
            <ThemeToggle />
          </View>
        </View>

        {/* Tracking switch — the hero control, Android-settings style */}
        <View
          style={[
            styles.card,
            styles.heroCard,
            { backgroundColor: theme.card, borderColor: theme.cardBorder, elevation: theme.elevation },
          ]}>
          <PulseDot active={enabled} />
          <View style={styles.heroTextWrap}>
            <Text style={[styles.heroTitle, { color: theme.text }]}>
              {enabled ? 'Tracking active' : 'Tracking off'}
            </Text>
            <Text style={[styles.heroSub, { color: theme.textDim }]} numberOfLines={1}>
              {status}
            </Text>
          </View>
          <Switch
            value={enabled}
            disabled={!ready}
            onValueChange={(v) => (v ? handleStart() : handleStop())}
            trackColor={{ false: theme.inner, true: theme.primary + '66' }}
            thumbColor={enabled ? theme.primary : theme.textFaint}
          />
        </View>

        {/* Live mini-map */}
        <MiniMap last={last} path={path} />

        {/* Live stats */}
        <View style={styles.statsRow}>
          <Stat label="Speed" value={speedKmh} unit="km/h" />
          <Stat label="Accuracy" value={last ? `±${Math.round(last.coords.accuracy)}` : '—'} unit="m" />
          <Stat label="Points" value={String(path.length)} />
          <Stat label="Queue" value={String(queued)} />
        </View>

        {/* Mode selector */}
        <Section title="Tracking mode">
          <View style={[styles.segment, { backgroundColor: theme.inner }]}>
            {(['eco', 'standard', 'high'] as Mode[]).map((m) => (
              <View key={m} style={styles.segClip}>
                <Pressable
                  android_ripple={{ color: theme.ripple }}
                  style={[styles.segItem, mode === m && { backgroundColor: theme.primary }]}
                  onPress={() => changeMode(m)}>
                  <Text
                    style={[
                      styles.segText,
                      { color: mode === m ? theme.primaryText : theme.textDim },
                    ]}>
                    {m}
                  </Text>
                </Pressable>
              </View>
            ))}
          </View>
        </Section>

        {/* Permissions */}
        <Section title="Permissions">
          <PermRow label="Location (fine)" ok={perms?.fine} />
          <PermRow label="Background location" ok={perms?.background} />
          <PermRow label="Notifications" ok={perms?.notifications} />
          <PermRow label="Activity recognition" ok={perms?.activityRecognition} />
          <PermRow label="Location services (GPS)" ok={perms?.locationServicesEnabled} last />

          {/* Background = "Allow all the time" — the key permission for tracking
              with the app closed. Guided, step-by-step. */}
          {perms && !perms.background && (
            <View
              style={[styles.banner, { backgroundColor: theme.toolBg, borderColor: theme.primary + '33' }]}>
              <Text style={[styles.bannerTitle, { color: theme.text }]}>
                Enable background tracking
              </Text>
              <Text style={[styles.bannerText, { color: theme.textDim }]}>
                To keep tracking when the app is closed, Android needs “Allow all the time”. {bgHint}
              </Text>
              <Button label={bgBtnLabel} onPress={handleBackground} />
            </View>
          )}
          {perms?.background && (
            <View style={[styles.okPill, { backgroundColor: theme.goodBg }]}>
              <Text style={[styles.okPillText, { color: theme.good }]}>
                ✓ Background tracking enabled
              </Text>
            </View>
          )}

          <View style={styles.toolRow}>
            <Tool label="Refresh" onPress={refreshPerms} />
            <Tool
              label="Turn on GPS"
              onPress={async () => {
                await GeoPulse.requestEnableLocation();
                await refreshPerms();
              }}
            />
            <Tool
              label="Unrestrict battery"
              onPress={async () => {
                const ok = await GeoPulse.requestIgnoreBatteryOptimizations();
                append(`battery  unrestricted=${ok}`);
              }}
            />
            <Tool label="App settings" onPress={() => GeoPulse.openAppSettings()} />
          </View>
        </Section>

        {/* Tools */}
        <Section
          title="Tools"
          trailing={
            <View style={[styles.statusChip, { backgroundColor: backendUp ? theme.goodBg : theme.badBg }]}>
              <View
                style={[styles.statusChipDot, { backgroundColor: backendUp ? theme.good : theme.bad }]}
              />
              <Text style={[styles.statusChipText, { color: backendUp ? theme.good : theme.bad }]}>
                {backendUp == null ? 'checking' : backendUp ? 'backend online' : 'backend offline'}
              </Text>
            </View>
          }>
          <View style={styles.toolRow}>
            <Tool
              label="Get position"
              onPress={async () => {
                try {
                  const loc = await GeoPulse.getCurrentPosition();
                  append(`pos  ${loc.coords.latitude.toFixed(5)}, ${loc.coords.longitude.toFixed(5)}`);
                } catch (e) {
                  append(`error  ${String(e)}`);
                }
              }}
            />
            <Tool
              label="Sync now"
              onPress={async () => {
                try {
                  const up = await GeoPulse.sync();
                  append(`sync  uploaded ${up.count}`);
                  setQueued(await GeoPulse.getCount());
                } catch (e) {
                  append(`error  ${String(e)}`);
                }
              }}
            />
            <Tool
              label="Test headless"
              onPress={async () => {
                try {
                  await GeoPulseNativeModule.simulateHeadless();
                  append('headless  dispatched -> check "headless-proof" device');
                } catch (e) {
                  append(`error  ${String(e)}`);
                }
              }}
            />
          </View>
        </Section>

        {/* Activity log */}
        <Section
          title="Activity"
          trailing={
            log.length > 0 ? (
              <Pressable hitSlop={10} onPress={() => setLog([])}>
                <Text style={[styles.clear, { color: theme.toolText }]}>Clear</Text>
              </Pressable>
            ) : undefined
          }>
          {log.length === 0 ? (
            <Text style={[styles.muted, { color: theme.textFaint }]}>Events will appear here.</Text>
          ) : (
            log.map((line, i) => (
              <Text key={i} style={[styles.logLine, { color: theme.textDim }]}>
                {line}
              </Text>
            ))
          )}
        </Section>
      </ScrollView>
    </SafeAreaView>
  );
}

/** Soft pulsing dot — alive while tracking, static while idle. */
function PulseDot({ active }: { active: boolean }) {
  const { theme } = useTheme();
  const pulse = useSharedValue(0);

  useEffect(() => {
    pulse.value = 0;
    if (active) {
      pulse.value = withRepeat(
        withTiming(1, { duration: 1400, easing: Easing.out(Easing.quad) }),
        -1
      );
    }
  }, [active, pulse]);

  const ringStyle = useAnimatedStyle(() => ({
    transform: [{ scale: 1 + pulse.value * 1.4 }],
    opacity: 0.5 * (1 - pulse.value),
  }));

  const color = active ? theme.good : theme.textFaint;
  return (
    <View style={styles.pulseWrap}>
      {active && <Animated.View style={[styles.pulseRing, { backgroundColor: color }, ringStyle]} />}
      <View style={[styles.pulseCore, { backgroundColor: color }]} />
    </View>
  );
}

/** Card with a Material-style overline title and an optional trailing element. */
function Section(props: {
  title: string;
  trailing?: React.ReactNode;
  children: React.ReactNode;
}) {
  const { theme } = useTheme();
  return (
    <View
      style={[
        styles.card,
        { backgroundColor: theme.card, borderColor: theme.cardBorder, elevation: theme.elevation },
      ]}>
      <View style={styles.cardHeader}>
        <Text style={[styles.cardTitle, { color: theme.textDim }]}>{props.title}</Text>
        {props.trailing}
      </View>
      {props.children}
    </View>
  );
}

function Stat(props: { label: string; value: string; unit?: string }) {
  const { theme } = useTheme();
  return (
    <View
      style={[
        styles.stat,
        { backgroundColor: theme.card, borderColor: theme.cardBorder, elevation: theme.elevation },
      ]}>
      <Text style={[styles.statLabel, { color: theme.textFaint }]}>{props.label}</Text>
      <Text style={[styles.statValue, { color: theme.text }]}>
        {props.value}
        {props.unit ? (
          <Text style={[styles.statUnit, { color: theme.textFaint }]}> {props.unit}</Text>
        ) : null}
      </Text>
    </View>
  );
}

function PermRow(props: { label: string; ok?: boolean; last?: boolean }) {
  const { theme } = useTheme();
  const known = props.ok != null;
  const color = !known ? theme.textFaint : props.ok ? theme.good : theme.bad;
  return (
    <View
      style={[styles.permRow, !props.last && { borderBottomWidth: 1, borderBottomColor: theme.divider }]}>
      <Text style={[styles.permLabel, { color: theme.text }]}>{props.label}</Text>
      <View style={[styles.permPill, { backgroundColor: !known ? theme.inner : props.ok ? theme.goodBg : theme.badBg }]}>
        <Text style={[styles.permState, { color }]}>
          {!known ? '—' : props.ok ? 'On' : 'Off'}
        </Text>
      </View>
    </View>
  );
}

/** Filled (primary) button with native ripple. */
function Button(props: { label: string; onPress: () => void; style?: StyleProp<ViewStyle> }) {
  const { theme } = useTheme();
  return (
    <View style={[styles.btnClip, props.style]}>
      <Pressable
        android_ripple={{ color: 'rgba(255,255,255,0.25)' }}
        style={[styles.btn, { backgroundColor: theme.primary }]}
        onPress={props.onPress}>
        <Text style={[styles.btnText, { color: theme.primaryText }]}>{props.label}</Text>
      </Pressable>
    </View>
  );
}

/** Tonal (secondary) action chip with native ripple. */
function Tool(props: { label: string; onPress: () => void }) {
  const { theme } = useTheme();
  return (
    <View style={styles.toolClip}>
      <Pressable
        android_ripple={{ color: theme.ripple }}
        style={[styles.tool, { backgroundColor: theme.toolBg }]}
        onPress={props.onPress}>
        <Text style={[styles.toolText, { color: theme.toolText }]}>{props.label}</Text>
      </Pressable>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1 },
  content: { padding: 16, paddingBottom: 56 },

  appBar: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    marginTop: 14,
    marginBottom: 14,
    paddingHorizontal: 4,
    minHeight: 56,
  },
  appBarActions: { marginLeft: 'auto', flexDirection: 'row', alignItems: 'center', gap: 8 },
  logo: {
    width: 42,
    height: 42,
    borderRadius: 13,
    alignItems: 'center',
    justifyContent: 'center',
  },
  logoGlyph: { color: '#fff', fontSize: 20, fontWeight: '900' },
  brand: { fontSize: 21, fontFamily: 'sans-serif-medium', letterSpacing: 0.1 },
  brandSub: { fontSize: 12, marginTop: 1 },

  card: { marginTop: 14, borderRadius: 20, padding: 16, borderWidth: 1 },
  cardHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginBottom: 12,
  },
  cardTitle: {
    fontSize: 12,
    fontFamily: 'sans-serif-medium',
    letterSpacing: 1.1,
    textTransform: 'uppercase',
  },

  heroCard: { flexDirection: 'row', alignItems: 'center', gap: 14, paddingVertical: 18, marginTop: 0 },
  heroTextWrap: { flex: 1 },
  heroTitle: { fontSize: 17, fontFamily: 'sans-serif-medium' },
  heroSub: { fontSize: 13, marginTop: 2 },

  pulseWrap: { width: 26, height: 26, alignItems: 'center', justifyContent: 'center' },
  pulseRing: { position: 'absolute', width: 22, height: 22, borderRadius: 11 },
  pulseCore: { width: 12, height: 12, borderRadius: 6 },

  statsRow: { flexDirection: 'row', gap: 8, marginTop: 14 },
  stat: { flex: 1, borderRadius: 16, paddingVertical: 12, paddingHorizontal: 11, borderWidth: 1 },
  statLabel: { fontSize: 10, textTransform: 'uppercase', letterSpacing: 0.6 },
  statValue: { fontSize: 19, fontFamily: 'sans-serif-medium', marginTop: 4, fontVariant: ['tabular-nums'] },
  statUnit: { fontSize: 11 },

  segment: { flexDirection: 'row', borderRadius: 12, padding: 4, gap: 4 },
  segClip: { flex: 1, borderRadius: 9, overflow: 'hidden' },
  segItem: { paddingVertical: 10, alignItems: 'center', borderRadius: 9 },
  segText: { fontFamily: 'sans-serif-medium', textTransform: 'capitalize', fontSize: 13.5 },

  permRow: { flexDirection: 'row', alignItems: 'center', minHeight: 46 },
  permLabel: { fontSize: 14.5, flex: 1 },
  permPill: { borderRadius: 999, paddingHorizontal: 11, paddingVertical: 4, minWidth: 44, alignItems: 'center' },
  permState: { fontSize: 12, fontFamily: 'sans-serif-medium' },

  banner: { marginTop: 12, borderWidth: 1, borderRadius: 16, padding: 14 },
  bannerTitle: { fontFamily: 'sans-serif-medium', fontSize: 14.5, marginBottom: 5 },
  bannerText: { fontSize: 13, lineHeight: 19, marginBottom: 12 },

  okPill: { marginTop: 12, borderRadius: 12, paddingVertical: 9, alignItems: 'center' },
  okPillText: { fontSize: 13, fontFamily: 'sans-serif-medium' },

  btnClip: { borderRadius: 12, overflow: 'hidden' },
  btn: { paddingVertical: 13, alignItems: 'center', borderRadius: 12 },
  btnText: { fontSize: 14.5, fontFamily: 'sans-serif-medium' },

  toolRow: { flexDirection: 'row', gap: 8, flexWrap: 'wrap', marginTop: 10 },
  toolClip: { borderRadius: 999, overflow: 'hidden' },
  tool: { borderRadius: 999, paddingVertical: 10, paddingHorizontal: 15, minHeight: 40, justifyContent: 'center' },
  toolText: { fontFamily: 'sans-serif-medium', fontSize: 13 },

  statusChip: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    borderRadius: 999,
    paddingHorizontal: 10,
    paddingVertical: 4,
  },
  statusChipDot: { width: 7, height: 7, borderRadius: 4 },
  statusChipText: { fontSize: 11.5, fontFamily: 'sans-serif-medium' },

  clear: { fontSize: 13, fontFamily: 'sans-serif-medium' },
  muted: { fontStyle: 'italic' },
  logLine: { fontSize: 12.5, marginBottom: 5, fontFamily: 'monospace' },
});
