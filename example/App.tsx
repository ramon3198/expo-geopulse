import GeoPulse, {
  Accuracy,
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
  Text,
  View,
} from 'react-native';

import { MiniMap } from './MiniMap';
import { ThemeToggle } from './ThemeToggle';
import { useTheme } from './ThemeProvider';

const DEVICE_ID = 'ramon-phone';
const API = 'https://gpsapi.carcamodev.site';

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
      setStatus('Requesting permissions…');
      const p = await GeoPulse.requestPermissions();
      setPerms(p);
      if (!p.fine && !p.coarse) {
        setStatus('Location permission denied');
        append('error  location permission denied');
        return;
      }
      if (!p.locationServicesEnabled) {
        setStatus('Turn on location…');
        const ok = await GeoPulse.requestEnableLocation();
        await refreshPerms();
        if (!ok) {
          setStatus('Location is off');
          append('error  location services are off');
          return;
        }
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

  const speedKmh =
    last?.coords.speed != null ? (last.coords.speed * 3.6).toFixed(1) : '—';

  return (
    <SafeAreaView style={[styles.container, { backgroundColor: theme.bg }]}>
      <StatusBar barStyle={theme.name === 'dark' ? 'light-content' : 'dark-content'} />
      <ScrollView contentContainerStyle={styles.content}>
        <View style={styles.headerRow}>
          <View style={[styles.brandDot, { backgroundColor: theme.accent }]} />
          <Text style={[styles.brand, { color: theme.text }]}>GeoPulse</Text>
          <View style={styles.headerActions}>
            <View
              style={[
                styles.badge,
                { backgroundColor: enabled ? 'rgba(52,211,153,0.15)' : theme.inner },
              ]}>
              <Text style={[styles.badgeText, { color: enabled ? theme.good : theme.textDim }]}>
                {enabled ? 'TRACKING' : 'IDLE'}
              </Text>
            </View>
            <ThemeToggle />
          </View>
        </View>
        <Text style={[styles.status, { color: theme.textDim }]}>{status}</Text>

        <View style={styles.actions}>
          <Pressable
            style={[styles.btn, { backgroundColor: theme.primary }, (enabled || !ready) && styles.btnDisabled]}
            disabled={enabled || !ready}
            onPress={handleStart}>
            <Text style={[styles.btnPrimaryText, { color: theme.primaryText }]}>Start tracking</Text>
          </Pressable>
          <Pressable
            style={[styles.btn, { backgroundColor: theme.inner, borderWidth: 1, borderColor: theme.cardBorder }, !enabled && styles.btnDisabled]}
            disabled={!enabled}
            onPress={handleStop}>
            <Text style={[styles.btnGhostText, { color: theme.text }]}>Stop</Text>
          </Pressable>
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
        <Card title="Tracking mode">
          <View style={[styles.segment, { backgroundColor: theme.inner }]}>
            {(['eco', 'standard', 'high'] as Mode[]).map((m) => (
              <Pressable
                key={m}
                style={[styles.segItem, mode === m && { backgroundColor: theme.primary }]}
                onPress={() => changeMode(m)}>
                <Text style={[styles.segText, { color: mode === m ? theme.primaryText : theme.textDim }]}>{m}</Text>
              </Pressable>
            ))}
          </View>
        </Card>

        {/* Permissions */}
        <Card title="Permissions">
          <PermRow label="Location (fine)" ok={perms?.fine} />
          <PermRow label="Background location" ok={perms?.background} />
          <PermRow label="Notifications" ok={perms?.notifications} />
          <PermRow label="Activity recognition" ok={perms?.activityRecognition} />
          <PermRow label="Location services (GPS)" ok={perms?.locationServicesEnabled} />

          {/* Background = "Allow all the time" — the key permission for tracking
              with the app closed. Guided, step-by-step. */}
          {perms && !perms.background && (
            <View style={[styles.bgBox, { backgroundColor: theme.toolBg, borderColor: theme.cardBorder }]}>
              <Text style={[styles.bgTitle, { color: theme.text }]}>Enable background tracking</Text>
              <Text style={[styles.bgText, { color: theme.textDim }]}>
                To keep tracking when the app is closed, Android needs
                “Allow all the time”. {bgHint}
              </Text>
              <Pressable style={[styles.bgBtn, { backgroundColor: theme.primary }]} onPress={handleBackground}>
                <Text style={[styles.bgBtnText, { color: theme.primaryText }]}>{bgBtnLabel}</Text>
              </Pressable>
            </View>
          )}
          {perms?.background && (
            <Text style={[styles.bgOk, { color: theme.good }]}>Background tracking enabled — “Allow all the time” is on.</Text>
          )}

          <View style={styles.toolRow}>
            <Tool label="Refresh" onPress={refreshPerms} />
            <Tool label="Turn on GPS" onPress={async () => { await GeoPulse.requestEnableLocation(); await refreshPerms(); }} />
            <Tool label="Unrestrict battery" onPress={async () => { const ok = await GeoPulse.requestIgnoreBatteryOptimizations(); append(`battery  unrestricted=${ok}`); }} />
            <Tool label="App settings" onPress={() => GeoPulse.openAppSettings()} />
          </View>
        </Card>

        {/* Tools */}
        <Card title="Tools">
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
                  append(`sync  uploaded ${up.length}`);
                  setQueued(await GeoPulse.getCount());
                } catch (e) {
                  append(`error  ${String(e)}`);
                }
              }}
            />
          </View>
          <View style={styles.backendRow}>
            <Text style={[styles.metaLabel, { color: theme.textFaint }]}>BACKEND</Text>
            <Text style={[styles.backendDot, { color: backendUp ? theme.good : theme.bad }]}>
              {backendUp == null ? 'checking…' : backendUp ? 'online' : 'offline'}
            </Text>
          </View>
        </Card>

        {/* Activity log */}
        <Card title="Activity">
          {log.length === 0 ? (
            <Text style={[styles.muted, { color: theme.textFaint }]}>Events will appear here.</Text>
          ) : (
            log.map((line, i) => (
              <Text key={i} style={[styles.logLine, { color: theme.textDim }]}>
                {line}
              </Text>
            ))
          )}
        </Card>
      </ScrollView>
    </SafeAreaView>
  );
}

