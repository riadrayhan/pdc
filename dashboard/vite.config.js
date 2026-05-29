import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  base: '/',
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'https://rr-locker-api.onrender.com',
        changeOrigin: true,
      },
    },
  },
})
