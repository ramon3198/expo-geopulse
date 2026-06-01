export type ThemeName = 'dark' | 'light';

export interface Theme {
  name: ThemeName;
  bg: string;
  card: string;
  cardBorder: string;
  inner: string;
  text: string;
  textDim: string;
  textFaint: string;
  primary: string;
  primaryText: string;
  accent: string;
  good: string;
  bad: string;
  chip: string;
  toolBg: string;
  toolText: string;
}

export const DARK: Theme = {
  name: 'dark',
  bg: '#070b14',
  card: '#0e1626',
  cardBorder: 'rgba(255,255,255,0.06)',
  inner: 'rgba(255,255,255,0.04)',
  text: '#e6edf3',
  textDim: '#8294a8',
  textFaint: '#64748b',
  primary: '#3b82f6',
  primaryText: '#ffffff',
  accent: '#38bdf8',
  good: '#34d399',
  bad: '#f87171',
  chip: 'rgba(255,255,255,0.06)',
  toolBg: 'rgba(56,189,248,0.12)',
  toolText: '#38bdf8',
};

export const LIGHT: Theme = {
  name: 'light',
  bg: '#eef2f7',
  card: '#ffffff',
  cardBorder: 'rgba(15,23,42,0.08)',
  inner: 'rgba(15,23,42,0.04)',
  text: '#0f172a',
  textDim: '#5b6b7e',
  textFaint: '#94a3b8',
  primary: '#2563eb',
  primaryText: '#ffffff',
  accent: '#0284c7',
  good: '#059669',
  bad: '#dc2626',
  chip: 'rgba(15,23,42,0.06)',
  toolBg: 'rgba(2,132,199,0.12)',
  toolText: '#0284c7',
};

export const THEMES: Record<ThemeName, Theme> = { dark: DARK, light: LIGHT };
