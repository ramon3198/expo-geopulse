export interface Coords {
  latitude: number;
  longitude: number;
  accuracy?: number;
  speed?: number;
}

export interface GeoLocation {
  uuid: string;
  timestamp: number;
  coords: Coords;
  provider?: string;
  isMoving?: boolean;
  confidence?: number;
  isMock?: boolean;
}

export interface VisitMarker {
  uuid: string;
  latitude: number;
  longitude: number;
  dwellMs?: number | null;
}

export interface FeedItem {
  kind: 'trip' | 'visit' | 'driving';
  label: string;
  detail: string;
  at: number;
}

export interface DeviceInfo {
  device: string;
  points: number;
  last_seen: number;
}

export interface TripEvent {
  action: 'start' | 'end';
  trip?: { uuid?: string; distanceMeters?: number };
}

export interface VisitEvent {
  action: 'arrive' | 'depart';
  visit?: { uuid: string; latitude: number; longitude: number; dwellMs?: number | null };
}

export interface DrivingEvent {
  type: string;
  severity: string;
  magnitude: number;
}

export type WsMessage =
  | { type: 'location'; device: string; location: GeoLocation }
  | { type: 'trip'; device: string; event: TripEvent }
  | { type: 'visit'; device: string; event: VisitEvent }
  | { type: 'driving'; device: string; event: DrivingEvent };
