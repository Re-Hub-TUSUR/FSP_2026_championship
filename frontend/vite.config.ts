import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Если backend не запущен, запросы к /api падают и клиент переходит в демо-режим на localStorage.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // agent: false отключает пул соединений — иначе прокси переиспользует закрытый сокет и отдаёт 500.
    proxy: { '/api': { target: 'http://localhost:8080', changeOrigin: true, agent: false } }
  }
});

