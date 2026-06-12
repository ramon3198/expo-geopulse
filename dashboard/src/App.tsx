import './App.css';

import { useEffect, useMemo, useState } from 'react';

import { MAP_STYLES, styleById } from './config';
import { MapView } from './MapView';
import { Sparkline } from './Sparkline';
import { useLiveFeed } from './useLiveFeed';
import { formatDistance, formatDuration, formatTime, pathDistance } from './utils';
import type { SessionInfo } from './types';

const kindMeta: Record<string, { color: string; icon: string }> = {
  trip: { color: '#38bdf8', icon: '↗' },
  visit: { color: '#34d399', icon: '◆' },
  driving: { color: '#fbbf24', icon: '⚡' },
};

function sessionLabel(s: SessionInfo, isNewest: boolean): string {
  if (s.session === 'legacy') return `History (pre-sessions) · ${s.points} pts`;
  const start = s.start_ts
    ? new Date(s.start_ts).toLocaleString([], {
        day: '2-digit',
        month: 'short',
        hour: '2-digit',
        minute: '2-digit',
      })
    : '—';
  return `${isNewest ? '● ' : ''}${start} · ${s.points} pts`;
}

export default function App() {
  const {
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
  } = useLiveFeed();

  const [styleId, setStyleId] = useState(() => localStorage.getItem('gp-style') ?? 'dark');
  const [follow, setFollow] = useState(true);
  const [collapsed, setCollapsed] = useState(false);
  const mapStyle = styleById(styleId);

  useEffect(() => {
    localStorage.setItem('gp-style', styleId);
    document.documentElement.dataset.theme = mapStyle.theme;
  }, [styleId, mapStyle.theme]);

  const distance = useMemo(() => pathDistance(path), [path]);
  const duration = useMemo(() => {
    if (path.length < 2) return 0;
    return path[path.length - 1].timestamp - path[0].timestamp;
  }, [path]);
  const speeds = useMemo(
    () => path.slice(-40).map((p) => (p.coords.speed ?? 0) * 3.6),
    [path]
  );
  const speedKmh = last?.coords.speed != null ? (last.coords.speed * 3.6).toFixed(1) : '—';

  return (
    <div className={`app theme-${mapStyle.theme}`}>
      <MapView
        style={mapStyle}
        path={path}
        last={last}
        visits={visits}
        deviceKey={`${device}:${session}`}
        follow={follow}
      />

      {/* Floating map controls */}
      <div className="map-controls">
        <select
          className="map-select"
          value={styleId}
          onChange={(e) => setStyleId(e.target.value)}
          title="Map style"
        >
          {MAP_STYLES.map((s) => (
            <option key={s.id} value={s.id}>
              {s.label}
            </option>
          ))}
        </select>
        <button
          className={`map-btn ${follow ? 'on' : ''}`}
          title={follow ? 'Following — click to free pan' : 'Click to follow device'}
          onClick={() => setFollow((f) => !f)}
        >
          ◎
        </button>
      </div>

      <aside className={`sidebar ${collapsed ? 'collapsed' : ''}`}>
        <header className="side-head">
          <div className="brand">
            <div className="brand-mark">◉</div>
            <div>
              <div className="brand-name">GeoPulse</div>
              <div className="brand-sub">Live tracking console</div>
            </div>
          </div>
          <div className="head-actions">
            <div className={`pill ${connected ? 'pill-live' : 'pill-off'}`}>
              <span className="pulse-dot" />
              {connected ? 'LIVE' : 'OFFLINE'}
            </div>
            <button
              className="icon-btn"
              title={collapsed ? 'Expand' : 'Collapse'}
              onClick={() => setCollapsed((c) => !c)}
            >
              {collapsed ? '▸' : '▾'}
            </button>
          </div>
        </header>

        {!collapsed && (
          <div className="side-body">
            <section className="section">
              <label className="field">
                <span className="field-label">Device</span>
                <select
                  className="field-select"
                  value={device ?? ''}
                  onChange={(e) => selectDevice(e.target.value)}
                >
                  {devices.length === 0 && <option value="">No devices yet</option>}
                  {devices.map((d) => (
                    <option key={d.device} value={d.device}>
                      {d.device}
                    </option>
                  ))}
                </select>
              </label>

              <label className="field">
                <span className="field-label">
                  Session
                  {liveSession && session !== 'legacy' && (
                    <span className="field-tag">live</span>
                  )}
                </span>
                <select
                  className="field-select"
                  value={session ?? ''}
                  onChange={(e) => selectSession(e.target.value)}
                >
                  {sessions.length === 0 && <option value="">No sessions yet</option>}
                  {sessions.map((s, i) => (
                    <option key={s.session} value={s.session}>
                      {sessionLabel(s, i === 0)}
                    </option>
                  ))}
                </select>
              </label>
            </section>

            <section className="section stats">
              <Stat label="Distance" value={formatDistance(distance)} />
              <Stat label="Duration" value={formatDuration(duration)} />
              <Stat label="Speed" value={speedKmh} unit="km/h" />
              <Stat label="Points" value={String(path.length)} />
            </section>

            <section className="section">
              <div className="spark-card">
                <div className="overline">Speed · last {speeds.length} fixes</div>
                <Sparkline values={speeds} color="var(--accent)" />
              </div>
            </section>

            <section className="section meta-row">
              <span className="chip">{last?.provider ?? 'no fix'}</span>
              {last?.confidence != null && <span className="chip">conf {last.confidence}</span>}
              <span className="chip">±{last?.coords.accuracy?.toFixed(0) ?? '—'} m</span>
              {last?.isMock && <span className="chip chip-warn">MOCK</span>}
              {last && (
                <span className="coords">
                  {last.coords.latitude.toFixed(6)}, {last.coords.longitude.toFixed(6)}
                </span>
              )}
            </section>

            <section className="section feed-section">
              <div className="overline">Activity</div>
              <ul className="feed">
                {feed.length === 0 && (
                  <li className="empty">Waiting for trips, visits &amp; driving events…</li>
                )}
                {feed.map((f, i) => {
                  const m = kindMeta[f.kind];
                  return (
                    <li key={i} className="event">
                      <span className="event-icon" style={{ color: m.color }}>
                        {m.icon}
                      </span>
                      <span className="event-label">{f.label}</span>
                      {f.detail && <span className="event-detail">{f.detail}</span>}
                      {f.at > 0 && <span className="event-time">{formatTime(f.at)}</span>}
                    </li>
                  );
                })}
              </ul>
            </section>
          </div>
        )}
      </aside>
    </div>
  );
}

function Stat({ label, value, unit }: { label: string; value: string; unit?: string }) {
  return (
    <div className="stat">
      <div className="overline">{label}</div>
      <div className="stat-value">
        {value}
        {unit && <span className="stat-unit"> {unit}</span>}
      </div>
    </div>
  );
}
