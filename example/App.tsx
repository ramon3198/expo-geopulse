import GeoPulse, { Accuracy, type Location } from 'expo-geopulse';
import { useEffect, useState } from 'react';
import {
  Button,
  SafeAreaView,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';

export default function App() {
  const [ready, setReady] = useState(false);
  const [enabled, setEnabled] = useState(false);
  const [last, setLast] = useState<Location | null>(null);
  const [log, setLog] = useState<string[]>([]);

  const append = (line: string) =>
    setLog((prev) => [`${new Date().toLocaleTimeString()}  ${line}`, ...prev].slice(0, 30));

  useEffect(() => {
    const locationSub = GeoPulse.onLocation((location) => {
      setLast(location);
      append(
        `📍 ${location.coords.latitude.toFixed(5)}, ${location.coords.longitude.toFixed(
          5
        )} (±${location.coords.accuracy}m) [${location.provider}]`
      );
    });
    const errorSub = GeoPulse.onError((error) => append(`⚠️ ${error.code}: ${error.message}`));

    GeoPulse.ready({
      desiredAccuracy: Accuracy.High,
      distanceFilter: 10,
      stopOnStationary: true,
      enableKalman: true,
    })
      .then((state) => {
        setReady(true);
        setEnabled(state.enabled);
        append('✅ ready()');
      })
      .catch((e) => append(`⚠️ ready() failed: ${String(e)}`));

    return () => {
      locationSub.remove();
      errorSub.remove();
    };
  }, []);

  return (
    <SafeAreaView style={styles.container}>
      <ScrollView contentContainerStyle={styles.content}>
        <Text style={styles.header}>GeoPulse</Text>
        <Text style={styles.subtitle}>
          ready: {ready ? 'yes' : 'no'} · tracking: {enabled ? 'on' : 'off'}
        </Text>

        <View style={styles.row}>
          <Button
            title="Start"
            onPress={async () => {
              const state = await GeoPulse.start();
              setEnabled(state.enabled);
              append('▶️ start()');
            }}
          />
          <Button
            title="Stop"
            onPress={async () => {
              const state = await GeoPulse.stop();
              setEnabled(state.enabled);
              append('⏹️ stop()');
            }}
          />
          <Button title="Emit test" onPress={() => GeoPulse.emitTestLocation()} />
        </View>

        <View style={styles.row}>
          <Button
            title="Permisos"
            onPress={async () => {
              const status = await GeoPulse.requestPermissions();
              append(
                `🔐 fine=${status.fine} bg=${status.background} notif=${status.notifications}`
              );
            }}
          />
          <Button
            title="Posición"
            onPress={async () => {
              try {
                const loc = await GeoPulse.getCurrentPosition();
                append(
                  `🎯 ${loc.coords.latitude.toFixed(5)}, ${loc.coords.longitude.toFixed(5)} (±${loc.coords.accuracy}m)`
                );
              } catch (e) {
                append(`⚠️ ${String(e)}`);
              }
            }}
          />
        </View>

        <View style={styles.row}>
          <Button
            title="+ Geofence"
            onPress={async () => {
              if (!last) {
                append('ℹ️ get a location first');
                return;
              }
              await GeoPulse.addGeofence({
                identifier: `gf_${Date.now()}`,
                latitude: last.coords.latitude,
                longitude: last.coords.longitude,
                radius: 150,
                notifyOnEntry: true,
                notifyOnExit: true,
              });
              const all = await GeoPulse.getGeofences();
              append(`📐 geofences: ${all.length}`);
            }}
          />
          <Button
            title="Sync"
            onPress={async () => {
              try {
                const uploaded = await GeoPulse.sync();
                append(`☁️ synced ${uploaded.length}`);
              } catch (e) {
                append(`⚠️ ${String(e)}`);
              }
            }}
          />
          <Button
            title="Batería"
            onPress={async () => {
              const ok = await GeoPulse.requestIgnoreBatteryOptimizations();
              append(`🔋 unrestricted: ${ok}`);
            }}
          />
        </View>

        <Group name="Last location">
          {last ? (
            <Text style={styles.mono}>
              {last.coords.latitude.toFixed(6)}, {last.coords.longitude.toFixed(6)}
              {'\n'}accuracy: ±{last.coords.accuracy}m{'\n'}provider: {last.provider}
            </Text>
          ) : (
            <Text style={styles.muted}>No fix yet — press “Emit test”.</Text>
          )}
        </Group>

        <Group name="Event log">
          {log.length === 0 ? (
            <Text style={styles.muted}>Events will appear here.</Text>
          ) : (
            log.map((line, i) => (
              <Text key={i} style={styles.logLine}>
                {line}
              </Text>
            ))
          )}
        </Group>
      </ScrollView>
    </SafeAreaView>
  );
}

function Group(props: { name: string; children: React.ReactNode }) {
  return (
    <View style={styles.group}>
      <Text style={styles.groupHeader}>{props.name}</Text>
      {props.children}
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#0b1220' },
  content: { padding: 20, paddingBottom: 60 },
  header: { fontSize: 34, fontWeight: '800', color: '#fff', marginTop: 20 },
  subtitle: { fontSize: 14, color: '#9fb3c8', marginBottom: 16 },
  row: { flexDirection: 'row', gap: 12, flexWrap: 'wrap', marginBottom: 8 },
  group: {
    marginTop: 16,
    backgroundColor: '#111a2e',
    borderRadius: 12,
    padding: 16,
  },
  groupHeader: { fontSize: 16, fontWeight: '700', color: '#cfe1f2', marginBottom: 10 },
  mono: { fontFamily: 'monospace', color: '#e6edf3', lineHeight: 20 },
  muted: { color: '#7d8ea3', fontStyle: 'italic' },
  logLine: { color: '#b7c7d8', fontSize: 12, marginBottom: 4 },
});
