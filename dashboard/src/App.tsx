import './App.css';

import { useEffect, useMemo, useState } from 'react';

import { MAP_STYLES, styleById } from './config';
import { MapView } from './MapView';
import { Sparkline } from './Sparkline';
import { useLiveFeed } from './useLiveFeed';
import { formatDistance, formatDuration, formatTime, pathDistance } from './utils';

const kindMeta: Record<string, { color: string; icon: string }> = {
  trip: { color: '#38bdf8', icon: '↗' },
  visit: { color: '#34d399', icon: '◆' },
  driving: { color: '#fbbf24', icon: '⚡' },
};

export default function App() {
  const { connected, devices, device, selectDevice, path, last, visits, feed } =
    useLiveFeed();

  const [styleId, setStyleId] = useState(
    () => localStorage.getItem('gp-style') ?? 'dark'
  );
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
        deviceKey={device}
        follow={follow}
      />

      <div className={`panel glass ${collapsed ? 'collapsed' : ''}`}>
        <header className="head">
          <div className="brand">
            <span className="brand-dot" />
            <span className="brand-name">GeoPulse</span>
          </div>
          <div className="head-actions">
            <div className={`pill ${connected ? 'pill-live' : 'pill-off'}`}>
              <span className="pulse-dot" />
              {connected ? 'LIVE' : '···'}
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
          <>
            <div className="controls">
              <div className="select-wrap">
                <select
                  className="device-select"
                  value={device ?? ''}
                  onChange={(e) => selectDevice(e.target.value)}
                >
                  {devices.length === 0 && <option value="">No devices yet</option>}
                  {devices.map((d) => (
                    <option key={d.device} value={d.device}>
                      {d.device} · {d.points} pts
                    </option>
                  ))}
                </select>
              </div>
              <div className="select-wrap small">
                <select
                  className="device-select"
                  value={styleId}
                  onChange={(e) => setStyleId(e.target.value)}
                >
                  {MAP_STYLES.map((s) => (
                    <option key={s.id} value={s.id}>
                      {s.label}
                    </option>
                  ))}
                </select>
              </div>
              <button
                className={`icon-btn follow ${follow ? 'on' : ''}`}
                title={follow ? 'Following — click to free pan' : 'Click to follow device'}
                onClick={() => setFollow((f) => !f)}
              >
                ◎
              </button>
            </div>

            <div className="stats">
              <Stat label="Distance" value={formatDistance(distance)} accent="#38bdf8" />
              <Stat label="Duration" value={formatDuration(duration)} accent="#a78bfa" />
              <Stat label="Speed" value={speedKmh} unit="km/h" accent="#22d3ee" />
              <Stat label="Visits" value={String(visits.length)} accent="#34d399" />
            </div>

            <div className="spark-card glass-inner">
              <div className="spark-label">Speed · last {speeds.length} fixes</div>
              <Sparkline values={speeds} color="#22d3ee" />
            </div>

            <div className="meta-row">
              <span className="chip">{last?.provider ?? 'no fix'}</span>
              {last?.confidence != null && (
                <span className="chip chip-conf">conf {last.confidence}</span>
              )}
              <span className="chip">±{last?.coords.accuracy?.toFixed(0) ?? '—'} m</span>
              {last?.isMock && <span className="chip chip-mock">MOCK</span>}
            </div>

            {last && (
              <div className="coords">
                {last.coords.latitude.toFixed(6)}, {last.coords.longitude.toFixed(6)}
              </div>
            )}

            <div className="feed-head">Activity</div>
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
          </>
        )}
      </div>
    </div>
  );
}

function Stat({
  label,
  value,
  unit,
  accent,
}: {
  label: string;
  value: string;
  unit?: string;
  accent: string;
}) {
  return (
    <div className="stat glass-inner">
      <div className="stat-label">{label}</div>
      <div className="stat-value" style={{ color: accent }}>
        {value}
        {unit && <span className="stat-unit"> {unit}</span>}
      </div>
    </div>
  );
}
