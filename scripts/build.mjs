import { spawnSync } from 'node:child_process';
import { mkdirSync, rmSync, cpSync, writeFileSync } from 'node:fs';
const base=process.env.SITE_BASE_PATH||'/';
if(!/^\/(?:[A-Za-z0-9_-]+\/)*$/.test(base))throw new Error('SITE_BASE_PATH 必须为 / 或 /项目名/ 格式');
const output=process.env.SITE_OUTPUT_DIR||'dist';
if(!/^dist(?:-[a-z0-9-]+)?$/.test(output))throw new Error('SITE_OUTPUT_DIR 必须为 dist 或 dist-名称');
const env={...process.env,UNI_H5_BASE:`${base}mobile/`,VITE_SITE_BASE_PATH:base};
for(const app of ['mobile','admin']){const r=spawnSync('npm',['run','build','--workspace',`@stellar/${app}`],{stdio:'inherit',env});if(r.status!==0)process.exit(r.status||1);}
rmSync(output,{recursive:true,force:true});mkdirSync(output,{recursive:true});
cpSync('apps/mobile/dist/build/h5',`${output}/mobile`,{recursive:true});cpSync('apps/admin/dist',`${output}/admin`,{recursive:true});
writeFileSync(`${output}/.nojekyll`,'');
writeFileSync(`${output}/index.html`,`<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>星曜电竞 · 俱乐部平台</title><meta name="description" content="星曜电竞俱乐部，用户游戏协作与任务订单平台。交互演示，数据和支付均为模拟。"><script>location.replace('${base}mobile/#/pages/index/index')</script></head><body style="background:#101218;color:#d9fd88;font:16px system-ui;padding:40px"><p>星曜电竞 · 一起出发</p><p><a href="${base}mobile/" style="color:inherit">进入星曜电竞</a></p></body></html>`);
console.log(`Built → ${output}/admin/ + ${output}/mobile/ (base: ${base})`);
