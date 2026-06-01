# GeoPulse companion backend (FastAPI)

A minimal, open-source real-time backend for `expo-geopulse`. It receives the
batches the SDK POSTs to its configured `url`, stores them in SQLite, and
broadcasts every new location/event to connected dashboards over a WebSocket so
the map updates live.

## Run

```bash
cd server
python -m venv .venv && . .venv/Scripts/activate   # Windows: .venv\Scripts\activate
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8787 --reload
```

The API is then at `http://localhost:8787`.

## Endpoints

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/locations` | Ingest a batch of locations (what the SDK sends). Identify the device with an `x-device-id` header. |
| `POST` | `/events/{trip\|visit\|driving}` | Ingest a trip / visit / driving event. |
| `GET` | `/devices` | List devices seen, with point counts. |
| `GET` | `/locations/{device}` | Location history (chronological). |
| `GET` | `/events/{device}` | Recent trips / visits / driving events. |
| `WS` | `/ws` | Live stream of every ingested location/event. |

## Point the SDK at it

```ts
await GeoPulse.ready({
  url: 'http://YOUR_PC_IP:8787/locations',
  autoSync: true,
  headers: { 'x-device-id': 'my-phone' },
});
```

(Use your PC's LAN IP, not `localhost`, so the phone can reach it.)

## Test without a phone

With the server running, inject a simulated Home→Work route:

```bash
python simulate.py --device phone-sim
```

Watch it move on the dashboard (see `../dashboard`).