function Card(props: { title: string; children: React.ReactNode }) {
  const { theme } = useTheme();
  return (
    <View style={[styles.card, { backgroundColor: theme.card, borderColor: theme.cardBorder }]}>
      <Text style={[styles.cardTitle, { color: theme.textDim }]}>{props.title}</Text>
      {props.children}
    </View>
  );
}

function Stat(props: { label: string; value: string; unit?: string }) {
  const { theme } = useTheme();
  return (
    <View style={[styles.stat, { backgroundColor: theme.card, borderColor: theme.cardBorder }]}>
      <Text style={[styles.statLabel, { color: theme.textFaint }]}>{props.label}</Text>
      <Text style={[styles.statValue, { color: theme.text }]}>
        {props.value}
        {props.unit ? <Text style={[styles.statUnit, { color: theme.textFaint }]}> {props.unit}</Text> : null}
      </Text>
    </View>
  );
}

function PermRow(props: { label: string; ok?: boolean }) {
  const { theme } = useTheme();
  const color = props.ok == null ? theme.textFaint : props.ok ? theme.good : theme.bad;
  return (
    <View style={styles.permRow}>
      <View style={[styles.permDot, { backgroundColor: color }]} />
      <Text style={[styles.permLabel, { color: theme.text }]}>{props.label}</Text>
      <Text style={[styles.permState, { color }]}>
        {props.ok == null ? '—' : props.ok ? 'granted' : 'missing'}
      </Text>
    </View>
  );
}

