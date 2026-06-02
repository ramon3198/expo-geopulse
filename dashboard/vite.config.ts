import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  // Extra hostnames the dev server may be served behind (reverse proxy / tunnel),
  // as a comma-separated list in VITE_ALLOWED_HOSTS, e.g. "app.example.com,.example.com".
  const extraHosts = (env.VITE_ALLOWED_HOSTS ?? '')
    .split(',')
    .map((h) => h.trim())
    .filter(Boolean)

  return {
    plugins: [react()],
    server: {
      host: '0.0.0.0',
      port: 5180,
      allowedHosts: ['localhost', ...extraHosts],
    },
  }
})
