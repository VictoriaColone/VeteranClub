<script setup lang="ts">
import {onUnmounted,ref} from 'vue';
import {CLUB_NAME,CLUB_NAME_EN} from '@stellar/core';
import {api,useIdentity} from '../store';
const emit=defineEmits<{authenticated:[]}>();
const identity=useIdentity();
const phone=ref(''),code=ref(''),busy=ref(false),sending=ref(false),remaining=ref(0),error=ref(''),delivery=ref('');
let timer:ReturnType<typeof setInterval>|undefined;
async function send(){
  if(sending.value||remaining.value)return;
  if(!/^1[3-9]\d{9}$/.test(phone.value)){error.value='请输入有效的手机号';return;}
  sending.value=true;error.value='';
  try{const result=await api.sendSms(phone.value);remaining.value=result.retryAfter;delivery.value=result.delivery;if(timer)clearInterval(timer);timer=setInterval(()=>{remaining.value=Math.max(0,remaining.value-1);if(!remaining.value&&timer)clearInterval(timer);},1000);}
  catch(e){error.value=e instanceof Error?e.message:'验证码发送失败';}finally{sending.value=false;}
}
async function login(){
  if(busy.value)return;busy.value=true;error.value='';
  try{await identity.smsLogin(phone.value,code.value);code.value='';emit('authenticated');}
  catch(e){error.value=e instanceof Error?e.message:'登录失败';}finally{busy.value=false;}
}
onUnmounted(()=>{if(timer)clearInterval(timer);});
</script>

<template>
  <view class="sms-login">
    <view class="login-brand"><text>✳</text><view>{{CLUB_NAME}}<text>{{CLUB_NAME_EN}}</text></view></view>
    <view class="login-welcome">好游戏，一起出发。</view>
    <view class="login-description">手机号登录 · 安全又方便</view>
    <view class="login-form">
      <view class="form-label">手机号</view><input v-model="phone" class="form-input" type="number" :maxlength="11" placeholder="请输入手机号" placeholder-class="field-placeholder"/>
      <view class="form-label">验证码</view><view class="sms-code-row"><input v-model="code" class="form-input" type="number" :maxlength="6" placeholder="6 位短信验证码" placeholder-class="field-placeholder" @confirm="login"/><button class="secondary" :disabled="sending||remaining>0" @tap="send">{{remaining?remaining+'s 后重发':sending?'发送中…':'获取验证码'}}</button></view>
      <view v-if="delivery==='development-outbox'" class="login-notice">本地联调模式：未发送真实短信，验证码请查看本地短信收件箱。</view>
      <view v-if="error" class="login-error" role="alert">{{error}}</view>
      <button class="primary full" :disabled="busy||phone.length!==11||code.length!==6" @tap="login">{{busy?'正在登录…':'登录 / 注册'}}</button>
      <view class="login-footnote">未注册的手机号验证后将自动创建账号。</view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.sms-login{padding:52px 26px 40px;min-height:100vh;display:flex;flex-direction:column;justify-content:center}.login-brand{display:flex;align-items:center;gap:12px;margin-bottom:56px;font-size:23px;font-weight:700}.login-brand>text{color:#d9fd88;font-size:42px}.login-brand>view>text{display:block;letter-spacing:3px;font-size:10px;color:#8b91a3;margin-top:6px}.login-welcome{font-size:28px;font-weight:700;letter-spacing:-.5px}.login-description{font-size:14px;color:#8b91a3;margin-top:12px}.login-form{margin-top:24px}.sms-code-row{display:flex;align-items:center;gap:10px}.sms-code-row input{min-width:0;flex:1}.sms-code-row button{padding:14px 12px;white-space:nowrap;font-size:13px;flex-shrink:0}.login-form>.primary{margin-top:28px}.login-notice{color:#bdb5f5;line-height:1.8;font-size:12px;margin-top:16px}.login-error{color:#f29c9c;font-size:13px;line-height:1.8;margin-top:16px}.login-footnote{text-align:center;line-height:1.8;color:#7d8394;font-size:12px;margin-top:20px}
</style>
