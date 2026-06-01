import maplibregl from 'maplibre-gl';
import 'maplibre-gl/dist/maplibre-gl.css';
import { useEffect, useRef, useState } from 'react';

import type { MapStyleOption } from './config';
import type { GeoLocation, VisitMarker } from './types';
import { bearing } from './utils';

interface Props {
  style: MapStyleOption;
  path: GeoLocation[];
  last: GeoLocation | null;
  visits: VisitMarker[];
  deviceKey: string | null;
  follow: boolean;
}

function visitsToGeoJSON(visits: VisitMarker[]) {
  return {
    type: 'FeatureCollection' as const,
    features: visits.map((v) => ({
      type: 'Feature' as const,
      geometry: { type: 'Point' as const, coordinates: [v.longitude, v.latitude] },
      properties: {
        dwell: v.dwellMs != null ? `${Math.round(v.dwellMs / 60000)} min` : 'visit',
      },
    })),
  };
}

function addOverlays(map: maplibregl.Map) {
  if (!map.getSource('route')) {
    map.addSource('route', {
      type: 'geojson',
      data: { type: 'Feature', geometry: { type: 'LineString', coordinates: [] }, properties: {} },
    });
    map.addLayer({
      id: 'route-glow',
      type: 'line',
      source: 'route',
      layout: { 'line-cap': 'round', 'line-join': 'round' },
      paint: { 'line-color': '#38bdf8', 'line-width': 12, 'line-opacity': 0.18, 'line-blur': 8 },
    });
    map.addLayer({
      id: 'route-line',
      type: 'line',
      source: 'route',
      layout: { 'line-cap': 'round', 'line-join': 'round' },
      paint: { 'line-color': '#38bdf8', 'line-width': 4, 'line-opacity': 0.95 },
    });
  }
  if (!map.getSource('visits')) {
    map.addSource('visits', { type: 'geojson', data: visitsToGeoJSON([]) });
    map.addLayer({
      id: 'visits-glow',
      type: 'circle',
      source: 'visits',
      paint: { 'circle-radius': 18, 'circle-color': '#34d399', 'circle-opacity': 0.15, 'circle-blur': 1 },
    });
    map.addLayer({
      id: 'visits-circle',
      type: 'circle',
      source: 'visits',
      paint: {
        'circle-radius': 7,
        'circle-color': '#34d399',
        'circle-stroke-width': 2,
        'circle-stroke-color': 'rgba(255,255,255,0.9)',
        'circle-opacity': 0.95,
      },
    });
  }
}

