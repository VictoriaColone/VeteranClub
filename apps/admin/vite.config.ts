import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
export default defineConfig({ base: `${process.env.VITE_SITE_BASE_PATH || '/'}admin/`, plugins: [vue()], optimizeDeps: {exclude: ['@stellar/core']}, server: {fs: {allow: ['../..']}}, build: {outDir: 'dist', emptyOutDir: true} });
