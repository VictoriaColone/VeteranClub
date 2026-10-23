import { defineConfig } from 'vite';
import uni from '@dcloudio/vite-plugin-uni';
const plugin = (uni as any).default || uni;
export default defineConfig({ plugins: [plugin()], server: {fs: {allow: ['../..']}} });