export function MapView({ style, path, last, visits, deviceKey, follow }: Props) {
  const containerRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<maplibregl.Map | null>(null);
  const markerElRef = useRef<HTMLDivElement | null>(null);
  const markerRef = useRef<maplibregl.Marker | null>(null);
  const loadedRef = useRef(false);
  const fitKeyRef = useRef<string | null>(null);
  // State (not just a ref) so the data/marker effect re-runs once the map is
  // ready — otherwise a fix that arrives before 'load' never places the marker.
  const [mapReady, setMapReady] = useState(false);
  // Latest props, so we can (re)paint data the moment the style finishes loading
  // even if it arrived before 'load' / after a style switch.
  const dataRef = useRef({ path, visits });
  useEffect(() => {
    dataRef.current = { path, visits };
  }, [path, visits]);

  const paintData = (map: maplibregl.Map) => {
    const { path: p, visits: v } = dataRef.current;
    (map.getSource('route') as maplibregl.GeoJSONSource | undefined)?.setData({
      type: 'Feature',
      geometry: {
        type: 'LineString',
        coordinates: p.map((x) => [x.coords.longitude, x.coords.latitude]),
      },
      properties: {},
    });
    (map.getSource('visits') as maplibregl.GeoJSONSource | undefined)?.setData(
      visitsToGeoJSON(v)
    );
  };

  // Init map once.
  useEffect(() => {
    if (!containerRef.current || mapRef.current) return;
    const map = new maplibregl.Map({
      container: containerRef.current,
      style: style.url,
      center: [-89.2182, 13.6929],
      zoom: 12,
      attributionControl: { compact: true },
    });
    mapRef.current = map;
    map.addControl(new maplibregl.NavigationControl(), 'top-right');

    const onLoad = () => {
      addOverlays(map);
      const popup = new maplibregl.Popup({ closeButton: false, closeOnClick: false, offset: 12 });
      map.on('mouseenter', 'visits-circle', (e) => {
        map.getCanvas().style.cursor = 'pointer';
        const f = e.features?.[0];
        if (!f) return;
        const c = (f.geometry as GeoJSON.Point).coordinates as [number, number];
        popup.setLngLat(c).setHTML(`<b>Visit</b> · ${f.properties?.dwell}`).addTo(map);
      });
      map.on('mouseleave', 'visits-circle', () => {
        map.getCanvas().style.cursor = '';
        popup.remove();
      });
      loadedRef.current = true;
      setMapReady(true);
      paintData(map); // paint any data that arrived before the map finished loading
    };
    map.on('load', onLoad);

    return () => {
      map.remove();
      mapRef.current = null;
      markerRef.current = null;
      markerElRef.current = null;
      loadedRef.current = false;
    };
    // Init runs once; the initial style.url is read intentionally on first mount,
    // and later style changes are handled by the dedicated effect below.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Switch base style on demand (re-adds overlays after the new style loads).
  useEffect(() => {
    const map = mapRef.current;
    if (!map || !loadedRef.current) return;
    loadedRef.current = false;
    setMapReady(false);
    map.setStyle(style.url);
    map.once('styledata', () => {
      addOverlays(map);
      loadedRef.current = true;
      setMapReady(true);
      paintData(map); // re-apply current data after the new style loads
    });
  }, [style.url]);

  // Update route + live marker (with heading) + visit markers. Depends on
  // `mapReady` so a fix that arrived before the map loaded still places the
  // marker and fits bounds once the style is ready.
  useEffect(() => {
    const map = mapRef.current;
    if (!map || !mapReady) return;

    const coords = path.map((p) => [p.coords.longitude, p.coords.latitude]);
    (map.getSource('route') as maplibregl.GeoJSONSource | undefined)?.setData({
      type: 'Feature',
      geometry: { type: 'LineString', coordinates: coords },
      properties: {},
    });
    (map.getSource('visits') as maplibregl.GeoJSONSource | undefined)?.setData(
      visitsToGeoJSON(visits)
    );

    if (last) {
      const lngLat: [number, number] = [last.coords.longitude, last.coords.latitude];

      // Heading from the last two points (fallback to reported speed bearing).
      let heading = 0;
      let moving = false;
      if (path.length >= 2) {
        const a = path[path.length - 2].coords;
        const b = path[path.length - 1].coords;
        const d = Math.hypot(b.latitude - a.latitude, b.longitude - a.longitude);
        if (d > 1e-6) {
          heading = bearing(a.latitude, a.longitude, b.latitude, b.longitude);
          moving = (last.coords.speed ?? 0) > 0.5 || d > 1e-5;
        }
      }

      if (!markerRef.current) {
        const el = document.createElement('div');
        el.className = 'gp-marker';
        el.innerHTML = '<div class="gp-arrow"></div>';
        markerElRef.current = el;
        markerRef.current = new maplibregl.Marker({ element: el }).setLngLat(lngLat).addTo(map);
      } else {
        markerRef.current.setLngLat(lngLat);
      }
      // Show arrow only while moving; rotate to heading.
      if (markerElRef.current) {
        markerElRef.current.classList.toggle('moving', moving);
        const arrow = markerElRef.current.querySelector('.gp-arrow') as HTMLElement | null;
        if (arrow) arrow.style.transform = `rotate(${heading}deg)`;
      }

      if (fitKeyRef.current !== deviceKey && coords.length >= 1) {
        const b = new maplibregl.LngLatBounds(lngLat, lngLat);
        coords.forEach((c) => b.extend(c as [number, number]));
        visits.forEach((v) => b.extend([v.longitude, v.latitude]));
        map.fitBounds(b, { padding: 90, maxZoom: 15, duration: 700 });
        fitKeyRef.current = deviceKey;
      } else if (follow) {
        map.easeTo({ center: lngLat, duration: 600 });
      }
    }
  }, [path, last, visits, deviceKey, follow, mapReady]);

  return <div ref={containerRef} className="map" />;
}
