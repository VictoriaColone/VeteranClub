import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
export default defineConfig({ base: '/admin/', plugins: [vue()], optimizeDeps: {exclude: ['@stellar/core']}, server: {fs: {allow: ['../..']}}, build: {outDir: 'dist', emptyOutDir: true} });
