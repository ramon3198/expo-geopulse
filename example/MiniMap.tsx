import { useEffect, useMemo, useRef, useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { WebView } from 'react-native-webview';

import type { Location } from 'expo-geopulse';

interface Props {
  last: Location | null;
  path: Array<[number, number]>; // [lng, lat]
}

/**
 * Lightweight live map embedded via a WebView running MapLibre GL JS with free
 * CARTO dark tiles (no Google Maps key, no native map SDK). The HTML loads once;
 * location/path updates are pushed in with injectJavaScript so it never reloads.
 */
const HTML = `<!doctype html><html><head>
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
<link href="https://unpkg.com/maplibre-gl@5.6.0/dist/maplibre-gl.css" rel="stylesheet"/>
<script src="https://unpkg.com/maplibre-gl@5.6.0/dist/maplibre-gl.js"></script>
<style>html,body,#map{margin:0;height:100%;background:#0b1220}</style>
</head><body><div id="map"></div><script>
  const map = new maplibregl.Map({
    container: 'map',
    style: 'https://basemaps.cartocdn.com/gl/dark-matter-gl-style/style.json',
    center: [-89.2182, 13.6929], zoom: 14, attributionControl: false
  });
  let marker = null, ready = false, pending = null;
  map.on('load', () => {
    map.addSource('route', { type:'geojson', data:{type:'Feature',geometry:{type:'LineString',coordinates:[]}}});
    map.addLayer({ id:'route', type:'line', source:'route',
      layout:{'line-cap':'round','line-join':'round'},
      paint:{'line-color':'#38bdf8','line-width':4,'line-opacity':0.9}});
    ready = true;
    if (pending) { window.gpUpdate(pending); pending = null; }
  });
  window.gpUpdate = function(d) {
    if (!ready) { pending = d; return; }
    if (d.path) map.getSource('route').setData({type:'Feature',geometry:{type:'LineString',coordinates:d.path}});
    if (d.last) {
      const ll = [d.last.lng, d.last.lat];
      if (!marker) {
        const el = document.createElement('div');
        el.style.cssText = 'width:16px;height:16px;border-radius:50%;background:#38bdf8;border:3px solid #fff;box-shadow:0 0 12px #38bdf8';
        marker = new maplibregl.Marker({element:el}).setLngLat(ll).addTo(map);
      } else marker.setLngLat(ll);
      map.easeTo({center: ll, duration: 500});
    }
  };
</script></body></html>`;

export function MiniMap({ last, path }: Props) {
  const webRef = useRef<WebView>(null);
  const [loaded, setLoaded] = useState(false);

  const payload = useMemo(() => {
    const data: Record<string, unknown> = {};
    if (last) data.last = { lat: last.coords.latitude, lng: last.coords.longitude };
    if (path.length) data.path = path;
    return JSON.stringify(data);
  }, [last, path]);

  // Push updates into the page (after it has loaded) without reloading.
  useEffect(() => {
    if (loaded && webRef.current) {
      webRef.current.injectJavaScript(`window.gpUpdate && window.gpUpdate(${payload}); true;`);
    }
  }, [payload, loaded]);

  return (
    <View style={styles.wrap}>
      <WebView
        ref={webRef}
        originWhitelist={['*']}
        source={{ html: HTML }}
        style={styles.web}
        onLoadEnd={() => setLoaded(true)}
        javaScriptEnabled
        domStorageEnabled
      />
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { height: 240, borderRadius: 16, overflow: 'hidden', marginTop: 16, backgroundColor: '#0b1220' },
  web: { flex: 1, backgroundColor: '#0b1220' },
});
