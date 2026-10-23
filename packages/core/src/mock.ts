import { createSeed, STORAGE_KEY } from './seed';
import { canServe, orderMembers, participantEarnings, isStaff, type ApiResponse, type Audit, type Database, type Dashboard, type Order, type OrderInput, type Role, type Session, type StorageAdapter, type Transport, type Wallet } from './types';
export class ApiError extends Error { constructor(public code: number,message: string){super(message);this.name='ApiError';} }
export class MemoryStorage implements StorageAdapter { private data=new Map<string,string>(); getItem(k:string){return this.data.get(k)??null;} setItem(k:string,v:string){this.data.set(k,v);} }
const clone=<T>(data:T):T=>JSON.parse(JSON.stringify(data));
function check(condition: unknown,message: string,code=400): asserts condition { if(!condition)throw new ApiError(code,message); }
const can=(s:Session,...roles:Role[])=>check(roles.includes(s.role),'当前身份没有此操作权限',403);
const paid=(o:Order)=>!!o.paidAt;
const unsettled=(o:Order)=>o.status==='completed'&&o.refundStatus!=='pending'&&orderMembers(o).some(m=>!m.settlementId)&&o.earnings>0;
const unsettledAmount=(o:Order)=>orderMembers(o).filter(m=>!m.settlementId).reduce((n,m)=>n+participantEarnings(o,m.playerId),0);
const day=(n:number)=>new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai'}).format(n);
const id=(prefix:string)=>`${prefix}${Date.now().toString(36)}${Math.random().toString(36).slice(2,7)}`;
export function createMockServer(storage: StorageAdapter,clock=()=>Date.now()) {
  function read():Database {
    const raw=storage.getItem(STORAGE_KEY);
    if(raw){try{const db=JSON.parse(raw) as Database;
      if(db.schema===1){const seed=createSeed(clock());db.schema=2;db.applications=[];
        db.players.forEach(p=>p.qualifications=p.approved?['hourly','escort']:[]);
        seed.players.filter(p=>!db.players.some(old=>old.id===p.id)).forEach(p=>db.players.push(p));
        db.orders.forEach(o=>{o.serviceType='hourly';o.task='';o.team=o.playerId?[{playerId:o.playerId,name:o.playerName||'',settlementId:o.settlementId}]:[];if(['serving','confirming','completed'].includes(o.status))o.startedAt=o.paidAt||o.createdAt;});
        const neutral=(v:string)=>v.replace(/\u8001\u677f/g,'用户').replace(/\u6253\u624b/g,'队友').replace(/\u966a\u73a9/g,'组队');
        db.audit.forEach(a=>{a.action=neutral(a.action);a.detail=neutral(a.detail);});db.settings.announcement=neutral(db.settings.announcement);db.orders.forEach(o=>{o.note=neutral(o.note);o.timeline.forEach(t=>{t.label=neutral(t.label);if(t.note)t.note=neutral(t.note);});});
      }
      if(db.schema===2){if(['p-hourly','p-escort'].some(id=>!db.players.some(p=>p.id===id))){const seed=createSeed(clock());seed.players.filter(p=>!db.players.some(old=>old.id===p.id)).forEach(p=>db.players.push(p));storage.setItem(STORAGE_KEY,JSON.stringify(db));}return db;}
    }catch{/* recreate corrupt demo data */}}
    const db=createSeed(clock());storage.setItem(STORAGE_KEY,JSON.stringify(db));return db;
  }
  function save(db:Database){db.revision++;storage.setItem(STORAGE_KEY,JSON.stringify(db));}
  function audit(db:Database,s:Session,action:string,target:string,detail:string){const a:Audit={id:id('log-'),at:clock(),actor:s.name,action,target,detail};db.audit.unshift(a);db.audit=db.audit.slice(0,300);}
  function event(o:Order,s:Session,label:string,note?:string){o.timeline.push({at:clock(),actor:s.name,label,note});}
  function owner(o:Order,s:Session){check(o.bossId===s.userId,'只能操作自己的订单',403);}
  function playerOwner(o:Order,s:Session){check(!!s.playerId&&o.playerId===s.playerId,'只能操作分配给自己的订单',403);}
  function expire(db:Database){let changed=false;db.orders.forEach(o=>{if(o.status==='unpaid'&&clock()-o.createdAt>=db.settings.unpaidTimeoutMinutes*60000){o.status='cancelled';o.timeline.push({at:clock(),actor:'系统',label:'超时未支付，订单已关闭'});changed=true;}});if(changed)save(db);}
  function dispatch(method:string,path:string,body:any={},token?:string):ApiResponse<unknown>{
    const db=read();expire(db);const url=new URL(path,'https://mock.local');const route=url.pathname.replace(/\/$/,'');const q=url.searchParams;let data:unknown;
    if(route==='/v1/auth/mock-login'&&method==='POST'){
      const roles:Role[]=['user','boss','player','admin','customer_service','finance'];check(roles.includes(body.role),'请选择有效的演示身份');
      const identities:Record<Role, Omit<Session,'token'|'role'>>={user:{userId:'u-boss',name:'林序',playerId:'p0'},boss:{userId:'u-boss',name:'林序'},player:{userId:'u-player',name:'岚',playerId:'p1'},admin:{userId:'u-admin',name:'陈安'},customer_service:{userId:'u-cs',name:'许知'},finance:{userId:'u-finance',name:'沈禾'}};
      let identity=identities[body.role as Role];if(body.role==='user'&&body.accountId){const p=db.players.find(p=>p.userId===body.accountId);check(p,'演示账号不存在');identity={userId:p.userId,name:p.name,playerId:p.id};}const profile=db.players.find(p=>p.userId===identity.userId);const session:Session={token:id('mock-'),role:body.role,...identity,playerId:profile?.id};db.sessions.push(session);db.sessions=db.sessions.slice(-100);save(db);return {code:0,message:'模拟登录成功',data:session,requestId:id('req-')};
    }
    const s=db.sessions.find(v=>v.token===token);check(s,'请先登录演示账号',401);const profile=db.players.find(p=>p.userId===s.userId);if(!isStaff(s.role))s.playerId=profile?.id;
    if(route==='/v1/auth/logout'&&method==='POST'){db.sessions=db.sessions.filter(v=>v.token!==token);save(db);data=null;}
    else if(route==='/v1/catalog/games'&&method==='GET')data=db.games;
    else if(route==='/v1/club'&&method==='GET')data=db.settings;
    else if(route==='/v1/players'&&method==='GET'){data=db.players.filter(p=>(isStaff(s.role)||p.approved||p.userId===s.userId)&&(!q.get('gameId')||p.gameIds.includes(q.get('gameId')!))).map(p=>({...p,busy:db.orders.some(o=>orderMembers(o).some(m=>m.playerId===p.id)&&['accepted','serving','confirming'].includes(o.status))}));}
    else if(route==='/v1/orders'&&method==='GET'){
      const scope=q.get('scope')||'mine';let list=db.orders;
      if(!isStaff(s.role)){
        if(scope==='hall')list=list.filter(o=>o.bossId!==s.userId&&o.status==='waiting'&&o.refundStatus!=='pending'&&(!o.preferredPlayerId||o.preferredPlayerId===s.playerId));
        else list=list.filter(o=>o.bossId===s.userId||orderMembers(o).some(m=>m.playerId===s.playerId));
      }
      if(q.get('serviceType'))list=list.filter(o=>o.serviceType===q.get('serviceType'));
      if(q.get('status')&&q.get('status')!=='all')list=list.filter(o=>o.status===q.get('status'));
      if(q.get('refund')&&q.get('refund')!=='all')list=list.filter(o=>o.refundStatus===q.get('refund'));
      if(q.get('gameId'))list=list.filter(o=>o.gameId===q.get('gameId'));
      const term=q.get('query')?.trim().toLowerCase();if(term)list=list.filter(o=>[o.id,o.bossName,o.playerName,o.gameName].join(' ').toLowerCase().includes(term));
      data=list.sort((a,b)=>b.createdAt-a.createdAt);
    }
    else if(route==='/v1/orders'&&method==='POST'){
      check(!isStaff(s.role),'员工账号请使用用户端下单',403);const b=body as OrderInput;const serviceType=b.serviceType||'hourly';check(['hourly','escort'].includes(serviceType),'订单类型无效');const game=db.games.find(g=>g.id===b.gameId);check(game,'请选择游戏');check(game.regions.includes(b.region),'请选择有效的游戏区服');check(profile&&canServe(profile,serviceType,game.id),`请先通过${serviceType==='escort'?'护航':'组队'}考核并取得该游戏的发单资格`,403);check(Number.isInteger(b.quantity)&&b.quantity>=1&&b.quantity<=8,'服务时长应为 1～8 小时');check((b.note||'').length<=200,'备注不能超过 200 字');
      if(serviceType==='escort'){check(String(b.task||'').trim().length>=5&&String(b.task).length<=200,'请填写 5～200 字的指定任务及验收标准');check(Number.isInteger(b.taskPrice)&&b.taskPrice!>=100&&b.taskPrice!<=100000,'任务金额应为 1～1000 元');check(b.quantity===1,'护航任务按整单计价');}
      const p=b.playerId?db.players.find(p=>p.id===b.playerId):undefined;if(b.playerId)check(p?.userId!==s.userId&&p?.approved&&p.available&&canServe(p,serviceType,game.id),'该队友当前不可预约，请选择其他队友');
      const price=serviceType==='escort'?b.taskPrice!:(p?.price??Math.min(...db.players.filter(p=>p.approved&&canServe(p,'hourly',game.id)).map(p=>p.price)));check(Number.isFinite(price),'该游戏暂未开放此服务');const amount=price*b.quantity;
      const o:Order={serviceType,task:serviceType==='escort'?String(b.task).trim():'',team:[],id:`XY${new Date(clock()).toISOString().slice(2,10).replace(/-/g,'')}${db.sequence++}`,bossId:s.userId,bossName:s.name,gameId:game.id,gameName:game.name,region:b.region,preferredPlayerId:p?.id,preferredPlayerName:p?.name,unitPrice:price,quantity:b.quantity,amount,commissionBps:db.settings.commissionBps,earnings:Math.floor(amount*(10000-db.settings.commissionBps)/10000),status:'unpaid',refundStatus:'none',note:b.note?.trim()||'',createdAt:clock(),timeline:[{at:clock(),actor:s.name,label:'订单已创建'}]};db.orders.unshift(o);audit(db,s,'创建订单',o.id,`${game.name} · ${serviceType==='escort'?'护航任务':b.quantity+' 小时'}`);save(db);data=o;
    }
    else if(/^\/v1\/orders\/[^/]+(?:\/[^/]+)?$/.test(route)){
      const [, , ,orderId,action]=route.split('/');const o=db.orders.find(o=>o.id===orderId);check(o,'订单不存在',404);
      if(method==='GET'&&!action){check(isStaff(s.role)||o.bossId===s.userId||orderMembers(o).some(m=>m.playerId===s.playerId),'无权查看该订单',403);data=o;}
      else if(method==='POST'){
        if(action==='pay'){
          owner(o,s);if(paid(o)){data=o;}else{check(profile&&canServe(profile,o.serviceType,o.gameId),'尚未取得该类型与游戏的发单资格，不能支付发布订单',403);check(o.status==='unpaid','订单已关闭，无法支付');o.status='waiting';o.paidAt=clock();o.paymentId=id('WXMOCK-');event(o,s,'模拟微信支付成功');audit(db,s,'模拟支付',o.id,`支付 ${(o.amount/100).toFixed(2)} 元`);data=o;}
        }else if(action==='accept'||action==='assign'){
          if(action==='accept')check(!isStaff(s.role),'员工账号不能接单',403);else can(s,'admin','customer_service');check(['waiting','accepted'].includes(o.status),'该订单当前不可接单或改派');check(o.refundStatus!=='pending','售后处理中，暂不可派单');
          const pid=action==='accept'?s.playerId:body.playerId;const p=db.players.find(p=>p.id===pid);check(p?.approved&&p.available&&canServe(p,o.serviceType,o.gameId),'队友未通过审核、已休息或不支持该游戏');
          check(p.userId!==o.bossId,'不能接取自己发布的订单');if(action==='accept'){check(o.status==='waiting','订单已被其他队友接走');check(!o.preferredPlayerId||o.preferredPlayerId===p.id,'这是其他队友的指定订单');}
          check(!db.orders.some(other=>other.id!==o.id&&orderMembers(other).some(m=>m.playerId===p.id)&&['accepted','serving','confirming'].includes(other.status)),'该队友还有未完成订单，请稍后再试');
          const prev=o.playerName;o.playerId=p.id;o.playerName=p.name;o.team=[{playerId:p.id,name:p.name}];o.status='accepted';event(o,s,prev?'订单已改派':'队友已接单',`${prev?prev+' → ':''}${p.name}${body.reason?' · '+body.reason:''}`);audit(db,s,action==='accept'?'接单':'派单',o.id,`分配给 ${p.name}`);data=o;
        }else if(action==='add-assistant'){
          playerOwner(o,s);check(o.serviceType==='escort'&&o.status==='accepted'&&o.refundStatus!=='pending','仅待开始的护航订单可以添加副手');const p=db.players.find(p=>p.id===body.playerId);check(p?.approved&&p.available&&canServe(p,'escort',o.gameId),'副手需通过护航考核、在线且支持该游戏');check(p.userId!==o.bossId,'下单用户不能参与接取自己的订单');check(!orderMembers(o).some(m=>m.playerId===p.id),'该成员已在协作名单中');check(!db.orders.some(other=>other.id!==o.id&&orderMembers(other).some(m=>m.playerId===p.id)&&['accepted','serving','confirming'].includes(other.status)),'该成员还有未完成订单');o.team.push({playerId:p.id,name:p.name});event(o,s,'已添加副手',p.name+' · 完成后均分净报酬');audit(db,s,'添加副手',o.id,p.name);data=o;
        }else if(action==='remove-assistant'){
          playerOwner(o,s);check(o.serviceType==='escort'&&o.status==='accepted'&&o.refundStatus!=='pending','当前不能变更协作成员');check(body.playerId!==o.playerId&&o.team.some(m=>m.playerId===body.playerId),'只能移除副手');o.team=o.team.filter(m=>m.playerId!==body.playerId);event(o,s,'已移除副手');data=o;
        }else if(action==='start'){playerOwner(o,s);check(o.status==='accepted'&&o.refundStatus!=='pending','订单当前无法开始服务');o.status='serving';o.startedAt=clock();o.demoElapsedMs=0;event(o,s,'服务已开始');audit(db,s,'开始服务',o.id,'队友确认开始服务');data=o;
        }else if(action==='demo-elapse'){playerOwner(o,s);check(o.serviceType==='hourly'&&o.status==='serving'&&o.refundStatus!=='pending','仅时长服务可模拟计时');o.demoElapsedMs=o.quantity*3600000;event(o,s,'演示计时已走满','仅模拟环境使用');data=o;
        }else if(action==='finish'){playerOwner(o,s);check(o.status==='serving'&&o.refundStatus!=='pending','订单当前无法结束服务');if(o.serviceType==='escort')check(body.taskCompleted===true,'请确认指定任务已达成，并填写完成记录');else check(clock()-(o.startedAt??clock())+(o.demoElapsedMs||0)>=o.quantity*3600000,'约定服务时长尚未完成');o.taskCompleted=o.serviceType==='escort';check(String(body.note||'').trim().length>=2,'请填写至少 2 字服务记录');o.status='confirming';event(o,s,'服务已结束，等待用户确认',String(body.note).slice(0,200));audit(db,s,'提交完成',o.id,String(body.note).slice(0,200));data=o;
        }else if(action==='confirm'){owner(o,s);check(o.status==='confirming'&&o.refundStatus!=='pending','订单尚未到确认阶段或正在售后');o.status='completed';o.finishedAt=clock();event(o,s,'用户确认完成');audit(db,s,'确认完成',o.id,'报酬计入待结算');data=o;
        }else if(action==='cancel'){if(!['admin','customer_service'].includes(s.role))owner(o,s);check(o.status==='unpaid','已付款订单请走售后退款');o.status='cancelled';event(o,s,'订单已取消');audit(db,s,'取消订单',o.id,'未支付订单已取消');data=o;
        }else if(action==='refund'){
          if(!['admin','customer_service'].includes(s.role))owner(o,s);check(paid(o)&&o.status!=='cancelled','该订单不可申请退款');check(o.refundStatus==='none'||o.refundStatus==='rejected','该订单已有退款申请');check(!o.settlementId,'订单已进入结算批次，请联系客服处理结算调整');
          check(String(body.reason||'').trim().length>=5,'请填写至少 5 字退款原因');const amount=Number(body.amount);check(Number.isInteger(amount)&&amount>0&&amount<=o.amount,'退款金额必须大于零，且不能超过实付金额');o.refundStatus='pending';o.refundReason=String(body.reason).trim().slice(0,200);o.refundAmount=amount;event(o,s,'已申请退款',o.refundReason);audit(db,s,'申请退款',o.id,o.refundReason);data=o;
        }else if(action==='review-refund'){
          can(s,'admin','finance');check(o.refundStatus==='pending','该退款已被处理');check(body.approve===true||body.approve===false,'请选择审核结果');check(String(body.note||'').trim().length>=2,'请填写审核说明');o.refundReviewNote=String(body.note).slice(0,200);
          if(body.approve){o.refundStatus='refunded';const left=o.amount-o.refundAmount!;o.earnings=Math.floor(left*(10000-o.commissionBps)/10000);if(left===0)o.status='cancelled';event(o,s,'模拟原路退款成功',o.refundReviewNote);}else{o.refundStatus='rejected';event(o,s,'退款申请未通过',o.refundReviewNote);}audit(db,s,body.approve?'批准退款':'拒绝退款',o.id,o.refundReviewNote);data=o;
        }else throw new ApiError(404,'模拟接口不存在');save(db);
      }else throw new ApiError(405,'请求方法不支持');
    }
    else if(route==='/v1/applications'&&method==='GET'){data=isStaff(s.role)?db.applications:db.applications.filter(a=>a.userId===s.userId);}
    else if(route==='/v1/applications'&&method==='POST'){
      check(profile&&!isStaff(s.role),'请使用用户账号申请',403);check(['hourly','escort'].includes(body.serviceType),'请选择申请方向');check(Array.isArray(body.gameIds)&&body.gameIds.length>0&&body.gameIds.every((g:unknown)=>db.games.some(game=>game.id===g)),'请选择有效游戏');check(String(body.introduction||'').trim().length>=10&&String(body.introduction).length<=500,'请填写 10～500 字的经验及考核申请说明');check(!profile.qualifications.includes(body.serviceType),'该方向已通过考核');check(!db.applications.some(a=>a.userId===s.userId&&a.serviceType===body.serviceType&&['pending','assessment'].includes(a.status)),'已有申请正在审核或考核中');
      const application={id:id('AP-'),userId:s.userId,playerId:profile.id,name:s.name,serviceType:body.serviceType,gameIds:[...new Set<string>(body.gameIds)],introduction:String(body.introduction).trim(),status:'pending' as const,createdAt:clock()};db.applications.unshift(application);audit(db,s,'提交入驻申请',application.id,body.serviceType==='escort'?'护航资格':'组队资格');save(db);data=application;
    }
    else if(/^\/v1\/admin\/applications\/[^/]+\/review$/.test(route)&&method==='POST'){
      can(s,'admin');const a=db.applications.find(a=>a.id===route.split('/')[4]);check(a,'申请不存在',404);check(['pending','assessment'].includes(a.status),'该申请已处理');check(['assessment','approved','rejected'].includes(body.status),'审核操作无效');check(String(body.note||'').trim().length>=2,'请填写审核或考核说明');if(body.status==='assessment')check(a.status==='pending','已进入考核');if(body.status==='approved'){check(a.status==='assessment','需先通过资料审核并进入考核');check(Number.isInteger(body.score)&&body.score>=60&&body.score<=100,'考核通过需要 60～100 分的成绩');const p=db.players.find(p=>p.id===a.playerId)!;p.qualifications.push(a.serviceType);p.qualificationGames??={};p.qualificationGames[a.serviceType]=a.gameIds;p.gameIds=[...new Set([...p.gameIds,...a.gameIds])];p.approved=true;}a.status=body.status;a.score=body.score;a.reviewNote=String(body.note).trim().slice(0,500);a.reviewedAt=clock();audit(db,s,'审核入驻申请',a.id,a.reviewNote);save(db);data=a;
    }
    else if(route==='/v1/player/availability'&&method==='POST'){check(profile,'用户资料不存在');check(typeof body.available==='boolean','接单状态无效');const p=db.players.find(p=>p.id===s.playerId)!;p.available=body.available;audit(db,s,'接单状态',p.name,p.available?'开始接单':'休息中');save(db);data=p;}
    else if(/^\/v1\/admin\/players\/[^/]+$/.test(route)&&method==='PATCH'){
      can(s,'admin');const p=db.players.find(p=>p.id===route.split('/').at(-1));check(p,'队友不存在',404);
      if(body.approved!==undefined){check(body.approved===false,'请通过入驻申请与考核流程授予资格');p.approved=false;p.qualifications=[];p.qualificationGames={};}if(body.available!==undefined){check(typeof body.available==='boolean','接单状态无效');p.available=body.available;}
      if(body.price!==undefined){check(Number.isInteger(body.price)&&body.price>=100&&body.price<=100000,'单价必须在 1～1000 元之间');p.price=body.price;}audit(db,s,'更新队友',p.name,`审核：${p.approved?'通过':'待审'}；可接单：${p.available?'是':'否'}；单价：${p.price/100} 元`);save(db);data=p;
    }
    else if(route==='/v1/player/wallet'&&method==='GET'){
      check(profile,'用户资料不存在');const orders=db.orders.filter(o=>orderMembers(o).some(m=>m.playerId===s.playerId)&&o.status==='completed');const batches=db.settlements.filter(v=>v.playerId===s.playerId);const settled=batches.filter(v=>v.status==='paid').reduce((n,v)=>n+v.amount,0);
      data={total:orders.reduce((n,o)=>n+participantEarnings(o,s.playerId),0),pending:orders.reduce((n,o)=>n+participantEarnings(o,s.playerId),0)-settled,settled,available:orders.filter(o=>unsettled(o)&&!orderMembers(o).find(m=>m.playerId===s.playerId)?.settlementId).reduce((n,o)=>n+participantEarnings(o,s.playerId),0),orders,settlements:batches} satisfies Wallet;
    }
    else if(route==='/v1/admin/settlements'&&method==='GET'){can(s,'admin','finance');data=db.settlements;}
    else if(route==='/v1/admin/settlements'&&method==='POST'){
      can(s,'admin','finance');const eligible=db.orders.filter(unsettled);const ids=new Set(eligible.flatMap(o=>orderMembers(o).filter(m=>!m.settlementId&&(!body.playerId||m.playerId===body.playerId)).map(m=>m.playerId)));check(ids.size,'暂无可结算订单');const created=[];
      for(const pid of ids){const list=eligible.filter(o=>orderMembers(o).some(m=>m.playerId===pid&&!m.settlementId));const batch={id:id('JS-').toUpperCase(),playerId:pid,playerName:orderMembers(list[0]).find(m=>m.playerId===pid)!.name,orderIds:list.map(o=>o.id),amount:list.reduce((n,o)=>n+participantEarnings(o,pid),0),status:'pending' as const,createdAt:clock()};list.forEach(o=>{o.team.find(m=>m.playerId===pid)!.settlementId=batch.id;o.settlementId||=batch.id;});db.settlements.unshift(batch);created.push(batch);audit(db,s,'生成结算',batch.id,`${batch.playerName} · ${list.length} 笔订单`);}save(db);data=created;
    }
    else if(/^\/v1\/admin\/settlements\/[^/]+\/pay$/.test(route)&&method==='POST'){
      can(s,'admin','finance');const batch=db.settlements.find(v=>v.id===route.split('/')[4]);check(batch,'结算单不存在',404);check(batch.status==='pending','该批次已经付款，请勿重复操作');batch.status='paid';batch.paidAt=clock();audit(db,s,'模拟结算付款',batch.id,`${batch.playerName} · ${(batch.amount/100).toFixed(2)} 元`);save(db);data=batch;
    }
    else if(route==='/v1/admin/dashboard'&&method==='GET'){
      check(isStaff(s.role),'无权查看运营数据',403);const today=day(clock());const todayOrders=db.orders.filter(o=>day(o.createdAt)===today);
      const revenue=(list:Order[])=>list.filter(paid).reduce((n,o)=>n+o.amount-(o.refundStatus==='refunded'?o.refundAmount||0:0),0);
      data={todayRevenue:revenue(db.orders.filter(o=>o.paidAt&&day(o.paidAt)===today)),todayOrders:todayOrders.length,liveOrders:db.orders.filter(o=>['accepted','serving','confirming'].includes(o.status)).length,refundPending:db.orders.filter(o=>o.refundStatus==='pending').length,onlinePlayers:db.players.filter(p=>p.approved&&p.available).length,totalPlayers:db.players.length,availablePlayers:db.players.filter(p=>p.approved&&p.available&&!db.orders.some(o=>orderMembers(o).some(m=>m.playerId===p.id)&&['accepted','serving','confirming'].includes(o.status))).length,unassigned:db.orders.filter(o=>o.status==='waiting'&&o.refundStatus!=='pending').length,pendingPayout:db.orders.filter(unsettled).reduce((n,o)=>n+unsettledAmount(o),0),revenueTrend:Array.from({length:7},(_,i)=>{const d=day(clock()-(6-i)*86400000);const list=db.orders.filter(o=>day(o.createdAt)===d);return {label:d.slice(5).replace('-','/'),revenue:revenue(list),orders:list.length};}),gameDistribution:db.games.map(g=>({name:g.name,orders:db.orders.filter(o=>o.gameId===g.id&&paid(o)).length,color:g.color})),recentOrders:db.orders.slice().sort((a,b)=>b.createdAt-a.createdAt).slice(0,6),activity:db.audit.slice(0,6)} satisfies Dashboard;
    }
    else if(route==='/v1/admin/audit'&&method==='GET'){can(s,'admin');data=db.audit;}
    else if(route==='/v1/admin/settings'&&method==='PATCH'){
      can(s,'admin');check(typeof body.name==='string'&&body.name.trim().length>=2&&body.name.trim().length<=20,'俱乐部名称应为 2～20 字');check(Number.isInteger(body.commissionBps)&&body.commissionBps>=0&&body.commissionBps<=5000,'抽成比例应为 0～50%');check([5,10,15,30].includes(body.unpaidTimeoutMinutes),'支付时限无效');check(String(body.announcement||'').length<=120,'公告不能超过 120 字');db.settings={name:body.name.trim(),commissionBps:body.commissionBps,unpaidTimeoutMinutes:body.unpaidTimeoutMinutes,announcement:String(body.announcement||'')};audit(db,s,'更新配置','俱乐部设置','新抽成规则仅对新订单生效');save(db);data=db.settings;
    }
    else if(route==='/v1/demo/reset'&&method==='POST'){can(s,'admin');const fresh=createSeed(clock());fresh.sessions=db.sessions;save(fresh);data={reset:true};}
    else throw new ApiError(404,`模拟接口不存在：${method} ${route}`);
    return {code:0,message:'ok',data:clone(data),requestId:id('req-')};
  }
  return {dispatch,read:()=>clone(read())};
}
let fallback:MemoryStorage|undefined;
function browserStorage():StorageAdapter {if(typeof localStorage!=='undefined')return localStorage;return fallback??=new MemoryStorage();}
export function createMockTransport(storage:StorageAdapter=browserStorage(),latency=160):Transport {
  const server=createMockServer(storage);
  return async<T>(method:string,path:string,body?:unknown,token?:string):Promise<T>=>{
    if(latency)await new Promise(r=>setTimeout(r,latency+Math.random()*100));
    const run=()=>server.dispatch(method,path,body,token).data as T;
    if(typeof navigator!=='undefined'&&navigator.locks)return navigator.locks.request('stellar-club-mock-db',run);
    return run();
  };
}
