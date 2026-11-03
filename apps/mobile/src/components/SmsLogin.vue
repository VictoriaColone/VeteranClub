<script setup lang="ts">
import {nextTick,onUnmounted,ref,watch} from 'vue';
import {CLUB_NAME,CLUB_NAME_EN} from '@stellar/core';
import {api,useIdentity} from '../store';
const emit=defineEmits<{authenticated:[]}>();
const identity=useIdentity();
const mode=ref<'login'|'register'|'reset'>('login'),method=ref<'sms'|'password'>('sms'),password=ref(''),repeatPassword=ref('');
const phone=ref(''),code=ref(''),busy=ref(false),sending=ref(false),remaining=ref(0),error=ref(''),delivery=ref('');
let timer:ReturnType<typeof setInterval>|undefined;
async function send(){
  if(sending.value||remaining.value)return;
  if(!/^1[3-9]\d{9}$/.test(phone.value)){error.value='请输入有效的手机号';return;}
  sending.value=true;error.value='';
  try{const result=await api.sendSms(phone.value,mode.value);remaining.value=result.retryAfter;delivery.value=result.delivery;if(timer)clearInterval(timer);timer=setInterval(()=>{remaining.value=Math.max(0,remaining.value-1);if(!remaining.value&&timer)clearInterval(timer);},1000);}
  catch(e){error.value=e instanceof Error?e.message:'验证码发送失败';}finally{sending.value=false;}
}
async function login(){
  if(busy.value)return;busy.value=true;error.value='';
  try{
    if(mode.value!=='login'&&password.value!==repeatPassword.value)throw new Error('两次填写的密码不一致');
    if(mode.value==='reset'){await api.resetUserPassword(phone.value,code.value,password.value);mode.value='login';method.value='password';await nextTick();error.value='密码已重置，请使用新密码登录';return;}
    await (mode.value==='register'?identity.smsRegister(phone.value,code.value,password.value):method.value==='password'?identity.passwordLogin(phone.value,password.value):identity.smsLogin(phone.value,code.value));code.value='';password.value='';repeatPassword.value='';emit('authenticated');
  }
  catch(e){error.value=e instanceof Error?e.message:'登录失败';}finally{busy.value=false;}
}
watch([mode,method],()=>{code.value='';password.value='';repeatPassword.value='';error.value='';});
onUnmounted(()=>{if(timer)clearInterval(timer);password.value='';repeatPassword.value='';code.value='';});
</script>

<template>
  <view class="sms-login">
    <view class="login-brand"><text>✳</text><view>{{CLUB_NAME}}<text>{{CLUB_NAME_EN}}</text></view></view>
    <view class="login-welcome">好游戏，一起出发。</view>
    <view class="login-description">手机号登录 · 安全又方便</view>
    <view class="login-form">
      <view class="login-modes"><button v-for="item in (['login','register'] as const)" :key="item" :class="{active:mode===item}" :disabled="busy" @tap="mode=item">{{item==='login'?'登录':'注册'}}</button></view>
      <view v-if="mode==='reset'" class="form-label">找回 / 设置密码</view>
      <view class="form-label">手机号</view><input v-model="phone" class="form-input" type="number" :maxlength="11" placeholder="请输入手机号" placeholder-class="field-placeholder"/>
      <template v-if="mode!=='login'||method==='sms'"><view class="form-label">验证码</view><view class="sms-code-row"><input v-model="code" class="form-input" type="number" :maxlength="6" placeholder="6 位短信验证码" placeholder-class="field-placeholder" @confirm="login"/><button class="secondary" :disabled="sending||remaining>0" @tap="send">{{remaining?remaining+'s 后重发':sending?'发送中…':'获取验证码'}}</button></view></template>
      <template v-if="mode!=='login'||method==='password'"><view class="form-label">{{mode==='reset'?'新密码':'密码'}}</view><input v-model="password" class="form-input" password :maxlength="72" :placeholder="mode==='login'?'请输入密码':'至少8位，包含字母和数字'" placeholder-class="field-placeholder" @confirm="login"/></template>
      <template v-if="mode!=='login'"><view class="form-label">确认密码</view><input v-model="repeatPassword" class="form-input" password :maxlength="72" placeholder="再次填写密码" placeholder-class="field-placeholder" @confirm="login"/></template>
      <view v-if="mode==='login'" class="login-alternatives"><button :disabled="busy" @tap="method=method==='sms'?'password':'sms'">{{method==='sms'?'密码登录':'短信登录'}}</button><button :disabled="busy" @tap="mode='reset'">忘记 / 设置密码</button></view>
      <view v-if="delivery==='development-outbox'" class="login-notice">本地联调模式：未发送真实短信，验证码请查看本地短信收件箱。</view>
      <view v-if="error" class="login-error" role="alert">{{error}}</view>
      <button class="primary full" :disabled="busy||phone.length!==11||((mode!=='login'||method==='sms')&&code.length!==6)||((mode!=='login'||method==='password')&&!password)" @tap="login">{{busy?'正在验证…':mode==='reset'?'重置密码':mode==='register'?'注册':'登录'}}</button>
      <view class="login-footnote">{{mode==='register'?'每个手机号仅可注册一个账号。已注册请切换登录。':mode==='reset'?'短信验证后设置新密码，原有登录将全部失效。':'可使用短信或密码登录；未设置密码请先通过短信设置。'}}</view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.form-label{display:flex;align-items:center;font-size:13px;font-weight:550;color:#d7dce7;margin:22px 0 12px}.form-input{height:46px;padding:0 13px;border:1px solid #374154;border-radius:10px;color:#eef0f6;background:#101218;font-size:16px}.primary{display:flex;align-items:center;justify-content:center;background:#d9fd88;color:#1b2412;min-height:46px;padding:12px 17px;border-radius:10px;font-size:14px;font-weight:650}.secondary{display:flex;align-items:center;justify-content:center;border:1px solid #3c4557;background:#242a35;color:#c8ceda;border-radius:10px;min-height:46px}.full{width:100%}
.login-modes{display:flex;gap:8px;margin-bottom:24px;padding:5px;border:1px solid #333b4a;border-radius:12px}.login-modes button{flex:1;background:none;padding:11px;color:#9299aa;border-radius:9px;font-size:14px}.login-modes button.active{background:#d9fd8810;color:#d9fd88}
.login-alternatives{display:flex;justify-content:space-between;gap:12px;margin-top:18px;color:#bdb5f5;font-size:12px}.login-alternatives button{background:none;padding:0}
.sms-login{padding:52px 26px 40px;min-height:100vh;display:flex;flex-direction:column;justify-content:center}.login-brand{display:flex;align-items:center;gap:12px;margin-bottom:56px;font-size:23px;font-weight:700}.login-brand>text{color:#d9fd88;font-size:42px}.login-brand>view>text{display:block;letter-spacing:3px;font-size:10px;color:#8b91a3;margin-top:6px}.login-welcome{font-size:28px;font-weight:700;letter-spacing:-.5px}.login-description{font-size:14px;color:#8b91a3;margin-top:12px}.login-form{margin-top:24px}.sms-code-row{display:flex;align-items:center;gap:10px}.sms-code-row input{min-width:0;flex:1}.sms-code-row button{padding:14px 12px;white-space:nowrap;font-size:13px;flex-shrink:0}.login-form>.primary{margin-top:28px}.login-notice{color:#bdb5f5;line-height:1.8;font-size:12px;margin-top:16px}.login-error{color:#f29c9c;font-size:13px;line-height:1.8;margin-top:16px}.login-footnote{text-align:center;line-height:1.8;color:#7d8394;font-size:12px;margin-top:20px}
</style>
