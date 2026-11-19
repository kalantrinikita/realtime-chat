import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The dev server proxies to the Spring Boot app so the browser only ever talks to one origin
// (http://localhost:5173), exactly like production where Spring serves the UI itself. That keeps
// the session cookie working for both REST and the WebSocket, and Spring's same-origin check on
// /ws passes because Vite forwards the original Host header (changeOrigin is left off).
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