function Tool(props: { label: string; onPress: () => void }) {
  const { theme } = useTheme();
  return (
    <Pressable style={[styles.tool, { backgroundColor: theme.toolBg }]} onPress={props.onPress}>
      <Text style={[styles.toolText, { color: theme.toolText }]}>{props.label}</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#070b14' },
  content: { padding: 20, paddingBottom: 60 },

  headerRow: { flexDirection: 'row', alignItems: 'center', gap: 10, marginTop: 22 },
  headerActions: { marginLeft: 'auto', flexDirection: 'row', alignItems: 'center', gap: 10 },
  brandDot: { width: 13, height: 13, borderRadius: 7, backgroundColor: '#38bdf8' },
  brand: { fontSize: 26, fontWeight: '800', color: '#fff', letterSpacing: -0.5 },
  badge: { paddingHorizontal: 11, paddingVertical: 5, borderRadius: 999 },
  badgeOn: { backgroundColor: 'rgba(52,211,153,0.15)' },
  badgeOff: { backgroundColor: 'rgba(148,163,184,0.15)' },
  badgeText: { fontSize: 11, fontWeight: '700', letterSpacing: 0.6 },
  badgeTextOn: { color: '#34d399' },
  badgeTextOff: { color: '#94a3b8' },
  status: { fontSize: 14, color: '#8294a8', marginTop: 8, marginBottom: 16 },

  actions: { flexDirection: 'row', gap: 12 },
  btn: { flex: 1, paddingVertical: 15, borderRadius: 14, alignItems: 'center' },
  btnPrimary: { backgroundColor: '#3b82f6' },
  btnPrimaryText: { color: '#fff', fontSize: 16, fontWeight: '700' },
  btnGhost: { backgroundColor: 'rgba(255,255,255,0.06)', borderWidth: 1, borderColor: 'rgba(255,255,255,0.1)' },
  btnGhostText: { color: '#cfe1f2', fontSize: 16, fontWeight: '700' },
  btnDisabled: { opacity: 0.4 },

  statsRow: { flexDirection: 'row', gap: 8, marginTop: 16 },
  stat: { flex: 1, backgroundColor: '#0e1626', borderRadius: 12, padding: 11, borderWidth: 1, borderColor: 'rgba(255,255,255,0.06)' },
  statLabel: { fontSize: 9.5, color: '#64748b', textTransform: 'uppercase', letterSpacing: 0.4 },
  statValue: { fontSize: 18, color: '#e6edf3', fontWeight: '800', marginTop: 3 },
  statUnit: { fontSize: 11, color: '#64748b', fontWeight: '600' },

  card: { marginTop: 14, backgroundColor: '#0e1626', borderRadius: 16, padding: 16, borderWidth: 1, borderColor: 'rgba(255,255,255,0.06)' },
  cardTitle: { fontSize: 11.5, fontWeight: '700', color: '#8294a8', letterSpacing: 0.7, textTransform: 'uppercase', marginBottom: 12 },

  segment: { flexDirection: 'row', backgroundColor: 'rgba(255,255,255,0.04)', borderRadius: 10, padding: 4 },
  segItem: { flex: 1, paddingVertical: 9, borderRadius: 7, alignItems: 'center' },
  segItemOn: { backgroundColor: '#3b82f6' },
  segText: { color: '#8294a8', fontWeight: '700', textTransform: 'capitalize', fontSize: 13 },
  segTextOn: { color: '#fff' },

  permRow: { flexDirection: 'row', alignItems: 'center', gap: 9, paddingVertical: 5 },
  permDot: { width: 9, height: 9, borderRadius: 5 },
  permLabel: { color: '#cfe1f2', fontSize: 13.5, flex: 1 },
  permState: { fontSize: 12, fontWeight: '700' },
  up: { backgroundColor: '#34d399' },
  down: { backgroundColor: '#f87171' },
  upText: { color: '#34d399' },
  downText: { color: '#f87171' },

  toolRow: { flexDirection: 'row', gap: 8, flexWrap: 'wrap', marginTop: 10 },
  tool: { backgroundColor: 'rgba(56,189,248,0.12)', borderRadius: 10, paddingVertical: 9, paddingHorizontal: 13 },
  toolText: { color: '#38bdf8', fontWeight: '700', fontSize: 13 },

  backendRow: { flexDirection: 'row', alignItems: 'center', gap: 8, marginTop: 12 },
  backendDot: { fontSize: 13, fontWeight: '700' },
  metaLabel: { fontSize: 10.5, color: '#64748b', textTransform: 'uppercase', letterSpacing: 0.5 },

  muted: { color: '#64748b', fontStyle: 'italic' },
  logLine: { color: '#9fb3c8', fontSize: 12.5, marginBottom: 5, fontFamily: 'monospace' },

  bgBox: {
    marginTop: 12,
    backgroundColor: 'rgba(56,189,248,0.08)',
    borderWidth: 1,
    borderColor: 'rgba(56,189,248,0.25)',
    borderRadius: 12,
    padding: 13,
  },
  bgTitle: { color: '#e6edf3', fontWeight: '700', fontSize: 14, marginBottom: 5 },
  bgText: { color: '#9fb3c8', fontSize: 12.5, lineHeight: 18, marginBottom: 11 },
  bgBtn: { backgroundColor: '#38bdf8', borderRadius: 10, paddingVertical: 11, alignItems: 'center' },
  bgBtnText: { color: '#06223a', fontWeight: '800', fontSize: 14 },
  bgOk: { color: '#34d399', fontSize: 12.5, fontWeight: '600', marginTop: 10 },
});
