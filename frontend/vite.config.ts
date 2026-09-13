import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Локальный fallback включается только явно через VITE_DEMO_MODE=true.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // agent: false отключает пул соединений — иначе прокси переиспользует закрытый сокет и отдаёт 500.
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true, agent: false },
      // Swagger UI и контракт API отдаёт backend: ссылка «Swagger / OpenAPI» в футере ведёт на /swagger-ui/index.html.
      '/swagger-ui': { target: 'http://localhost:8080', changeOrigin: true, agent: false },
      '/v3/api-docs': { target: 'http://localhost:8080', changeOrigin: true, agent: false },
      '/openapi.yaml': { target: 'http://localhost:8080', changeOrigin: true, agent: false }
    }
  }
});

