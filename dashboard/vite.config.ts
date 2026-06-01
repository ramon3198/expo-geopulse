import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    host: '0.0.0.0',
    port: 5180,
    // Allow the dashboard to be served behind these hostnames (reverse proxy / DNS).
    allowedHosts: ['gps.carcamodev.site', '.carcamodev.site', 'localhost'],
  },
})
