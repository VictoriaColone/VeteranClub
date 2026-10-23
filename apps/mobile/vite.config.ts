import { cpSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { defineConfig, loadEnv } from 'vite';
import uni from '@dcloudio/vite-plugin-uni';
const plugin = (uni as any).default || uni;
// uni's MP compiler expects component sources below src or its adjacent node_modules.
// npm workspaces hoist uni-ui three levels above src. Stage only the two used,
// pinned components inside src; never modify installed packages or duplicate Vue.
const require = createRequire(import.meta.url);
const uiSource = fileURLToPath(new URL('./lib/', new URL(require.resolve('@dcloudio/uni-ui/package.json'), 'file://')));
const uiTarget = fileURLToPath(new URL('./src/components/vendor-ui/', import.meta.url));
mkdirSync(uiTarget, { recursive: true });
for (const component of ['uni-icons', 'uni-number-box']) cpSync(`${uiSource}${component}`, `${uiTarget}${component}`, { recursive: true });
export default defineConfig(({mode}) => {
  const env = loadEnv(mode, process.cwd(), '');
  const manifest = JSON.parse(readFileSync(new URL('./src/manifest.json', import.meta.url), 'utf8'));
  const appid = process.env.WEIXIN_APPID || env.WEIXIN_APPID || manifest['mp-weixin'].appid || '';
  if (appid && !/^wx[0-9a-f]{16}$/.test(appid)) throw new Error('WEIXIN_APPID 必须是 wx 开头的 18 位小程序 AppID');
  if (process.env.UNI_PLATFORM === 'mp-weixin') {
    // uni reads this native config when emitting project.config.json. Account
    // configuration stays local; an empty AppID produces an explicit tourist demo.
    writeFileSync(new URL('./src/project.config.json', import.meta.url), JSON.stringify({
      appid: appid || 'touristappid', projectname: manifest.name, compileType: 'miniprogram',
      miniprogramRoot: './', setting: {urlCheck:true, es6:true, minified:true},
    }, null, 2));
  }
  return { plugins: [plugin()], resolve:{alias:{'@club-ui':uiTarget}}, optimizeDeps: {exclude: ['@stellar/core']}, server: {fs: {allow: ['../..']}} };
});
