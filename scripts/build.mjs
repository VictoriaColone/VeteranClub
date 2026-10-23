import { spawnSync } from 'node:child_process';
import { mkdirSync, rmSync, cpSync, writeFileSync } from 'node:fs';
for(const app of ['mobile','admin']){const r=spawnSync('npm',['run','build','--workspace',`@stellar/${app}`],{stdio:'inherit',env:process.env});if(r.status!==0)process.exit(r.status||1);}
rmSync('dist',{recursive:true,force:true});mkdirSync('dist',{recursive:true});
cpSync('apps/mobile/dist/build/h5','dist/mobile',{recursive:true});cpSync('apps/admin/dist','dist/admin',{recursive:true});
writeFileSync('dist/index.html',`<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>星曜电竞 · 俱乐部平台</title><meta name="description" content="星曜电竞俱乐部，用户游戏协作与任务订单平台。交互演示，数据和支付均为模拟。"><script>location.replace('/mobile/#/pages/index/index')</script></head><body style="background:#101218;color:#d9fd88;font:16px system-ui;padding:40px"><p>星曜电竞 · 一起出发</p><p><a href="/mobile/" style="color:inherit">进入星曜电竞</a></p></body></html>`);
console.log('Built → dist/admin/ + dist/mobile/');
