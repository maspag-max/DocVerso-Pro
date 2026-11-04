import { defineConfig } from 'vite';

// base relativa: l'app funziona sia su GitHub Pages (/DocVerso/) sia aperta in locale
export default defineConfig({
  base: './',
  build: { target: 'es2022', chunkSizeWarningLimit: 4000 },
  worker: { format: 'es' },
});
