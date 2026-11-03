<script setup lang="ts">
import { computed, onMounted, onUnmounted, nextTick, ref, watch, getCurrentInstance } from 'vue';
import {onShow,onHide,onResize,onShareAppMessage,onBackPress} from '@dcloudio/uni-app';
import {deviceDataLabel,getPlatformLayout,isWeixinMiniProgram} from '../../services/platform';
import UniIcons from '@club-ui/uni-icons/uni-icons.vue';
import UniNumberBox from '@club-ui/uni-number-box/uni-number-box.vue';
import { api, useIdentity, httpMode, acceptanceMode } from '../../store';
import SmsLogin from '../../components/SmsLogin.vue';
import GameIcon from '../../components/GameIcon.vue';
import {gestureAxis,type TouchPoint} from '../../services/gesture-axis';
import {orderIncome} from '../../services/order-income';
import {serviceAvailability,receivingOrders,receivingBlockReason} from '../../services/service-availability';
import {matchesPersonalOrderScope,personalOrderScopes,type PersonalOrderScope} from '../../services/order-scope';
import {subscribeStatusChanges} from '../../services/presence';
import {receivableHallOrders,type HallScope} from '../../services/hall-orders';
import {assistantOptions} from '../../services/assistant-options';
import { CLUB_NAME, CLUB_NAME_EN, isClubProduct, ApiError, CATALOG_TTL_MS, mobileRefreshResources, refreshDelay, DEFAULT_ESCORT_TASK, canServe, qualifiedGames, money, compactMoney, statusLabels, serviceLabels, time, orderMembers, participantEarnings, orderSummary, orderUnits, orderCopies, totalOrderHours, MAX_ORDER_COPIES, validContactPhone, type UserIdentity, type Game, type Player, type Order, type Settlement, type ClubSettings, type ServiceType, type Application, type Product } from '@stellar/core';
const props=defineProps<{personalView?:'orders'|'products';initialStatus?:string}>();
const personalView=props.personalView;
// Routed wrappers own uni-app page hooks; this view remains a reusable child.
const pageLifecycleTarget=personalView?getCurrentInstance()?.parent||undefined:undefined;
const identity=useIdentity();
watch(()=>identity.session?.userId,(id,previous)=>{if(httpMode&&id&&id!==previous)void load();});
const platformLayout=ref(getPlatformLayout());
const webViewport=ref<{height:number;width:number;top:number;left:number}|null>(null);
function updateWebViewport(){
  // #ifdef H5
  const viewport=window.visualViewport;webViewport.value={height:viewport?.height||window.innerHeight,width:viewport?.width||window.innerWidth,top:viewport?.offsetTop||0,left:viewport?.offsetLeft||0};
  // #endif
}
function listenWebViewport(add:boolean){
  // #ifdef H5
  if(add){updateWebViewport();window.addEventListener('resize',updateWebViewport);window.visualViewport?.addEventListener('resize',updateWebViewport);window.visualViewport?.addEventListener('scroll',updateWebViewport);}
  else{window.removeEventListener('resize',updateWebViewport);window.visualViewport?.removeEventListener('resize',updateWebViewport);window.visualViewport?.removeEventListener('scroll',updateWebViewport);}
  // #endif
}
const viewportStyle=computed(()=>platformLayout.value?{height:platformLayout.value.windowHeight+'px','--club-viewport-height':platformLayout.value.windowHeight+'px'}:webViewport.value?{height:webViewport.value.height+'px',width:webViewport.value.width+'px',top:webViewport.value.top+'px',left:webViewport.value.left+'px','--club-viewport-height':webViewport.value.height+'px'}:{});
const appStyle=computed(()=>platformLayout.value?{'--club-safe-bottom':platformLayout.value.safeBottom+'px'}:{});
const topbarStyle=computed(()=>platformLayout.value?{height:platformLayout.value.navHeight+'px',paddingTop:platformLayout.value.topPadding+'px',paddingRight:platformLayout.value.rightPadding+'px',paddingBottom:'8px'}:{});
const sheetStyle=computed(()=>platformLayout.value?{height:Math.max(80,platformLayout.value.sheetHeight-(sheet.value==='checkout'?156:0))+'px'}:{});
const tab=ref(personalView==='orders'?'orders':personalView==='products'?'my-products':'home'),selectedGame=ref('all'),serviceType=ref<ServiceType>('hourly'),search=ref(''),statusFilter=ref('all'),orderScope=ref<PersonalOrderScope>('all');
const activeMobileView=computed(()=>tab.value);
const games=ref<Game[]>([]),players=ref<Player[]>([]),orders=ref<Order[]>([]),hall=ref<Order[]>([]),settlements=ref<Settlement[]>([]),settings=ref<ClubSettings|null>(null),applications=ref<Application[]>([]);
const products=ref<Product[]>([]),myProducts=ref<Product[]>([]),selectedProduct=ref<Product|null>(null);
const userIdentity=ref<UserIdentity|null>(null);
const productType=ref<ServiceType>('hourly'),productGame=ref('delta'),productTitle=ref(''),productDescription=ref(''),productPrice=ref('68'),productTask=ref(DEFAULT_ESCORT_TASK);
const hallScope=ref<HallScope>('public');
const hallScopeLabels={public:'公开招募',private:'定向招募'};
const loading=ref(true),refreshing=ref(false),busy=ref(false),error=ref('');
type Sheet='identity-required'|'accounts'|'player'|'checkout'|'pay'|'order'|'refund'|'finish'|'help'|'apply'|'assistants'|'publish-menu'|'publish-product'|'onboarding';
const sheet=ref<Sheet|null>(null),selectedPlayer=ref<Player|null>(null),selectedOrder=ref<Order|null>(null);
// Only one scroll surface owns a gesture: page, horizontal status tabs, or sheet.
const contentScrollTop=ref(0),horizontalAxis=ref<'x'|'y'|null>(null);
let lastContentScrollTop=0,horizontalOrigin:TouchPoint|null=null;
type TouchEventLike={touches?:TouchPoint[]};
function trackContentScroll(event:{detail:{scrollTop:number}}){lastContentScrollTop=event.detail.scrollTop;}
function resetContentScroll(){contentScrollTop.value=lastContentScrollTop;void nextTick(()=>{contentScrollTop.value=0;lastContentScrollTop=0;});}
function startHorizontalGesture(event:TouchEventLike){horizontalOrigin=event.touches?.[0]||null;horizontalAxis.value=null;}
function moveHorizontalGesture(event:TouchEventLike){if(!horizontalAxis.value&&horizontalOrigin&&event.touches?.[0])horizontalAxis.value=gestureAxis(horizontalOrigin,event.touches[0]);}
function endHorizontalGesture(){horizontalOrigin=null;horizontalAxis.value=null;}
watch([tab,sheet],endHorizontalGesture);
const checkoutGame=ref('valorant'),checkoutType=ref<ServiceType>('hourly'),region=ref(''),quantity=ref(1),copies=ref(1),contactPhone=ref(''),contactWechat=ref(''),note=ref(''),agreed=ref(true);
const refundReason=ref(''),refundAmount=ref(0),finishNote=ref(''),taskConfirmed=ref(false),applyType=ref<ServiceType>('hourly'),applyGame=ref('delta'),introduction=ref('');
const now=ref(Date.now()),toast=ref('');let toastTimer:ReturnType<typeof setTimeout>;let interval:ReturnType<typeof setTimeout>;let ticker:ReturnType<typeof setInterval>;
const me=computed(()=>players.value.find(p=>p.userId===identity.session?.userId));
const isMine=(o:Order)=>o.bossId===identity.session?.userId;
const isLead=(o:Order)=>o.playerId===me.value?.id;
const isInvited=(o:Order)=>!!me.value&&o.status==='waiting'&&o.preferredPlayerId===me.value.id;
const publishableTypes=computed(()=>(['hourly','escort'] as ServiceType[]).filter(type=>!!me.value&&games.value.some(g=>canServe(me.value!,type,g.id))));
const canReceiveOrders=computed(()=>publishableTypes.value.length>0);
const receiving=computed(()=>receivingOrders(me.value));
const currentAvailability=computed(()=>serviceAvailability(me.value));
const visibleOrderScopes=computed(()=>canReceiveOrders.value?personalOrderScopes:personalOrderScopes.slice(0,1));
watch(canReceiveOrders,allowed=>{if(!allowed){orderScope.value='all';if(tab.value==='hall')navigate('home');if(['publish-menu','publish-product'].includes(sheet.value||''))sheet.value=null;}});
const demoAccounts=[{id:'u-boss',name:'林序'},{id:'u-hourly',name:'小屿'},{id:'u-escort',name:'凛'},{id:'u-player',name:'岚'}];
function accountStatus(userId:string){const p=players.value.find(p=>p.userId===userId);if(!p?.approved)return '尚未入驻 · 暂不可发布商品或接单';return (['hourly','escort'] as const).filter(type=>qualifiedGames(p,type).length).map(type=>(type==='escort'?'护航':'组队')+'：'+qualifiedGames(p,type).map(gameName).join('、')).join('；')||'尚未入驻 · 暂不可发布商品或接单';}

