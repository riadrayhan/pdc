import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  base: '/',
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'https://riadrayhan111-rr-locker-api.hf.space',
        changeOrigin: true,
      },
    },
  },
})
