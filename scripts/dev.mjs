import { spawn } from 'node:child_process';
import { createServer, request } from 'node:http';
const children=[['mobile',5174],['admin',5175]].map(([app])=>spawn('npm',['run','dev','--workspace',`@stellar/${app}`],{stdio:'inherit'}));
const landing=`<!doctype html><meta charset="utf-8"><title>星曜电竞</title><script>location.replace(matchMedia('(max-width:767px)').matches?'/mobile/':'/admin/')</script><a href="/mobile/">移动端 H5</a> · <a href="/admin/">员工后台</a>`;
const server=createServer((req,res)=>{const path=new URL(req.url,'http://localhost').pathname;if(path==='/'){res.writeHead(200,{'Content-Type':'text/html; charset=utf-8'});res.end(landing);return;}const mobile=path.startsWith('/mobile');const port=mobile?5174:5175;const upstream=request({hostname:'127.0.0.1',port,path:req.url,method:req.method,headers:{...req.headers,host:`127.0.0.1:${port}`}},r=>{res.writeHead(r.statusCode||200,r.headers);r.pipe(res);});upstream.on('error',()=>{res.writeHead(503,{'Content-Type':'text/html; charset=utf-8'});res.end('<meta http-equiv="refresh" content="2">应用正在启动，请稍候…');});req.pipe(upstream);});
server.listen(4173,'127.0.0.1',()=>console.log('Local: http://127.0.0.1:4173/\nMobile: http://127.0.0.1:4173/mobile/\nAdmin: http://127.0.0.1:4173/admin/'));
function stop(){children.forEach(c=>c.kill('SIGTERM'));server.close(()=>process.exit());}process.on('SIGTERM',stop);process.on('SIGINT',stop);
