import { useCallback, useEffect, useRef, useState } from 'react';

import { API_BASE, WS_URL } from './config';
import type {
  DeviceInfo,
  DrivingEvent,
  FeedItem,
  GeoLocation,
  TripEvent,
  VisitEvent,
  VisitMarker,
  WsMessage,
} from './types';

interface EventsResponse {
  trips?: TripEvent[];
  visits?: VisitEvent[];
  driving?: DrivingEvent[];
}

interface LiveState {
  connected: boolean;
  devices: DeviceInfo[];
  device: string | null;
  selectDevice: (d: string) => void;
  path: GeoLocation[];
  last: GeoLocation | null;
  visits: VisitMarker[];
  feed: FeedItem[];
}

/**
 * Subscribes to the backend WebSocket and tracks one selected device's live
 * path, visit markers and event feed. On select (or first load) it fetches that
 * device's history so the map and feed aren't empty. Auto-reconnects.
 */
export function useLiveFeed(): LiveState {
  const [connected, setConnected] = useState(false);
  const [devices, setDevices] = useState<DeviceInfo[]>([]);
  const [device, setDevice] = useState<string | null>(null);
  const [path, setPath] = useState<GeoLocation[]>([]);
  const [last, setLast] = useState<GeoLocation | null>(null);
  const [visits, setVisits] = useState<VisitMarker[]>([]);
  const [feed, setFeed] = useState<FeedItem[]>([]);

  // Keep the selected device readable inside the WS handler without reconnecting.
  const deviceRef = useRef<string | null>(null);
  useEffect(() => {
    deviceRef.current = device;
  }, [device]);

  const pushFeed = useCallback((item: FeedItem) => {
    setFeed((prev) => [item, ...prev].slice(0, 100));
  }, []);

  // Load a device's history (path + visits + events) when it becomes selected.
  const loadDevice = useCallback(async (dev: string) => {
    setPath([]);
    setVisits([]);
    setFeed([]);
    setLast(null);
    try {
      const locs: GeoLocation[] = await fetch(`${API_BASE}/locations/${dev}`).then((r) =>
        r.json()
      );
      setPath(locs);
      setLast(locs[locs.length - 1] ?? null);

      const events: EventsResponse = await fetch(`${API_BASE}/events/${dev}`).then((r) =>
        r.json()
      );
      // Visit markers (only "arrive" actions carry the place coordinates).
      const vmarkers: VisitMarker[] = (events.visits ?? [])
        .filter((e) => e.action === 'arrive' && e.visit)
        .map((e) => ({
          uuid: e.visit!.uuid,
          latitude: e.visit!.latitude,
          longitude: e.visit!.longitude,
          dwellMs: e.visit!.dwellMs,
        }));
      setVisits(vmarkers);

      // Seed the feed with recent history (newest first).
      const hist: FeedItem[] = [];
      for (const e of events.trips ?? [])
        hist.push({
          kind: 'trip',
          label: `Trip ${e.action}`,
          detail: e.trip?.distanceMeters != null ? `${Math.round(e.trip.distanceMeters)} m` : '',
          at: 0,
        });
      for (const e of events.visits ?? [])
        hist.push({ kind: 'visit', label: `Visit ${e.action}`, detail: '', at: 0 });
      for (const e of events.driving ?? [])
        hist.push({
          kind: 'driving',
          label: e.type,
          detail: `${e.severity} · ${Number(e.magnitude).toFixed(1)}`,
          at: 0,
        });
      setFeed(hist.slice(0, 100));
    } catch {
      /* ignore */
    }
  }, []);

  const selectDevice = useCallback(
    (dev: string) => {
      setDevice(dev);
      loadDevice(dev);
    },
    [loadDevice]
  );

  // Poll the device list so new devices appear in the selector.
  useEffect(() => {
    let stop = false;
    async function refreshDevices() {
      try {
        const list: DeviceInfo[] = await fetch(`${API_BASE}/devices`).then((r) => r.json());
        if (stop) return;
        setDevices(list);
        // Auto-select the most recent device on first load.
        if (!deviceRef.current && list.length) selectDevice(list[0].device);
      } catch {
        /* backend may be down */
      }
    }
    refreshDevices();
    const id = setInterval(refreshDevices, 5000);
    return () => {
      stop = true;
      clearInterval(id);
    };
  }, [selectDevice]);

  // WebSocket: apply only messages for the selected device.
  useEffect(() => {
    let stop = false;
    let retry: ReturnType<typeof setTimeout>;
    let ws: WebSocket | null = null;

    function connect() {
      ws = new WebSocket(WS_URL);
      ws.onopen = () => setConnected(true);
      ws.onclose = () => {
        setConnected(false);
        if (!stop) retry = setTimeout(connect, 1500);
      };
      ws.onerror = () => ws?.close();
      ws.onmessage = (ev) => {
        const msg: WsMessage = JSON.parse(ev.data);
        const sel = deviceRef.current;
        // Adopt the first device we see if none selected yet.
        if (!sel) return;
        if (msg.device !== sel) return;

        if (msg.type === 'location') {
          setLast(msg.location);
          setPath((prev) => [...prev, msg.location].slice(-5000));
        } else if (msg.type === 'trip') {
          const t = msg.event.trip ?? {};
          pushFeed({
            kind: 'trip',
            label: `Trip ${msg.event.action}`,
            detail: t.distanceMeters != null ? `${Math.round(t.distanceMeters)} m` : '',
            at: Date.now(),
          });
        } else if (msg.type === 'visit') {
          pushFeed({ kind: 'visit', label: `Visit ${msg.event.action}`, detail: '', at: Date.now() });
          const v = msg.event.visit;
          if (msg.event.action === 'arrive' && v) {
            setVisits((prev) => [
              ...prev,
              { uuid: v.uuid, latitude: v.latitude, longitude: v.longitude, dwellMs: v.dwellMs },
            ]);
          }
        } else if (msg.type === 'driving') {
          pushFeed({
            kind: 'driving',
            label: msg.event.type,
            detail: `${msg.event.severity} · ${Number(msg.event.magnitude).toFixed(1)}`,
            at: Date.now(),
          });
        }
      };
    }

    connect();
    return () => {
      stop = true;
      clearTimeout(retry);
      ws?.close();
    };
  }, [pushFeed]);

  return { connected, devices, device, selectDevice, path, last, visits, feed };
}