const displayProducts=computed(()=>products.value.filter(p=>(p.serviceType===serviceType.value)&&(selectedGame.value==='all'||p.gameId===selectedGame.value)&&[p.title,p.sellerName,gameName(p.gameId),p.description].join(' ').toLowerCase().includes(search.value.toLowerCase())).sort((a,b)=>Number(isClubProduct(b))-Number(isClubProduct(a))));
const productSeller=(p:Product)=>players.value.find(v=>v.id===p.playerId);
const productStatus=(p:Product)=>isClubProduct(p)?'俱乐部官方':serviceAvailability(productSeller(p)).label;
const orderProvider=(o:Order)=>players.value.find(p=>p.id===o.preferredPlayerId);
const providerName=(o:Order)=>o.preferredPlayerName||orderProvider(o)?.name||'指定成员';
const ownPublished=computed(()=>myProducts.value.filter(p=>p.active).length);
const displayHall=computed(()=>receivableHallOrders(hallScope.value==='public'?hall.value:orders.value,hallScope.value,me.value,identity.session?.userId));
const displayOrders=computed(()=>orders.value.filter(o=>matchesPersonalOrderScope(o,orderScope.value,identity.session?.userId,me.value?.id)&&(statusFilter.value==='all'||(statusFilter.value==='refund'?refundFrozen(o):statusFilter.value==='active'?['waiting','accepted','serving','confirming'].includes(o.status):o.status===statusFilter.value))));
const orderIncomeById=computed(()=>Object.fromEntries(orders.value.map(o=>[o.id,orderIncome(o,me.value?.id,settlements.value)])));
const selectedIncome=computed(()=>selectedOrder.value?orderIncome(selectedOrder.value,me.value?.id,settlements.value):null);
const currentGame=computed(()=>games.value.find(g=>g.id===checkoutGame.value));
const checkoutPrice=computed(()=>selectedProduct.value?.price||0);
const checkoutTotal=computed(()=>checkoutPrice.value*(checkoutType.value==='escort'?copies.value:quantity.value));
const assistantSearch=ref('');
const assistantCandidates=computed(()=>assistantOptions(players.value,selectedOrder.value,assistantSearch.value));
watch(sheet,value=>{if(value==='assistants')assistantSearch.value='';});
const gameName=(id:string)=>games.value.find(g=>g.id===id)?.name||id;
const gameColor=(id:string)=>games.value.find(g=>g.id===id)?.color||'#b0a2fc';
const activeCount=computed(()=>orders.value.filter(o=>['waiting','accepted','serving','confirming'].includes(o.status)).length);
const applicationFor=(type:ServiceType,gameId:string)=>applications.value.find(a=>a.serviceType===type&&a.gameIds.includes(gameId));
const gameQualified=(type:ServiceType,gameId:string)=>!!me.value&&canServe(me.value,type,gameId);
const applicationPending=(type:ServiceType,gameId:string)=>['pending','assessment'].includes(applicationFor(type,gameId)?.status||'');
const qualificationState=(type:ServiceType,gameId:string)=>gameQualified(type,gameId)?'已入驻':applicationFor(type,gameId)?.status==='approved'?'入驻已暂停':applicationLabel(applicationFor(type,gameId));
const applicationLabel=(a?:Application)=>a?({pending:'资料审核中',assessment:'考核进行中',approved:'考核通过',rejected:'未通过 · 可重新申请'}[a.status]):'尚未申请';
const remaining=(o:Order)=>Math.max(0,totalOrderHours(o)*3600000-(now.value-(o.startedAt??now.value))-(o.demoElapsedMs||0));
const countdown=(o:Order)=>{const m=Math.ceil(remaining(o)/60000);return m>0?`剩余 ${Math.floor(m/60)} 小时 ${m%60} 分钟`:'约定时长已完成';};
function notify(message:string){toast.value=message;clearTimeout(toastTimer);toastTimer=setTimeout(()=>toast.value='',3200);}
async function run(job:()=>Promise<void>){if(busy.value)return;busy.value=true;try{await job();}catch(e){notify(e instanceof Error?e.message:'操作失败，请重试');}finally{busy.value=false;}}
let catalogLoadedAt=0,loadedUserId='',refreshAgain=false,refreshVisibleAgain=false;
async function load(silent=false,visibleOnly=false){
  if(refreshing.value){if(!visibleOnly)refreshAgain=true;else refreshVisibleAgain=true;return;}
  if(httpMode&&silent&&!identity.session)return;
  refreshing.value=true;if(!silent)loading.value=true;let requestToken:string|undefined;
  try{
    if(!identity.session){if(httpMode){if(!await identity.restore())return;}else await identity.login('user');}
    const userId=identity.session!.userId,token=identity.session!.token;requestToken=token;
    if(loadedUserId!==userId){orders.value=[];hall.value=[];settlements.value=[];applications.value=[];myProducts.value=[];selectedOrder.value=null;userIdentity.value=null;loadedUserId=userId;}
    const resources=mobileRefreshResources(activeMobileView.value,sheet.value,canReceiveOrders.value,!visibleOnly);
    const updates:Promise<()=>void>[]=[];
    const request=<T,>(promise:Promise<T>,apply:(value:T)=>void)=>updates.push(promise.then(value=>()=>apply(value)));
    const refreshCatalog=!catalogLoadedAt||!silent||Date.now()-catalogLoadedAt>=CATALOG_TTL_MS;
    if(refreshCatalog){request(api.games(),value=>games.value=value);request(api.settings(),value=>settings.value=value);}
    if(resources.includes('players'))request(api.players(),value=>players.value=value);
    if(resources.includes('orders'))request(api.orders(),value=>orders.value=value);
    if(resources.includes('wallet'))request(api.wallet(),value=>settlements.value=value.settlements);
    if(resources.includes('applications'))request(api.applications(),value=>applications.value=value);
    if(resources.includes('products'))request(api.products(),value=>products.value=value);
    if(resources.includes('myProducts'))request(api.products('mine'),value=>myProducts.value=value);
    if(!userIdentity.value||tab.value==='me')request(api.userIdentity(),value=>userIdentity.value=value);
    const apply=await Promise.all(updates);
    if(identity.session?.token!==token)return;
    apply.forEach(update=>update());if(refreshCatalog)catalogLoadedAt=Date.now();
    if(canReceiveOrders.value&&(!visibleOnly||tab.value==='hall')){
      const available=await api.orders({scope:'hall'});
      if(identity.session?.token!==token)return;hall.value=available;
    }else if(!canReceiveOrders.value)hall.value=[];
    if(selectedPlayer.value)selectedPlayer.value=players.value.find(p=>p.id===selectedPlayer.value?.id)||selectedPlayer.value;
    if(selectedOrder.value)selectedOrder.value=orders.value.find(o=>o.id===selectedOrder.value?.id)||hall.value.find(o=>o.id===selectedOrder.value?.id)||selectedOrder.value;
    error.value='';
  }catch(e){
    if(requestToken&&identity.session?.token!==requestToken)return;
    if(e instanceof ApiError&&e.code===401){if(httpMode){identity.clear();sheet.value=null;return;}await identity.login('user');refreshAgain=true;return;}
    error.value=e instanceof Error?e.message:'加载失败';
  }finally{loading.value=false;refreshing.value=false;if(refreshAgain||refreshVisibleAgain){const visible=!refreshAgain;refreshAgain=false;refreshVisibleAgain=false;void load(true,visible);}}
}
const refundFrozen=(o:Order)=>['pending','processing'].includes(o.refundStatus);
async function logout(){await run(async()=>{await identity.logout();sheet.value=null;orders.value=[];hall.value=[];products.value=[];tab.value='home';});}
function openWallet(){uni.navigateTo({url:'/pages/account/wallet'});}
async function requestPayment(channel:string){if(!selectedOrder.value)return;uni.navigateTo({url:'/pages/account/wechat-pay?orderId='+encodeURIComponent(selectedOrder.value.id)});}

