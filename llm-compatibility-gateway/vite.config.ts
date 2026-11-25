import { defineConfig } from 'vite';

export default defineConfig({
  root: 'web',
  build: {
    outDir: '../dist/web', emptyOutDir: true,
    rollupOptions: {
      onwarn(warning, handler) {
        if (!(warning.code === 'MODULE_LEVEL_DIRECTIVE' && warning.message.includes('"use client"'))) handler(warning);
      },
    },
  },
});
