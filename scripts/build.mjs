import { spawnSync } from 'node:child_process';
import { mkdirSync, rmSync, cpSync, writeFileSync } from 'node:fs';
for(const app of ['mobile','admin']){const r=spawnSync('npm',['run','build','--workspace',`@stellar/${app}`],{stdio:'inherit',env:process.env});if(r.status!==0)process.exit(r.status||1);}
rmSync('dist',{recursive:true,force:true});mkdirSync('dist',{recursive:true});
cpSync('apps/mobile/dist/build/h5','dist/mobile',{recursive:true});cpSync('apps/admin/dist','dist/admin',{recursive:true});
writeFileSync('dist/index.html',`<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>星曜电竞 · 俱乐部平台</title><meta name="description" content="星曜电竞俱乐部，老板与打手移动端、员工运营工作台。交互演示，数据和支付均为模拟。"><script>location.replace(matchMedia('(max-width: 767px)').matches?'/mobile/#/pages/index/index':'/admin/')</script></head><body style="background:#101218;color:#d9fd88;font:16px system-ui;padding:40px"><p>星曜电竞 · 选择你的工作空间</p><p><a href="/mobile/" style="color:inherit">老板／打手 H5</a></p><p><a href="/admin/" style="color:inherit">员工运营后台</a></p></body></html>`);
console.log('Built → dist/admin/ + dist/mobile/');