async function switchAccount(userId:string){await run(async()=>{await identity.login('user',userId);selectedOrder.value=null;sheet.value=null;orderScope.value='all';statusFilter.value='all';await load();notify('已切换体验账号，可服务项目以入驻结果为准');});}
function openPlayer(p:Player){selectedPlayer.value=p;sheet.value='player';}
function publishGames(type:ServiceType,p?:Player|null){return me.value?qualifiedGames(me.value,type).filter(id=>!p||qualifiedGames(p,type).includes(id)):[];}
let checkoutAttempt=0;
const contactPhoneTouched=ref(false);
watch(sheet,(value,previous)=>{if(previous==='checkout'&&value!=='checkout'){checkoutAttempt++;contactPhone.value='';}});
async function prefillRegistrationPhone(){
  const attempt=++checkoutAttempt,userId=identity.session?.userId;
  try{
    const contact=await api.registrationContact();
    if(attempt===checkoutAttempt&&sheet.value==='checkout'&&identity.session?.userId===userId&&contact.userId===userId&&!contactPhoneTouched.value)contactPhone.value=contact.phone;
  }catch{/* Keep manual entry available if the account has no number or the request fails. */}
}
async function checkOrdering(){
  const userId=identity.session?.userId;const status=await api.userIdentity();if(identity.session?.userId!==userId)return false;
  userIdentity.value=status;if(!status.canOrder){sheet.value='identity-required';return false;}return true;
}
async function book(p?:Player,product?:Product){
  if(busy.value)return;
  const item=product||products.value.find(item=>item.playerId===p?.id&&item.serviceType===serviceType.value&&(selectedGame.value==='all'||item.gameId===selectedGame.value));
  if(!item){notify('该成员暂无对应的在售商品');return;}
  if(item.userId===identity.session?.userId){notify('不能给自己下单');return;}
  busy.value=true;try{
  if(!await checkOrdering())return;
  selectedProduct.value=item;selectedPlayer.value=isClubProduct(item)?null:productSeller(item)||null;
  checkoutType.value=item.serviceType;checkoutGame.value=item.gameId;region.value=games.value.find(g=>g.id===item.gameId)?.regions[0]||'';
  quantity.value=1;copies.value=1;contactPhone.value='';contactWechat.value='';note.value='';contactPhoneTouched.value=false;agreed.value=true;sheet.value='checkout';void prefillRegistrationPhone();
  }catch(e){notify(e instanceof Error?e.message:'暂时无法核对下单资格，请重试');}finally{busy.value=false;}
}
async function createOrder(){await run(async()=>{
  const product=selectedProduct.value;if(!product)return;
  if(product.userId===identity.session?.userId){notify('不能给自己下单');return;}
  if(!agreed.value){notify('请先确认服务说明');return;}
  if(!validContactPhone(contactPhone.value)){notify('请填写有效联系电话（手机或座机）');return;}
  if(!await checkOrdering())return;
  selectedOrder.value=await api.createOrder({productId:product.id,serviceType:product.serviceType,gameId:product.gameId,region:region.value,quantity:product.serviceType==='escort'?1:quantity.value,copies:product.serviceType==='escort'?copies.value:1,contactPhone:contactPhone.value.trim(),contactWechat:contactWechat.value.trim(),note:note.value});
  await load(true);sheet.value='pay';
});}

function payOrder(o:Order){selectedOrder.value=o;sheet.value='pay';}
function openRecruitment(p:Product){if(p.userId===identity.session?.userId){openMyProducts();return;}book(productSeller(p),p);}
function openPublish(type:ServiceType){if(!publishableTypes.value.includes(type)){sheet.value=null;notify('尚未通过对应游戏与类型的商品发布考核');return;}productType.value=type;productGame.value=publishGames(type)[0]||'delta';productTitle.value='';productDescription.value='';productPrice.value=type==='escort'?'128':String((me.value?.price||6800)/100);productTask.value=DEFAULT_ESCORT_TASK;sheet.value='publish-product';}
async function publishProduct(){await run(async()=>{await api.publishProduct({serviceType:productType.value,gameId:productGame.value,title:productTitle.value,description:productDescription.value,price:Math.round(Number(productPrice.value)*100),task:productTask.value});await load(true);openMyProducts();notify('商品发布成功，可在发现页查看');});}
async function toggleProduct(p:Product){await run(async()=>{await api.updateProduct(p.id,!p.active);await load(true);notify(p.active?'商品已下架':'商品已上架');});}
function openOrder(o:Order){selectedOrder.value=o;sheet.value='order';}
async function action(name:string,o=selectedOrder.value,body?:unknown){if(!o)return;if(name==='accept'){const reason=receivingBlockReason(me.value);if(reason){notify(reason);return;}}await run(async()=>{selectedOrder.value=await api.act(o.id,name,body);await load(true);sheet.value='order';if(name==='pay'||name==='accept')openOrders();notify(({pay:'模拟支付成功，等待接单',accept:'接单成功，已切换为服务中',start:'服务已开始，协作名单已锁定',finish:'已提交，等待下单用户确认',confirm:'订单已完成',refund:'已进入售后处理','add-assistant':'副手已加入，报酬按订单抽佣规则计算','demo-elapse':'已模拟完成约定时长'} as Record<string,string>)[name]||'操作成功');});}
function refund(o:Order){selectedOrder.value=o;refundReason.value='';refundAmount.value=o.amount/100;sheet.value='refund';}
function finish(o:Order){selectedOrder.value=o;finishNote.value='';taskConfirmed.value=false;sheet.value='finish';}
function openApply(type:ServiceType,gameId?:string){
  const target=gameId||(selectedGame.value!=='all'?selectedGame.value:games.value.find(g=>!gameQualified(type,g.id))?.id)||games.value[0]?.id;
  if(!target)return;
  if(gameQualified(type,target)){notify('已通过该游戏的对应考核');return;}
  if(applicationPending(type,target)){sheet.value=null;openOnboarding();notify('该游戏该类型正在审核或考核中');return;}
  applyType.value=type;applyGame.value=target;introduction.value='';sheet.value='apply';
}
async function submitApplication(){await run(async()=>{await api.apply({serviceType:applyType.value,gameIds:[applyGame.value],introduction:introduction.value});await load(true);sheet.value=null;openOnboarding();notify('已提交'+gameName(applyGame.value)+(applyType.value==='escort'?'护航':'组队')+'考核申请');});}

function navigate(next:string){if(next==='orders'){openOrders();return;}tab.value=next==='hall'&&!canReceiveOrders.value?'home':next;statusFilter.value='all';resetContentScroll();}
function openOrders(status='all'){
  if(personalView==='orders'){orderScope.value='all';statusFilter.value=status;resetContentScroll();return;}
  sheet.value=null;uni.navigateTo({url:'/pages/account/orders?status='+status});
}
function backToProfile(){uni.navigateBack({fail:()=>uni.reLaunch({url:'/pages/index/index'})});}
function openOnboarding(){sheet.value='onboarding';}
function openRealName(){sheet.value=null;uni.navigateTo({url:'/pages/account/identity'});}
function goToRealName(){navigate('me');openRealName();}
function openAccountSecurity(){sheet.value=null;uni.navigateTo({url:'/pages/account/security'});}
function openMyProducts(){if(!canReceiveOrders.value)return;sheet.value=null;if(personalView!=='products')uni.navigateTo({url:'/pages/account/products'});}
const tabs=[{id:'home',label:'发现',icon:'home'},{id:'hall',label:'接单',icon:'flag'},{id:'me',label:'我的',icon:'person'}];
const visibleTabs=computed(()=>tabs.filter(item=>item.id!=='hall'||canReceiveOrders.value));
let mounted=false,appVisible=true;
let stopPresence:(()=>void)|undefined,presenceLoading=false,presenceAgain=false;
async function refreshPresence(){
  if(!appVisible||!identity.session)return;
  if(presenceLoading){presenceAgain=true;return;}
  const token=identity.session.token;presenceLoading=true;
  try{const latest=await api.players();if(appVisible&&identity.session?.token===token){players.value=latest;if(selectedPlayer.value)selectedPlayer.value=latest.find(p=>p.id===selectedPlayer.value?.id)||selectedPlayer.value;}}
  catch{/* Normal polling handles network and expired-session errors. */}
  finally{presenceLoading=false;if(presenceAgain){presenceAgain=false;void refreshPresence();}}
}
function startPresence(){stopPresence?.();stopPresence=appVisible&&identity.session?subscribeStatusChanges(()=>void refreshPresence()):undefined;}
watch(()=>identity.session?.token,()=>{if(mounted)startPresence();});
function stopTimers(){clearTimeout(interval);clearInterval(ticker);stopPresence?.();stopPresence=undefined;}
function scheduleRefresh(){clearTimeout(interval);if(!appVisible)return;const view=['order','pay','refund','finish','assistants'].includes(sheet.value||'')?'orders':activeMobileView.value;interval=setTimeout(async()=>{try{await load(true,true);}finally{scheduleRefresh();}},refreshDelay('mobile',view));}
function startTimers(){stopTimers();startPresence();scheduleRefresh();ticker=setInterval(()=>now.value=Date.now(),1000);}
watch([tab,sheet],()=>{if(mounted&&appVisible&&!loading.value){void load(true,true);scheduleRefresh();}});
watch(()=>props.initialStatus,status=>{if(status&&['all','active','unpaid','waiting','accepted','serving','confirming','completed','refund'].includes(status))statusFilter.value=status;},{immediate:true});
onBackPress(()=>{if(sheet.value){sheet.value=null;return true;}return false;},pageLifecycleTarget);
onMounted(async()=>{listenWebViewport(true);mounted=true;startTimers();await load();});
onShow(()=>{appVisible=true;platformLayout.value=getPlatformLayout();now.value=Date.now();if(mounted)void load(true,true);startTimers();},pageLifecycleTarget);
onHide(()=>{appVisible=false;stopTimers();},pageLifecycleTarget);
onUnmounted(()=>{listenWebViewport(false);appVisible=false;stopTimers();clearTimeout(toastTimer);});
onResize(()=>{platformLayout.value=getPlatformLayout();updateWebViewport();},pageLifecycleTarget);
onShareAppMessage(()=>({title:(settings.value?.name||CLUB_NAME)+' · 好游戏，一起出发',path:'/pages/index/index'}),pageLifecycleTarget);
</script>

