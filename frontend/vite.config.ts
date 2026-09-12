import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Локальный fallback включается только явно через VITE_DEMO_MODE=true.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // agent: false отключает пул соединений — иначе прокси переиспользует закрытый сокет и отдаёт 500.
    proxy: { '/api': { target: 'http://localhost:8080', changeOrigin: true, agent: false } }
  }
});

