import {readFileSync,existsSync,readdirSync} from 'node:fs';
import {resolve} from 'node:path';
import {spawn,spawnSync} from 'node:child_process';

const root=resolve(import.meta.dirname,'..');
const env={...process.env};
const local=resolve(root,'.local/server.env');
if(existsSync(local))for(const line of readFileSync(local,'utf8').split(/\r?\n/)){
  if(!line||line.startsWith('#'))continue;const at=line.indexOf('=');if(at>0&&!Object.hasOwn(env,line.slice(0,at)))env[line.slice(0,at)]=line.slice(at+1);
}
const tools=resolve(root,'artifacts/backend-tools');
const javaVersion=spawnSync(env.JAVA_HOME?resolve(env.JAVA_HOME,'bin/java'):'java',['-version'],{encoding:'utf8'});
const major=Number((javaVersion.stderr||javaVersion.stdout||'').match(/version "(\d+)/)?.[1]||0);
if(major<21&&existsSync(tools)){
  const bundled=readdirSync(tools).find(name=>name.startsWith('jdk-21.')&&existsSync(resolve(tools,name,'Contents/Home/bin/java')));
  if(bundled)env.JAVA_HOME=resolve(tools,bundled,'Contents/Home');
}
if(env.JAVA_HOME)env.PATH=resolve(env.JAVA_HOME,'bin')+':'+(env.PATH||'');
let executable=resolve(root,'server/mvnw');
if(existsSync(tools)){
  const bundled=readdirSync(tools).find(name=>name.startsWith('apache-maven-')&&existsSync(resolve(tools,name,'bin/mvn')));
  if(bundled)executable=resolve(tools,bundled,'bin/mvn');
}
const command=process.argv[2]||'run',extra=process.argv.slice(3);
let args;
if(command==='run')args=['spring-boot:run'];
else if(command==='start'){executable=env.JAVA_HOME?resolve(env.JAVA_HOME,'bin/java'):'java';args=['-jar',resolve(root,'server/target/norman-server-1.0.0.jar')];}
else if(command==='test')args=['test',...extra];
else if(command==='package')args=['verify',...extra];
else if(['bootstrap-admin','reset-staff'].includes(command)){
  const username=extra[0];if(!username||!/^[A-Za-z0-9_.-]{3,64}$/.test(username))throw new Error('请指定员工账号名称');
  args=['spring-boot:run',`-Dspring-boot.run.arguments=--spring.main.web-application-type=none --club.command=${command} --club.username=${username}`];
}else throw new Error('不支持的服务端命令');
const child=spawn(executable,args,{cwd:resolve(root,'server'),env,stdio:'inherit'});
child.on('error',error=>{console.error('请先安装 Java 21，并检查 Maven Wrapper 网络连接：',error.message);process.exit(1);});
child.on('exit',code=>process.exit(code??1));
for(const signal of ['SIGINT','SIGTERM'])process.on(signal,()=>child.kill(signal));