<template>
  <view class="viewport-shell" :style="viewportStyle"><view class="mobile-app" :class="{'is-weixin':isWeixinMiniProgram}" :style="appStyle">
    <scroll-view v-if="httpMode&&!identity.session" scroll-y class="login-scroll"><SmsLogin @authenticated="load()"/></scroll-view>
    <template v-else>
    <view v-if="personalView" class="topbar account-topbar" :style="topbarStyle"><button class="account-back" aria-label="返回我的" @tap="backToProfile"><uni-icons type="arrow-left" :size="23" color="#e9ebf1"/></button><view class="account-page-title">{{personalView==='orders'?'我的订单':'我的商品'}}</view><button class="account-refresh" aria-label="刷新列表" :disabled="refreshing" @tap="load(true)"><uni-icons type="refresh" :size="22" color="#aaaebe"/></button></view>
    <view v-else class="topbar" :style="topbarStyle"><view class="wordmark"><view class="star-mark">✳</view><view>{{settings?.name||CLUB_NAME}}<text>{{CLUB_NAME_EN}}</text></view></view></view>
    <scroll-view class="page-scroll" :class="{'scroll-locked':!!sheet}" :scroll-y="!sheet&&horizontalAxis!=='x'" :scroll-top="contentScrollTop" :show-scrollbar="false" @scroll="trackContentScroll">
    <view v-if="error" class="error-box">{{error}}<button @tap="load()">重新加载</button></view>
    <view v-if="loading" class="loading"><view class="loading-ring"/> 正在加载…</view>
    <view v-else-if="tab==='home'||tab==='hall'" class="content">
      <template v-if="tab==='home'"><view class="greeting">PLAY WITH PURPOSE</view><view class="page-title">发布招募</view></template>
      <template v-if="tab==='home'"><view class="service-switch"><button :class="{active:serviceType==='hourly'}" @tap="serviceType='hourly'"><text>招募队员</text><text>约定时长 · 轻松同行</text></button><button :class="{active:serviceType==='escort'}" @tap="serviceType='escort'"><text>招募护航</text><text>指定任务 · 目标达成</text></button></view>
      <view class="game-filter" role="group" aria-label="按游戏筛选">
        <button :class="{active:selectedGame==='all'}" :aria-pressed="selectedGame==='all'" @tap="selectedGame='all'">
          <view class="game-filter-icon"><view class="all-game-icon"><view/><view/><view/><view/></view></view><text>全部游戏</text>
        </button>
        <button v-for="g in games" :key="g.id" :class="{active:selectedGame===g.id}" :aria-pressed="selectedGame===g.id" @tap="selectedGame=g.id">
          <view class="game-filter-icon"><GameIcon :game-id="g.id" :size="28"/></view><text>{{g.name}}</text>
        </button>
      </view>
      </template>
      <view v-else class="service-switch hall-scope-switch" role="tablist" aria-label="接单订单范围"><button :class="{active:hallScope==='public'}" role="tab" :aria-selected="hallScope==='public'" @tap="hallScope='public'"><text>公开招募</text><text>未指定成员 · 自由接单</text></button><button :class="{active:hallScope==='private'}" role="tab" :aria-selected="hallScope==='private'" @tap="hallScope='private'"><text>定向招募</text><text>指定我接单 · 专属邀请</text></button></view>
      <template v-if="tab==='home'"><view class="section-top"><view class="section-title">{{serviceType==='escort'?'护航服务':'组队服务'}}</view><text class="muted">{{displayProducts.length}} 件在售</text></view><view class="search-box"><uni-icons type="search" color="#858a9d" :size="20"/><input v-model="search" placeholder="搜索商品、游戏或服务成员"/></view>
        <view class="product-list">
          <view v-for="p in displayProducts" :key="p.id" class="product-card" :class="{'club-product':isClubProduct(p)}" @tap="openRecruitment(p)"><view class="product-info"><view class="product-seller"><view class="seller-avatar" :class="{'club-avatar':isClubProduct(p)}" :style="{background:productSeller(p)?.color}">{{isClubProduct(p)?'✳':p.sellerName.slice(0,1)}}</view><view class="product-seller-body"><view class="product-title">{{isClubProduct(p)?CLUB_NAME:p.sellerName}}<text v-if="isClubProduct(p)" class="official-badge">官方</text></view><text class="seller-state" :class="isClubProduct(p)?'official':serviceAvailability(productSeller(p)).state">{{isClubProduct(p)?'随机分配':productStatus(p)}}</text></view></view><view class="product-meta"><view class="product-game-meta"><GameIcon :game-id="p.gameId" :size="14"/><text>{{gameName(p.gameId)}}</text></view><text v-if="p.serviceType==='escort'" class="product-summary">{{p.task}}</text></view><view class="product-bottom"><view class="price">¥{{money(p.price)}}<text>/{{p.serviceType==='hourly'?'小时':'份'}}</text></view><text class="product-buy">{{p.userId===identity.session?.userId?'我的商品':'立即招募'}} ›</text></view></view></view>
        </view><view v-if="!displayProducts.length" class="empty">暂无符合条件的商品<text>试试切换游戏或服务类型</text></view>
      </template>
      <template v-else><view class="section-top hall-heading"><view><view class="section-title">{{hallScopeLabels[hallScope]}}</view><text class="section-hint">{{displayHall.length}} 个机会</text></view></view>
        <view v-for="o in displayHall" :key="o.id" class="order-card"><view class="order-top"><view class="game-label"><view class="mini-game" :style="{background:gameColor(o.gameId)+'20',color:gameColor(o.gameId)}">{{games.find(g=>g.id===o.gameId)?.short}}</view>{{o.gameName}}</view><text class="type-badge" :class="o.serviceType">{{serviceLabels[o.serviceType]}}</text></view><view class="job-task">{{orderUnits(o)}}</view><view class="muted">{{o.bossName}} · {{hallScope==='private'?'指定我接单':'公开招募'}}</view><view class="hall-game-info">{{o.region}} · {{o.serviceType==='escort'?'任务护航':'时长组队'}}</view><view v-if="o.note" class="job-note">{{o.note}}</view><view class="job-footer"><view class="price">¥{{money(o.earnings)}}<text>预计收入</text></view><button class="primary small" :disabled="busy" @tap="action('accept',o)">{{hallScope==='private'?'接受邀请':'立即抢单'}}</button></view></view><view v-if="!displayHall.length" class="empty">{{'暂无'+hallScopeLabels[hallScope]}}<text>{{hallScope==='private'?'用户购买你的商品并付款后，邀请会显示在这里':'这里只展示已入驻游戏及服务类型的公开招募，指定你的订单请查看定向招募。'}}</text></view>
      </template>
    </view>
