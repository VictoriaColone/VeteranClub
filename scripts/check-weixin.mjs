import assert from 'node:assert/strict';
import {readFileSync,readdirSync,statSync,existsSync} from 'node:fs';
import path from 'node:path';
const root=path.resolve('apps/mobile/dist/build/mp-weixin');
function files(dir){return readdirSync(dir).flatMap(name=>{const p=path.join(dir,name);return statSync(p).isDirectory()?files(p):[p];});}
const all=files(root);
const project=JSON.parse(readFileSync(path.join(root,'project.config.json'),'utf8'));
const app=JSON.parse(readFileSync(path.join(root,'app.json'),'utf8'));
assert.equal(project.compileType,'miniprogram');
assert.match(project.appid,/^(wx[0-9a-f]{16}|touristappid)$/);
assert.equal(project.setting.urlCheck,true,'保留合法域名校验');
assert.ok(app.pages.includes('pages/index/index'));
for(const json of all.filter(p=>p.endsWith('.json'))){
  const config=JSON.parse(readFileSync(json,'utf8'));
  for(const reference of Object.values(config.usingComponents||{})){
    const target=path.resolve(reference.startsWith('/')?root:path.dirname(json),reference.replace(/^\//,''));
    assert.ok(target.startsWith(root+path.sep),'组件不可引用构建目录以外文件');
    for(const ext of ['.js','.json','.wxml','.wxss'])assert.ok(existsSync(target+ext),`缺少组件文件: ${target+ext}`);
  }
}
const js=all.filter(p=>p.endsWith('.js')).map(p=>readFileSync(p,'utf8')).join('\n');
assert.ok(!/new (?:URL|Intl\.DateTimeFormat)\(/.test(js),'模拟服务不能依赖浏览器 URL / Intl');
assert.ok(!/\.\.\/\.\.\/node-modules/.test(js),'不允许残留工作区外部组件路径');
const pageStyle=readFileSync(path.join(root,'pages/index/index.wxss'),'utf8');
assert.ok(pageStyle.includes('.is-weixin'),'缺少微信小程序布局适配');
const appStyle=readFileSync(path.join(root,'app.wxss'),'utf8');
assert.ok(!appStyle.includes(':root'),'小程序样式不应包含浏览器根选择器');
assert.ok(appStyle.includes('--club-primary'),'缺少共享主题');
const bytes=all.reduce((n,p)=>n+statSync(p).size,0);
assert.ok(bytes<2*1024*1024,'主包大于 2 MiB，需要分包');
console.log(`WeChat build checked: ${all.length} files, ${(bytes/1024).toFixed(0)} KiB, ${project.appid==='touristappid'?'游客模拟项目':'已配置 AppID'}。`);
console.log('编译产物校验通过；开发者工具、真机、微信登录及支付尚需独立验收。');
