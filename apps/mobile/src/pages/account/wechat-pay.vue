<script setup lang="ts">
import {ref,onUnmounted,watch} from 'vue';
import {onLoad,onShow,onHide} from '@dcloudio/uni-app';
import {api,httpMode,useIdentity} from '../../store';
import {money,type Order,type PaymentAttempt} from '@stellar/core';
const identity=useIdentity(),order=ref<Order|null>(null),message=ref('正在检查支付状态…'),enabled=ref(false),busy=ref(false),wechat=ref(false);
let orderId='',active=false,revision=0,timer:ReturnType<typeof setTimeout>|undefined;
function back(){uni.navigateBack({fail:()=>uni.reLaunch({url:'/pages/index/index'})});}
function stop(){active=false;revision++;clearTimeout(timer);}
async function load(){
 const version=++revision;try{if(httpMode)await identity.restore();if(!identity.session)throw new Error('请先返回首页登录');
 const [value,channels]=await Promise.all([api.order(orderId),api.paymentChannels()]);if(version!==revision||!active)return;order.value=value;enabled.value=httpMode&&channels.some(c=>c.channel==='wechat'&&c.enabled);
 message.value=value.status!=='unpaid'?(value.status==='cancelled'?'订单已关闭':'服务端已确认收款'):!enabled.value?'微信支付尚未开通，当前不会收款。订单超时后自动关闭。':!wechat.value?'请在微信中打开用户端后付款。':'收款方：诺曼部落所属经营主体，付款金额以当前订单为准。';
 }catch(e){if(version===revision)message.value=e instanceof Error?e.message:'支付状态查询失败';}
}
async function pay(){
 if(busy.value||!order.value||!wechat.value||!enabled.value)return;busy.value=true;const owner=identity.session?.userId;
 try{const authorization=await api.authorizeWechat(orderId);
 // #ifdef H5
 if(!authorization.authorized){const url=new URL(authorization.url||'');if(url.origin!=='https://open.weixin.qq.com'||url.pathname!=='/connect/oauth2/authorize')throw new Error('微信授权地址无效');window.location.assign(url.href);return;}
 // #endif
 let attempt=(await api.paymentAttempts(orderId)).find(p=>['creating','pending','unknown'].includes(p.status));
 if(!attempt)attempt=await api.createPayment(orderId,'wechat');
 for(let i=0;i<20&&active&&owner===identity.session?.userId&&attempt.status==='creating';i++){message.value='正在准备微信支付…';await new Promise(resolve=>setTimeout(resolve,1000));attempt=await api.payment(attempt.id);}
 if(!active||owner!==identity.session?.userId)return;
 if(attempt.status==='paid'){await load();return;}if(attempt.status!=='pending'||!attempt.clientParams)throw new Error('支付结果仍在确认，请稍后查询，勿重复付款');
 await invoke(attempt);message.value='正在等待服务端确认支付结果…';await load();
 }catch(e){if(owner===identity.session?.userId)message.value=e instanceof Error?e.message:'微信支付暂不可用';}finally{busy.value=false;}
}
async function invoke(attempt:PaymentAttempt){
 // #ifdef H5
 const bridge=(window as any).WeixinJSBridge;if(!bridge?.invoke)throw new Error('微信支付环境尚未就绪，请稍后重试');
 const p=attempt.clientParams!;if(p.signType!=='RSA'||typeof p.package!=='string'||!p.package.startsWith('prepay_id='))throw new Error('支付参数无效');
 await new Promise<void>((resolve,reject)=>{const timeout=setTimeout(()=>reject(new Error('支付结果未确认，请查询订单状态')),120000);bridge.invoke('getBrandWCPayRequest',p,(result:{err_msg?:string})=>{clearTimeout(timeout);result.err_msg==='get_brand_wcpay_request:ok'?resolve():reject(new Error(result.err_msg==='get_brand_wcpay_request:cancel'?'已取消支付，可稍后继续':'支付结果未确认，请查询订单状态'));});});
 // #endif
}
function schedule(){clearTimeout(timer);if(active)timer=setTimeout(async()=>{if(!busy.value)await load();schedule();},5000);}
onLoad(options=>{orderId=options?.orderId||'';});onShow(()=>{active=true;
 // #ifdef H5
 wechat.value=/MicroMessenger/i.test(navigator.userAgent);
 // #endif
 void load();schedule();});onHide(stop);onUnmounted(stop);watch(()=>identity.session?.userId,()=>{order.value=null;});
</script>
<template><view class="payment-page"><view class="payment-heading"><button @tap="back">‹ 返回</button><text>订单支付</text></view><view class="payment-body"><text class="payment-label">应付金额</text><view class="payment-total">¥{{money(order?.amount||0)}}</view><text class="payment-label">{{order?.gameName}}</text><view class="payment-message" role="status">{{message}}</view><button v-if="order?.status==='unpaid'" class="primary full" :disabled="busy||!enabled||!wechat" @tap="pay">{{busy?'请稍候…':'微信支付'}}</button><button class="secondary full" :disabled="busy" @tap="load">查询支付结果</button><button class="text-button" @tap="back">返回订单</button></view></view></template>
<style scoped>.payment-page{height:var(--club-viewport-height,100dvh);display:flex;flex-direction:column;background:#12151c;color:#e9ebf1}.payment-heading{height:68px;display:flex;align-items:center;border-bottom:1px solid #303746;gap:24px;padding:0 20px}.payment-heading button{margin:0;background:transparent;color:#bdb5f5;font-size:14px;padding:0;border:0}.payment-heading text{font-weight:700}.payment-body{padding:36px 24px;overflow:auto;text-align:center;flex:1}.payment-label{color:#9fa6b7;font-size:13px}.payment-total{font-size:40px;font-weight:750;color:#d7ff82;margin:14px 0}.payment-message{padding:24px 0;line-height:1.8;color:#aaaebe;font-size:14px}.payment-body button{margin-top:14px}</style>
