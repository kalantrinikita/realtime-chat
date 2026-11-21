import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The dev server proxies to the Spring Boot app so the browser talks to (http://localhost:5173).
const proxy = {
  '/api': { target: 'http://localhost:8080' },
  '/ws': { target: 'ws://localhost:8080', ws: true },
};

export default defineConfig({
  plugins: [react()],
  server: { port: 5173, proxy },
  preview: { port: 4173, proxy },
  build: { sourcemap: true },
});