<view v-else-if="personalView==='orders'" class="content account-page account-orders"><view v-if="canReceiveOrders" class="choice-row order-scope"><button v-for="item in visibleOrderScopes" :key="item.id" :class="{selected:orderScope===item.id}" @tap="orderScope=item.id">{{item.label}}</button></view><scroll-view :scroll-x="horizontalAxis!=='y'" class="order-tabs" @touchstart="startHorizontalGesture" @touchmove="moveHorizontalGesture" @touchend="endHorizontalGesture" @touchcancel="endHorizontalGesture"><view class="order-tabs-inner"><button v-for="f in [{id:'all',label:'全部'},{id:'active',label:'进行中'},{id:'unpaid',label:'待付款'},{id:'waiting',label:'待接单'},{id:'accepted',label:'待开始'},{id:'serving',label:'服务中'},{id:'confirming',label:'待确认'},{id:'completed',label:'已完成'},{id:'refund',label:'售后'}]" :key="f.id" :class="{active:statusFilter===f.id}" @tap="statusFilter=f.id">{{f.label}}</button></view></scroll-view>
      <view v-for="o in displayOrders" :key="o.id" class="order-card" @tap="openOrder(o)"><view class="order-top"><view class="game-label">{{o.gameName}}<text class="type-badge" :class="o.serviceType">{{o.serviceType==='escort'?'护航':'组队'}}</text></view><text class="status" :class="refundFrozen(o)?'refund':o.status">{{refundFrozen(o)?'售后处理中':statusLabels[o.status]}}</text></view><view class="job-task">{{orderSummary(o)}}</view><view class="muted">{{o.region}} · {{isMine(o)?'我下单的':'派给我的'}} · {{orderMembers(o).map(m=>m.name).join('、')||(o.preferredPlayerId?'指定邀请':o.productId?.startsWith('club-')?'俱乐部公开招募':'等待接单')}}</view><view v-if="o.preferredPlayerId" class="order-provider"><view class="seller-avatar" :style="{background:orderProvider(o)?.color}">{{providerName(o).slice(0,1)}}</view><view><text class="provider-caption">指定成员</text><text class="provider-name">{{providerName(o)}}</text></view><text class="provider-progress">{{refundFrozen(o)?'售后处理中':o.playerName&&o.playerId!==o.preferredPlayerId?'已改派给 '+o.playerName:o.status==='waiting'?'等待接受邀请':o.status==='unpaid'?'付款后发送邀请':''}}</text></view><view class="order-meta"><text>{{time(o.createdAt)}}</text><text>订单金额 ¥{{money(o.amount)}}</text></view><view v-if="orderIncomeById[o.id]" class="order-income-line"><view><text>{{orderIncomeById[o.id]?.amountLabel}}</text><text class="order-income-amount">¥{{money(orderIncomeById[o.id]?.amount||0)}}</text></view><text class="income-state" :class="orderIncomeById[o.id]?.status">{{orderIncomeById[o.id]?.label}}</text></view><view class="order-actions" @tap.stop><button class="secondary small" @tap="openOrder(o)">查看详情</button><button v-if="isMine(o)&&o.status==='unpaid'" class="primary small" @tap="payOrder(o)">去付款</button><button v-if="isLead(o)&&o.serviceType==='escort'&&o.status==='accepted'&&!refundFrozen(o)" class="primary small" @tap="selectedOrder=o;sheet='assistants'">添加副手</button><button v-if="isMine(o)&&o.status==='confirming'&&!refundFrozen(o)" class="primary small" @tap="action('confirm',o)">确认完成</button></view></view><view v-if="!displayOrders.length" class="empty">{{orderScope==='assigned'?'暂无派给我的订单':'这里还没有订单'}}</view>
    </view>
    <view v-else-if="personalView==='products'" class="content account-page account-products"><view class="profile-products-toolbar"><text>{{ownPublished}} 件在售 · 共 {{myProducts.length}} 件商品</text><button class="primary small" :disabled="busy" @tap="sheet='publish-menu'">发布商品</button></view><view v-for="p in myProducts" :key="p.id" class="owned-product"><view class="product-title">{{p.title}}</view><view class="owned-product-meta"><GameIcon :game-id="p.gameId" :size="14"/><text>{{gameName(p.gameId)}} · {{p.serviceType==='escort'?'护航':'组队'}} · ¥{{money(p.price)}}/{{p.serviceType==='hourly'?'小时':'份'}}</text></view><view class="product-bottom"><text class="muted">{{p.active?(me&&canServe(me,p.serviceType,p.gameId)?'已上架':'入驻已暂停 · 暂不展示'):'已下架'}}</text><button class="secondary small" :disabled="busy" @tap="toggleProduct(p)">{{p.active?'下架':'重新上架'}}</button></view></view><view v-if="!myProducts.length" class="empty">还没有发布商品<text>发布服务商品，让用户找到你。</text></view></view>
    <view v-else class="content profile-page"><view class="greeting">YOUR SPACE, YOUR PACE</view><view class="profile-header"><view class="profile-avatar">{{identity.session?.name.slice(0,1)}}</view><view class="profile-identity"><view class="profile-name-row"><view class="profile-name">{{identity.session?.name}}</view><view v-if="canReceiveOrders" class="presence-status" :class="{receiving}" role="status" :aria-label="'当前接单状态：'+currentAvailability.profileLabel"><view class="presence-dot"/><text>{{currentAvailability.profileLabel}}</text></view><view v-else class="profile-level">{{CLUB_NAME}}用户</view></view><view class="muted">发布需求，也让你的实力被看见</view></view></view><view class="account-summary"><view class="account-summary-heading"><view class="account-summary-title"><uni-icons type="list" :size="19" color="#bdb5f5"/><text>我的订单</text></view><button class="summary-all" @tap="openOrders()">全部 <uni-icons type="arrow-right" :size="12" color="#aaaebe"/></button></view><view class="profile-stats"><button @tap="openOrders()"><text>{{orders.length}}</text>全部订单</button><button @tap="openOrders('active')"><text>{{activeCount}}</text>进行中</button><button @tap="openOrders('completed')"><text>{{orders.filter(o=>o.status==='completed').length}}</text>已完成</button></view></view>
      <view v-if="canReceiveOrders" class="account-summary profile-product-summary"><view class="account-summary-heading"><view class="account-summary-title"><uni-icons type="shop" :size="19" color="#bdb5f5"/><text>我的商品</text></view><button class="summary-all" @tap="openMyProducts">全部 <uni-icons type="arrow-right" :size="12" color="#aaaebe"/></button></view><button class="product-summary-body" @tap="openMyProducts"><view><text class="summary-count">{{ownPublished}}</text><text>件在售</text><text class="summary-divider">/</text><text>{{myProducts.length}} 件商品</text></view><text class="summary-caption">管理你的服务，让好队友找到你</text></button></view>
      <view class="profile-menu"><button v-if="canReceiveOrders||settlements.length>0" @tap="openWallet"><uni-icons type="wallet" :size="21" color="#bdb5f5"/><text>我的钱包</text><uni-icons type="arrow-right" color="#7b7f90" :size="15"/></button><button @tap="openRealName"><uni-icons type="person" :size="21" color="#bdb5f5"/><text>实名认证</text><view class="identity-menu-status">{{userIdentity?.status==='self_declared'?'已提交':'未填写'}}</view><uni-icons type="arrow-right" color="#7b7f90" :size="15"/></button><button @tap="openOnboarding"><uni-icons type="personadd" :size="21" color="#bdb5f5"/><text>我要入驻</text><uni-icons type="arrow-right" color="#7b7f90" :size="15"/></button><button v-if="!httpMode" @tap="sheet='accounts'"><uni-icons type="loop" :size="21" color="#bdb5f5"/><text>切换体验账号</text><uni-icons type="arrow-right" color="#7b7f90" :size="15"/></button><button v-if="httpMode" @tap="openAccountSecurity"><uni-icons type="locked" :size="21" color="#bdb5f5"/><text>账号安全</text><uni-icons type="arrow-right" color="#7b7f90" :size="15"/></button><button v-if="httpMode" @tap="logout"><uni-icons type="undo" :size="21" color="#bdb5f5"/><text>退出登录</text></button><button @tap="sheet='help'"><uni-icons type="headphones" :size="21" color="#bdb5f5"/><text>帮助与客服</text><uni-icons type="arrow-right" color="#7b7f90" :size="15"/></button></view><view class="profile-about"><view class="small-star">✳</view><text>{{CLUB_NAME_EN}}</text><view>好游戏，好队友。</view><text class="version">{{acceptanceMode?'内部验收 · 数据保存在服务器测试库':httpMode?'Norman Club · 游戏协作服务':'交互体验版 v2.1 · 数据仅保存在'+deviceDataLabel}}</text></view></view>
    </scroll-view>

    <view v-if="!personalView" class="bottom-nav"><button v-for="item in visibleTabs" :key="item.id" :class="{active:tab===item.id}" @tap="navigate(item.id)"><view class="nav-icon"><uni-icons :type="item.icon" :color="tab===item.id?'#d9fd88':'#777c8b'" :size="23"/><view v-if="item.id==='me'&&activeCount" class="nav-badge" :aria-label="activeCount+'个进行中订单'">{{activeCount>99?'99+':activeCount}}</view></view><text>{{item.label}}</text><view v-if="tab===item.id" class="nav-indicator"/></button></view>
    <view v-if="sheet" class="sheet-mask"><view class="sheet-backdrop" @tap="sheet=null" @touchmove.stop.prevent @wheel.stop.prevent/><view class="sheet sheet-tall" :class="{'recruitment-sheet':sheet==='checkout'}" @tap.stop @touchmove.stop @wheel.stop><view class="sheet-handle"/><button class="sheet-close" aria-label="关闭弹窗" @tap="sheet=null"><uni-icons type="closeempty" color="#a4a8b7" :size="24"/></button><view v-if="sheet==='checkout'" class="recruitment-header"><view class="sheet-title">确认招募</view></view><scroll-view :key="sheet" scroll-y class="sheet-scroll" :style="sheetStyle" :show-scrollbar="false">
      <view v-if="sheet==='identity-required'" class="sheet-content"><view class="sheet-title">{{userIdentity?.status==='self_declared'?'未成年人暂不能下单':'请先完成实名认证'}}</view><view class="sheet-sub">{{userIdentity?.status==='self_declared'?'根据填写的证件信息，您未满18周岁且尚未入驻，暂不能下单。如信息有误，请联系平台客服。':'未完成年龄登记且尚未入驻，暂不能下单。请到“我的”填写姓名和身份证号，平台将根据填写的信息判断年龄。'}}</view><button class="primary full" @tap="goToRealName">去我的实名认证</button><button class="secondary full" @tap="sheet=null">暂不</button></view><view v-else-if="sheet==='publish-menu'" class="sheet-content"><view class="sheet-title">发布服务商品</view><view class="sheet-sub">展示你的服务，用户下单后再开始履约。</view><button v-for="type in publishableTypes" :key="type" class="publish-choice" :disabled="busy" @tap="openPublish(type)"><view><view>发布{{type==='escort'?'护航':'组队'}}商品</view><text>{{type==='escort'?'按任务提供服务，每份计价':'按小时提供服务，设置服务单价'}}</text></view><uni-icons type="arrow-right" :size="18" color="#aaa5b9"/></button></view>
      <view v-else-if="sheet==='publish-product'" class="sheet-content"><view class="sheet-title">发布{{productType==='escort'?'护航':'组队'}}商品</view><view class="sheet-sub">仅显示你已通过该类型考核的游戏。</view><view class="form-label">游戏</view><view class="choice-row"><button v-for="g in games.filter(g=>publishGames(productType).includes(g.id))" :key="g.id" :class="{selected:productGame===g.id}" @tap="productGame=g.id">{{g.name}}</button></view><view class="form-label">商品标题</view><input v-model="productTitle" class="form-input" placeholder-class="field-placeholder" :cursor-spacing="24" placeholder="例如：三角洲默契组队" :maxlength="40"/><view class="form-label">商品说明</view><textarea v-model="productDescription" class="text-area" placeholder-class="field-placeholder" :cursor-spacing="24" :disable-default-padding="true" placeholder="介绍你的服务内容与约定，至少 5 字" :maxlength="200"/><template v-if="productType==='escort'"><view class="form-label">任务项 <text>必填</text></view><textarea v-model="productTask" class="text-area" placeholder-class="field-placeholder" :cursor-spacing="24" :disable-default-padding="true" :maxlength="200"/></template><view class="form-label">{{productType==='hourly'?'每小时单价（元）':'每份价格（元）'}}</view><input v-model="productPrice" class="form-input" placeholder-class="field-placeholder" :cursor-spacing="24" type="digit" placeholder="1～1000 元"/><button class="primary full" :disabled="busy" @tap="publishProduct">发布商品</button></view>
      <view v-else-if="sheet==='onboarding'" class="sheet-content onboarding-sheet"><view class="sheet-title">我要入驻</view><view class="qualification-explainer">按游戏分别申请组队、护航入驻，通过对应考核后即可发布商品、接单。</view>
      <view v-for="g in games" :key="g.id" class="qualification-game"><view class="qualification-game-title"><GameIcon :game-id="g.id" :size="22"/><text>{{g.name}}</text><text>独立考核</text></view><view class="qualification-directions">
        <view v-for="type in (['hourly','escort'] as ServiceType[])" :key="type" class="qualification-cell" :class="{certified:gameQualified(type,g.id)}"><view class="qualification-cell-heading">{{type==='escort'?'护航入驻':'组队入驻'}}</view><text class="qualification-state">{{qualificationState(type,g.id)}}</text>
          <view v-if="applicationFor(type,g.id)?.reviewNote" class="qualification-feedback">{{applicationFor(type,g.id)?.reviewNote}}<text v-if="applicationFor(type,g.id)?.score!==undefined"> · {{applicationFor(type,g.id)?.score}} 分</text></view>
          <view v-if="gameQualified(type,g.id)" class="qualification-access">可发布商品、接单</view><button v-else class="secondary small" :disabled="applicationPending(type,g.id)" @tap="openApply(type,g.id)">{{applicationPending(type,g.id)?'审核考核中':applicationFor(type,g.id)?'重新申请':'申请入驻'}}</button>
        </view>
      </view></view></view>
      <view v-else-if="sheet==='accounts'" class="sheet-content"><view class="eyebrow">ONE ACCOUNT, MORE POSSIBILITIES</view><view class="sheet-title">选择体验账号</view><view class="sheet-sub">各游戏的组队与护航分别申请入驻，通过对应考核后即可发布商品、接单。</view><button v-for="account in demoAccounts" :key="account.id" class="role-card" :class="{chosen:identity.session?.userId===account.id}" :disabled="busy" @tap="switchAccount(account.id)"><view class="role-icon">{{account.name.slice(0,1)}}</view><view><view>{{account.name}}</view><text>{{accountStatus(account.id)}}</text></view><uni-icons type="arrow-right" color="#a5a8b5" :size="20"/></button><view class="sheet-footnote">模拟账号，无需真实微信或手机号。</view></view>
      <view v-else-if="sheet==='player'&&selectedPlayer" class="sheet-content"><view class="player-detail-header"><view class="portrait large" :style="{'--portrait-color':selectedPlayer.color}"><view class="portrait-halo"/><view class="portrait-head"/><view class="portrait-body"/><text>{{selectedPlayer.avatar}}</text></view><view><view class="sheet-title">{{selectedPlayer.name}} <text class="verified">✓</text></view><view class="sheet-sub">{{selectedPlayer.subtitle}}</view><view class="tags"><text v-for="t in selectedPlayer.tags" :key="t">{{t}}</text></view></view></view><view class="detail-stats"><view><text>{{selectedPlayer.rating}}</text>服务评分</view><view><text>{{selectedPlayer.completed}}</text>已完成订单</view><view><text>{{serviceAvailability(selectedPlayer).label}}</text>接单状态</view></view><view class="form-label">关于我</view><view class="detail-bio">{{selectedPlayer.bio}}</view><view class="form-label">支持的游戏</view><view class="tags"><text v-for="id in selectedPlayer.gameIds" :key="id">{{gameName(id)}}</text></view><view class="service-promise">✓ 已通过{{serviceType==='escort'?'护航':'组队'}}考核 · 服务问题可申请售后</view><view class="input-help">{{serviceAvailability(selectedPlayer).hint}}</view><button class="primary full" :disabled="busy||selectedPlayer.userId===identity.session?.userId" @tap="book(selectedPlayer)">{{selectedPlayer.userId===identity.session?.userId?'不能邀请自己':serviceAvailability(selectedPlayer).action}}</button></view>
      <view v-else-if="sheet==='checkout'&&selectedProduct" class="sheet-content recruitment-checkout">
        <view class="checkout-summary"><view class="seller-avatar" :class="{'club-avatar':isClubProduct(selectedProduct)}" :style="{background:selectedPlayer?.color}">{{isClubProduct(selectedProduct)?'✳':selectedProduct.sellerName.slice(0,1)}}</view><view class="checkout-summary-body"><view class="checkout-summary-name">{{isClubProduct(selectedProduct)?CLUB_NAME:selectedProduct.sellerName}}<text v-if="isClubProduct(selectedProduct)" class="official-badge">官方</text></view><view class="checkout-summary-game"><GameIcon :game-id="selectedProduct.gameId" :size="13"/><text>{{gameName(selectedProduct.gameId)}} · {{checkoutType==='hourly'?'时长组队':'任务护航'}}</text></view></view><text class="checkout-summary-mode">{{isClubProduct(selectedProduct)?'公开招募':'定向招募'}}</text></view>
        <view v-if="selectedPlayer&&!receivingOrders(selectedPlayer)" class="checkout-availability">{{serviceAvailability(selectedPlayer).hint}}</view>
        <view class="checkout-options"><view class="checkout-option-row"><text class="checkout-label">游戏区服</text><text v-if="(currentGame?.regions.length||0)===1" class="checkout-region">{{region}}</text><view v-else class="choice-row checkout-regions"><button v-for="r in currentGame?.regions" :key="r" :class="{selected:region===r}" @tap="region=r">{{r}}</button></view></view>
        <view v-if="checkoutType==='escort'" class="checkout-task"><text class="checkout-label">约定任务</text><text>{{selectedProduct.task}}</text></view>
        <view class="checkout-option-row"><view><text class="checkout-label">{{checkoutType==='hourly'?'服务时长':'购买份数'}}</text><text class="checkout-unit-price">¥{{compactMoney(checkoutPrice)}} / {{checkoutType==='hourly'?'小时':'份'}}</text></view><view class="quantity-control"><uni-number-box v-if="checkoutType==='hourly'" v-model="quantity" :min="1" :max="8" background="#2b2d39" color="#f5f5f5" aria-label="服务时长"/><uni-number-box v-else v-model="copies" :min="1" :max="MAX_ORDER_COPIES" background="#2b2d39" color="#f5f5f5" aria-label="购买份数"/><text>{{checkoutType==='hourly'?'小时':'份'}}</text></view></view></view>
        <view class="checkout-fields"><view class="checkout-field-row"><text class="checkout-label">联系电话<text class="required-mark"> *</text></text><input v-model="contactPhone" class="checkout-input" type="text" inputmode="tel" aria-label="联系电话" placeholder-class="field-placeholder" :cursor-spacing="24" :maxlength="24" placeholder="请输入联系电话" @input="contactPhoneTouched=true"/></view><view class="checkout-field-row"><text class="checkout-label">微信号</text><input v-model="contactWechat" class="checkout-input" aria-label="微信号" placeholder-class="field-placeholder" :cursor-spacing="24" :maxlength="64" placeholder="请输入微信号"/></view><view class="checkout-note-row"><text class="checkout-label">备注</text><textarea v-model="note" class="checkout-note" aria-label="备注" placeholder-class="field-placeholder" :cursor-spacing="24" :disable-default-padding="true" placeholder="开始时间、游戏偏好等" :maxlength="200"/></view></view>
        <button class="agreement checkout-agreement" @tap="agreed=!agreed"><view class="checkmark" :class="{checked:agreed}">{{agreed?'✓':''}}</view><text>{{checkoutType==='escort'?'按约定任务验收，服务问题可申请售后':'按约定时长履约，服务问题可申请售后'}}</text></button>
      </view>
      <view v-else-if="sheet==='pay'&&selectedOrder" class="sheet-content payment-sheet"><view class="pay-icon"><uni-icons type="weixin" :size="42" color="#72e2a0"/></view><view class="sheet-title">{{httpMode?'订单支付':acceptanceMode?'内部验收 · 模拟收银台':'微信支付 · 模拟收银台'}}</view><view class="sheet-sub">{{selectedOrder.gameName}} · {{serviceLabels[selectedOrder.serviceType]}}</view><view class="pay-amount"><text>¥</text>{{money(selectedOrder.amount)}}</view><template v-if="httpMode"><view class="simulation-note">订单保留10分钟。微信支付以服务端开通状态为准，支付结果由渠道确认。</view><button class="secondary full" :disabled="busy" @tap="requestPayment('wechat')">微信支付</button></template><template v-else><view class="simulation-note">这是交互演示，不会发起真实扣款。</view><button class="primary full" :disabled="busy" @tap="action('pay')">确认模拟支付</button></template><button class="text-button" @tap="openOrders()">稍后支付</button></view>
      <view v-else-if="sheet==='order'&&selectedOrder" class="sheet-content"><view class="eyebrow">ORDER DETAILS</view><view class="sheet-title">{{refundFrozen(selectedOrder)?'售后处理中':statusLabels[selectedOrder.status]}}</view><view class="sheet-sub">{{selectedOrder.id}}</view><view class="detail-order-game"><view class="mini-game big" :style="{background:gameColor(selectedOrder.gameId)+'22',color:gameColor(selectedOrder.gameId)}">{{games.find(g=>g.id===selectedOrder?.gameId)?.short}}</view><view><view>{{selectedOrder.gameName}}</view><text>{{selectedOrder.region}} · {{serviceLabels[selectedOrder.serviceType]}}</text></view><view class="detail-order-price">¥{{money(selectedOrder.amount)}}</view></view><view class="detail-note">{{selectedOrder.serviceType==='escort'?'任务项：':''}}{{orderSummary(selectedOrder)}}</view><view class="detail-row"><text>下单用户</text><text>{{selectedOrder.bossName}}</text></view><view class="detail-row"><text>接单负责人</text><text>{{selectedOrder.playerName||selectedOrder.preferredPlayerName||'等待接单'}}</text></view><view v-if="selectedOrder.contactPhone" class="order-contact"><view class="form-label">预留联系方式</view><view class="detail-row"><text>联系电话</text><text>{{selectedOrder.contactPhone}}</text></view><view v-if="selectedOrder.contactWechat" class="detail-row"><text>微信号</text><text>{{selectedOrder.contactWechat}}</text></view></view><view v-if="selectedOrder.note" class="detail-note">{{selectedOrder.note}}</view><view v-if="selectedIncome" class="personal-income"><view class="personal-income-top"><text>{{selectedIncome.amountLabel}}</text><text class="income-state" :class="selectedIncome.status">{{selectedIncome.label}}</text></view><view class="personal-income-amount">¥{{money(selectedIncome.amount)}}</view><view class="input-help">{{selectedOrder.memberCommission?'服务金额按协作人数均分后，按各自锁定比例抽佣，此处仅显示你的净收入。':selectedOrder.serviceType==='escort'?'旧订单扣除服务费后按协作人数均分，此处仅显示你的份额。':'此金额为扣除服务费后的个人净报酬。'}}</view><template v-if="selectedIncome.settlement"><view class="detail-row"><text>自动结算记录</text><text>{{selectedIncome.settlement.id}}</text></view><view class="detail-row"><text>生成时间</text><text>{{time(selectedIncome.settlement.createdAt)}}</text></view><view v-if="selectedIncome.settlement.paidAt" class="detail-row"><text>{{httpMode?'到账时间':'模拟结算时间'}}</text><text>{{time(selectedIncome.settlement.paidAt)}}</text></view></template></view><template v-if="orderMembers(selectedOrder).length"><view class="form-label">{{selectedOrder.serviceType==='escort'?'协作成员':'服务队友'}}</view><view v-for="(member,i) in orderMembers(selectedOrder)" :key="member.playerId" class="team-member"><view><view>{{member.name}} <text class="muted">{{i===0?'负责人':'副手'}}</text><text v-if="selectedOrder.memberCommission" class="member-commission">抽佣 {{(member.commissionBps??selectedOrder.commissionBps)/100}}%</text></view><text class="muted">{{member.playerId===me?.id?selectedIncome?.label:refundFrozen(selectedOrder)?'售后暂停结算':selectedOrder.status==='completed'?(selectedOrder.settlementStatus==='credited'?'已记账 · 待渠道转账':selectedOrder.settlementStatus==='paid'||selectedOrder.timeline.some(e=>e.label==='系统自动结算完成（模拟）')?'已自动结算':'自动结算处理中'):'确认完成后自动结算'}}</text></view><text class="lime">¥{{money(participantEarnings(selectedOrder,member.playerId))}}</text><button v-if="i>0&&isLead(selectedOrder)&&selectedOrder.status==='accepted'&&!refundFrozen(selectedOrder)" class="text-button" :disabled="busy" @tap="action('remove-assistant',selectedOrder,{playerId:member.playerId})">移除</button></view><button v-if="isLead(selectedOrder)&&selectedOrder.serviceType==='escort'&&selectedOrder.status==='accepted'&&!refundFrozen(selectedOrder)" class="secondary full" @tap="sheet='assistants'">＋ 添加副手</button><view v-if="selectedOrder.serviceType==='escort'" class="input-help">预计总收入 ¥{{money(selectedOrder.earnings)}}。{{selectedOrder.memberCommission?'服务金额先均分，再按各成员锁定比例抽佣。':'旧订单沿用净报酬均分。'}}开始服务后名单锁定。</view></template>
        <view v-if="selectedOrder.serviceType==='hourly'&&selectedOrder.status==='serving'" class="notice"><text>{{countdown(selectedOrder)}}</text><button v-if="!httpMode&&isLead(selectedOrder)&&remaining(selectedOrder)>0" class="text-button" :disabled="busy||refundFrozen(selectedOrder)" @tap="action('demo-elapse')">模拟走满时长</button></view><view v-if="selectedOrder.refundStatus!=='none'" class="refund-info"><view>售后：{{refundFrozen(selectedOrder)?'待审核':selectedOrder.refundStatus==='refunded'?(httpMode?'已原路退款':'已模拟退款'):'未通过'}}</view><text>申请 ¥{{money(selectedOrder.refundAmount||0)}} · {{selectedOrder.refundReason}}</text><text v-if="selectedOrder.refundReviewNote">{{selectedOrder.refundReviewNote}}</text></view><view class="form-label">订单动态</view><view class="timeline"><view v-for="(e,i) in selectedOrder.timeline" :key="i" class="timeline-item"><view class="timeline-dot"/><view><view>{{e.label}}</view><text>{{time(e.at)}} · {{e.actor}}</text><view v-if="e.note" class="timeline-note">{{e.note}}</view></view></view></view><view class="detail-actions"><button v-if="isInvited(selectedOrder)" class="primary" :disabled="busy" @tap="action('accept')">接受邀请</button><button class="secondary" @tap="sheet='help'">联系客服</button><template v-if="isMine(selectedOrder)"><button v-if="selectedOrder.status==='unpaid'" class="secondary" :disabled="busy" @tap="action('cancel')">取消订单</button><button v-if="selectedOrder.status==='unpaid'" class="primary" @tap="payOrder(selectedOrder)">去付款</button><button v-if="selectedOrder.status==='confirming'&&!refundFrozen(selectedOrder)" class="primary" :disabled="busy" @tap="action('confirm')">确认完成</button><button v-if="selectedOrder.paidAt&&selectedOrder.status!=='cancelled'&&['none','rejected'].includes(selectedOrder.refundStatus)&&selectedOrder.settlementStatus!=='paid'" class="secondary" @tap="refund(selectedOrder)">申请退款</button></template><template v-if="isLead(selectedOrder)&&!refundFrozen(selectedOrder)"><button v-if="selectedOrder.status==='accepted'" class="primary" :disabled="busy" @tap="action('start')">开始服务</button><button v-if="selectedOrder.status==='serving'" class="primary" :disabled="busy||(selectedOrder.serviceType==='hourly'&&remaining(selectedOrder)>0)" @tap="finish(selectedOrder)">{{selectedOrder.serviceType==='escort'?'提交任务验收':'提交完成'}}</button></template></view></view>
      <view v-else-if="sheet==='assistants'&&selectedOrder" class="sheet-content"><view class="sheet-title">添加副手</view><view class="sheet-sub">仅展示已通过该游戏护航考核、平台已开启接单且空闲的成员。开始服务后锁定名单。</view><view class="assistant-search"><uni-icons type="search" :size="18" color="#999faf"/><input v-model="assistantSearch" aria-label="搜索副手" placeholder="搜索成员昵称、标签" placeholder-class="field-placeholder" :maxlength="60"/><button v-if="assistantSearch" class="text-button" aria-label="清空搜索" @tap="assistantSearch=''"><uni-icons type="clear" :size="16" color="#999faf"/></button></view><view class="assistant-count">{{assistantCandidates.length}} 位可添加成员</view><view class="assistant-list"><view v-for="p in assistantCandidates" :key="p.id" class="assistant-row"><view class="assistant-avatar" :style="{background:p.color+'25',color:p.color}">{{p.name.slice(0,1)}}</view><view class="assistant-info"><text>{{p.name}}</text><text>{{p.tags.join(' · ')||p.subtitle}}</text></view><button class="secondary small" :disabled="busy" :aria-label="'添加副手'+p.name" @tap="action('add-assistant',selectedOrder,{playerId:p.id})">添加</button></view></view><view v-if="!assistantCandidates.length" class="empty">{{assistantSearch?'没有找到匹配成员':'暂无可添加成员'}}</view><view class="input-help">{{selectedOrder.memberCommission?'服务金额先均分，再按每位成员加入时的比例抽佣。':'此旧订单沿用原有净报酬均分规则。'}}</view></view>
      <view v-else-if="sheet==='apply'" class="sheet-content"><view class="eyebrow">LET YOUR SKILLS SHINE</view><view class="sheet-title">申请入驻 · {{gameName(applyGame)}}{{applyType==='escort'?'护航':'组队'}}</view><view class="sheet-sub">提交申请 → 资料审核 → 专项考核 → 开通商品发布与接单</view><view class="apply-scope"><GameIcon :game-id="applyGame" :size="24"/><view>{{gameName(applyGame)}}<text>{{applyType==='escort'?'护航 · 按任务验收':'组队 · 按时长履约'}}</text></view></view><view class="form-label">游戏经验与服务说明</view><textarea v-model="introduction" class="text-area" placeholder-class="field-placeholder" :cursor-spacing="24" :disable-default-padding="true" placeholder="介绍你的游戏经验、擅长内容及可参加考核的时间，至少 10 字。" :maxlength="500"/><view class="simulation-note">本次仅申请入驻{{gameName(applyGame)}}的{{applyType==='escort'?'护航':'组队'}}服务。通过后仅可发布、接取该游戏该类型的商品与订单，其他游戏或服务类型需要另行考核。</view><button class="primary full" :disabled="busy" @tap="submitApplication">提交申请</button></view>
      <view v-else-if="sheet==='refund'&&selectedOrder" class="sheet-content"><view class="sheet-title">申请售后退款</view><view class="sheet-sub">俱乐部会核实服务记录并处理你的申请。</view><view class="form-label">退款金额（元）</view><input v-model="refundAmount" class="form-input" placeholder-class="field-placeholder" :cursor-spacing="24" type="digit"/><view class="input-help">最高 ¥{{money(selectedOrder.amount)}}，支持部分退款。</view><view class="form-label">退款原因 </view><textarea v-model="refundReason" class="text-area" placeholder-class="field-placeholder" :cursor-spacing="24" :disable-default-padding="true" placeholder="可补充遇到的问题，也可以直接提交售后" :maxlength="200"/><button class="primary full" :disabled="busy" @tap="action('refund',selectedOrder,{amount:Math.round(Number(refundAmount)*100),reason:refundReason})">提交退款申请</button></view>
      <view v-else-if="sheet==='finish'&&selectedOrder" class="sheet-content"><view class="eyebrow">GG, WELL PLAYED</view><view class="sheet-title">{{selectedOrder.serviceType==='escort'?'提交任务验收':'提交服务记录'}}</view><view class="sheet-sub">提交后由下单用户确认，随后系统自动结算净报酬。</view><view v-if="selectedOrder.serviceType==='escort'" class="detail-note">任务项：{{orderUnits(selectedOrder)}}</view><view class="form-label">完成记录</view><textarea v-model="finishNote" class="text-area" placeholder-class="field-placeholder" :cursor-spacing="24" :disable-default-padding="true" :placeholder="selectedOrder.serviceType==='escort'?'说明任务达成情况及验收依据':'说明已完成的服务时长与情况'" :maxlength="200"/><button v-if="selectedOrder.serviceType==='escort'" class="agreement" @tap="taskConfirmed=!taskConfirmed"><view class="checkmark" :class="{checked:taskConfirmed}">{{taskConfirmed?'✓':''}}</view><text>确认已完成全部 {{orderCopies(selectedOrder)}} 份约定任务</text></button><button class="primary full" :disabled="busy" @tap="action('finish',selectedOrder,{note:finishNote,taskCompleted:taskConfirmed})">提交完成</button></view>
      <view v-else-if="sheet==='help'" class="sheet-content"><view class="eyebrow">ALWAYS ON YOUR SIDE</view><view class="sheet-title">帮助与客服</view><view class="sheet-sub">服务遇到问题，可在订单详情申请售后。</view><view class="help-card"><uni-icons type="headphones" :size="35" color="#d9fd88"/><view><view>俱乐部服务支持</view><text>{{httpMode?'可通过俱乐部现有客服渠道联系工作人员。':'当前为交互演示，真实客服尚未接入。'}}</text></view></view><view class="detail-bio">组队订单按约定时长完成；护航订单按指定任务验收。新用户默认尚未入驻。每个游戏的组队与护航分别申请入驻，通过对应考核后，才可发布对应商品或接取对应订单。</view><view class="sheet-footnote">{{acceptanceMode?'验收数据保存在服务器独立测试库，两端共享；收付款均为模拟。':httpMode?'订单有争议时请先提交售后，相关处理进展可在订单中查看。':'模拟数据保存在'+deviceDataLabel+'，不同设备之间独立。'}}</view></view>
    </scroll-view><view v-if="sheet==='checkout'&&selectedProduct" class="recruitment-footer"><view class="total-price"><text>合计</text>¥{{money(checkoutTotal)}}</view><button class="primary" :disabled="busy" @tap="createOrder">{{busy?'提交中…':'提交招募'}}</button></view></view></view><view v-if="toast" class="toast"><uni-icons type="info" color="#d9fd88" :size="18"/>{{toast}}</view>
    </template>
  </view></view>
</template>

<style lang="scss">
@import "../../styles/mobile.scss";
</style>
