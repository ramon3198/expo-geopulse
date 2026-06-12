import { useCallback, useEffect, useRef, useState } from 'react';

import { API_BASE, WS_URL } from './config';
import type {
  DeviceInfo,
  DrivingEvent,
  FeedItem,
  GeoLocation,
  SessionInfo,
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
  sessions: SessionInfo[];
  session: string | null;
  /** True while the selected session is the device's newest (receiving live fixes). */
  liveSession: boolean;
  selectSession: (s: string) => void;
  path: GeoLocation[];
  last: GeoLocation | null;
  visits: VisitMarker[];
  feed: FeedItem[];
}

/**
 * Subscribes to the backend WebSocket and tracks one selected device's live
 * path, visit markers and event feed — scoped to one tracking SESSION at a
 * time (the SDK stamps a fresh sessionId per start()), so the map shows a
 * single run instead of every point ever recorded. Selecting the newest
 * session follows live fixes and auto-rolls into new runs as they start;
 * older sessions render as static history. Auto-reconnects.
 */
export function useLiveFeed(): LiveState {
  const [connected, setConnected] = useState(false);
  const [devices, setDevices] = useState<DeviceInfo[]>([]);
  const [device, setDevice] = useState<string | null>(null);
  const [sessions, setSessions] = useState<SessionInfo[]>([]);
  const [session, setSession] = useState<string | null>(null);
  const [liveSession, setLiveSession] = useState(true);
  const [path, setPath] = useState<GeoLocation[]>([]);
  const [last, setLast] = useState<GeoLocation | null>(null);
  const [visits, setVisits] = useState<VisitMarker[]>([]);
  const [feed, setFeed] = useState<FeedItem[]>([]);

  // Readable inside the WS handler without reconnecting.
  const deviceRef = useRef<string | null>(null);
  const sessionRef = useRef<string | null>(null);
  const liveRef = useRef(true);
  useEffect(() => {
    deviceRef.current = device;
  }, [device]);
  useEffect(() => {
    sessionRef.current = session;
  }, [session]);
  useEffect(() => {
    liveRef.current = liveSession;
  }, [liveSession]);

  const pushFeed = useCallback((item: FeedItem) => {
    setFeed((prev) => [item, ...prev].slice(0, 100));
  }, []);

  const refreshSessions = useCallback(async (dev: string): Promise<SessionInfo[]> => {
    try {
      const res = await fetch(`${API_BASE}/sessions/${dev}`).then((r) => r.json());
      // Defensive: an older backend (no /sessions route) answers a JSON object.
      const list: SessionInfo[] = Array.isArray(res) ? res : [];
      setSessions(list);
      return list;
    } catch {
      return [];
    }
  }, []);

  const loadPath = useCallback(async (dev: string, sess: string | null) => {
    setPath([]);
    setLast(null);
    try {
      const url =
        sess != null
          ? `${API_BASE}/locations/${dev}?session=${encodeURIComponent(sess)}`
          : `${API_BASE}/locations/${dev}`;
      const locs: GeoLocation[] = await fetch(url).then((r) => r.json());
      setPath(locs);
      setLast(locs[locs.length - 1] ?? null);
    } catch {
      /* ignore */
    }
  }, []);

  const loadEvents = useCallback(async (dev: string) => {
    setVisits([]);
    setFeed([]);
    try {
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

  // Select a device: newest session becomes active (live mode).
  const loadDevice = useCallback(
    async (dev: string) => {
      const list = await refreshSessions(dev);
      const newest = list[0]?.session ?? null;
      setSession(newest);
      sessionRef.current = newest;
      setLiveSession(true);
      liveRef.current = true;
      await Promise.all([loadPath(dev, newest), loadEvents(dev)]);
    },
    [refreshSessions, loadPath, loadEvents]
  );

  const selectDevice = useCallback(
    (dev: string) => {
      setDevice(dev);
      loadDevice(dev);
    },
    [loadDevice]
  );

  const selectSession = useCallback(
    (sess: string) => {
      const dev = deviceRef.current;
      if (!dev) return;
      setSession(sess);
      sessionRef.current = sess;
      const isNewest = sessions[0]?.session === sess;
      setLiveSession(isNewest);
      liveRef.current = isNewest;
      loadPath(dev, sess);
    },
    [sessions, loadPath]
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
        if (!sel || msg.device !== sel) return;

        if (msg.type === 'location') {
          const sid = msg.location.sessionId ?? 'legacy';
          if (sid === sessionRef.current) {
            setLast(msg.location);
            setPath((prev) => [...prev, msg.location].slice(-5000));
          } else if (liveRef.current) {
            // A new tracking run just started while we were following the
            // latest one: roll into it (fresh trace) and refresh the list.
            setSession(sid);
            sessionRef.current = sid;
            setPath([msg.location]);
            setLast(msg.location);
            refreshSessions(sel);
          }
          // Viewing an older session: history stays static; ignore live fixes.
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
  }, [pushFeed, refreshSessions]);

  return {
    connected,
    devices,
    device,
    selectDevice,
    sessions,
    session,
    liveSession,
    selectSession,
    path,
    last,
    visits,
    feed,
  };
}
