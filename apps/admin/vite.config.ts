import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
export default defineConfig({ base: `${process.env.VITE_SITE_BASE_PATH || '/'}admin/`, plugins: [vue()], optimizeDeps: {exclude: ['@stellar/core']}, server: {proxy:{'/api':{target:process.env.HTTP_API_UPSTREAM||'http://127.0.0.1:8080',changeOrigin:false,rewrite:path=>path.replace(/^\/api/,'')}},fs: {allow: ['../..']}}, build: {outDir: 'dist', emptyOutDir: true} });
