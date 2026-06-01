// ---- Backend ----
function resolveApiBase(): string {
  const env = import.meta.env.VITE_API_BASE as string | undefined;
  if (env) return env;
  if (typeof window !== 'undefined') {
    const { hostname, protocol } = window.location;
    if (hostname.endsWith('carcamodev.site')) {
      return `${protocol}//gpsapi.carcamodev.site`;
    }
  }
  return 'http://127.0.0.1:8787';
}

export const API_BASE = resolveApiBase();
export const WS_URL =
  (import.meta.env.VITE_WS_URL as string | undefined) ??
  API_BASE.replace(/^http/, 'ws') + '/ws';

// ---- Map styles ----
// All free & token-less (CARTO basemaps). Optionally, a Mapbox style can be
// enabled by providing VITE_MAPBOX_TOKEN — MapLibre's API is Mapbox-GL-v1
// compatible, so it's a drop-in. We keep CARTO as the default so the dashboard
// stays fully open-source with no account required.
const MAPBOX_TOKEN = import.meta.env.VITE_MAPBOX_TOKEN as string | undefined;

export interface MapStyleOption {
  id: string;
  label: string;
  theme: 'dark' | 'light';
  url: string;
}

export const MAP_STYLES: MapStyleOption[] = [
  {
    id: 'dark',
    label: 'Dark Matter',
    theme: 'dark',
    url: 'https://basemaps.cartocdn.com/gl/dark-matter-gl-style/style.json',
  },
  {
    id: 'light',
    label: 'Positron',
    theme: 'light',
    url: 'https://basemaps.cartocdn.com/gl/positron-gl-style/style.json',
  },
  {
    id: 'voyager',
    label: 'Voyager',
    theme: 'light',
    url: 'https://basemaps.cartocdn.com/gl/voyager-gl-style/style.json',
  },
  // Mapbox option only shown when a token is supplied.
  ...(MAPBOX_TOKEN
    ? [
        {
          id: 'mapbox-dark',
          label: 'Mapbox Dark',
          theme: 'dark' as const,
          url: `https://api.mapbox.com/styles/v1/mapbox/dark-v11?access_token=${MAPBOX_TOKEN}`,
        },
        {
          id: 'mapbox-streets',
          label: 'Mapbox Streets',
          theme: 'light' as const,
          url: `https://api.mapbox.com/styles/v1/mapbox/streets-v12?access_token=${MAPBOX_TOKEN}`,
        },
      ]
    : []),
];

export function styleById(id: string): MapStyleOption {
  return MAP_STYLES.find((s) => s.id === id) ?? MAP_STYLES[0];
}
