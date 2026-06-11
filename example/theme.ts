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
  /** Android ripple color for pressables. */
  ripple: string;
  /** Hairline divider between list rows. */
  divider: string;
  /** Tinted backgrounds for status pills. */
  goodBg: string;
  badBg: string;
  /** Card elevation (light theme uses shadows; dark relies on borders). */
  elevation: number;
}

export const DARK: Theme = {
  name: 'dark',
  bg: '#0b1017',
  card: '#151c27',
  cardBorder: 'rgba(255,255,255,0.07)',
  inner: 'rgba(255,255,255,0.05)',
  text: '#e8edf4',
  textDim: '#93a4b8',
  textFaint: '#64748b',
  primary: '#4c8dff',
  primaryText: '#ffffff',
  accent: '#38bdf8',
  good: '#34d399',
  bad: '#f87171',
  chip: 'rgba(255,255,255,0.06)',
  toolBg: 'rgba(76,141,255,0.14)',
  toolText: '#8ab4ff',
  ripple: 'rgba(255,255,255,0.12)',
  divider: 'rgba(255,255,255,0.06)',
  goodBg: 'rgba(52,211,153,0.14)',
  badBg: 'rgba(248,113,113,0.14)',
  elevation: 0,
};

export const LIGHT: Theme = {
  name: 'light',
  bg: '#f4f6fa',
  card: '#ffffff',
  cardBorder: 'rgba(15,23,42,0.06)',
  inner: 'rgba(15,23,42,0.04)',
  text: '#16202e',
  textDim: '#566476',
  textFaint: '#94a3b8',
  primary: '#1a66ff',
  primaryText: '#ffffff',
  accent: '#0284c7',
  good: '#0a9b6c',
  bad: '#d93838',
  chip: 'rgba(15,23,42,0.05)',
  toolBg: 'rgba(26,102,255,0.10)',
  toolText: '#1a55d6',
  ripple: 'rgba(15,23,42,0.10)',
  divider: 'rgba(15,23,42,0.07)',
  goodBg: 'rgba(10,155,108,0.12)',
  badBg: 'rgba(217,56,56,0.10)',
  elevation: 2,
};

export const THEMES: Record<ThemeName, Theme> = { dark: DARK, light: LIGHT };
