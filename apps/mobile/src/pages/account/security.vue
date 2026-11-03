<script setup lang="ts">
import {computed,onUnmounted,ref,watch} from 'vue';
import {onShow,onHide} from '@dcloudio/uni-app';
import UniIcons from '@club-ui/uni-icons/uni-icons.vue';
import {api,useIdentity,httpMode} from '../../store';
import {getPlatformLayout} from '../../services/platform';
const identity=useIdentity(),layout=ref(getPlatformLayout()),phone=ref(''),mode=ref<'reset'|'close'>('reset');
const code=ref(''),password=ref(''),repeatPassword=ref(''),busy=ref(false),sending=ref(false),remaining=ref(0),error=ref(''),confirmed=ref(false);
const maskedPhone=computed(()=>phone.value?phone.value.slice(0,3)+'****'+phone.value.slice(-4):'未绑定手机号');
let timer:ReturnType<typeof setInterval>|undefined;
function back(){uni.navigateBack({fail:()=>uni.reLaunch({url:'/pages/index/index'})});}
function clear(){code.value='';password.value='';repeatPassword.value='';confirmed.value=false;}
async function send(){
  if(sending.value||remaining.value||!phone.value)return;sending.value=true;error.value='';
  try{const result=await api.sendSms(phone.value,mode.value);remaining.value=result.retryAfter;if(timer)clearInterval(timer);timer=setInterval(()=>{remaining.value=Math.max(0,remaining.value-1);if(!remaining.value&&timer)clearInterval(timer);},1000);}
  catch(e){error.value=e instanceof Error?e.message:'验证码发送失败';}finally{sending.value=false;}
}
async function submit(){
  if(busy.value)return;
  if(mode.value==='reset'&&password.value!==repeatPassword.value){error.value='两次填写的密码不一致';return;}
  if(mode.value==='close'&&!confirmed.value){error.value='请先确认注销说明';return;}
  if(mode.value==='close'){
    const result=await new Promise<boolean>(resolve=>uni.showModal({title:'确认注销账号',content:'注销后会退出所有设备，清除登录和身份登记信息。历史订单及必要交易记录保留，原有入驻资格不能恢复。',confirmText:'确认注销',cancelText:'暂不注销',success:r=>resolve(r.confirm),fail:()=>resolve(false)}));
    if(!result)return;
  }
  busy.value=true;error.value='';
  try{if(mode.value==='reset')await api.resetUserPassword(phone.value,code.value,password.value);else await api.closeAccount(code.value);identity.clear();clear();uni.reLaunch({url:'/pages/index/index'});uni.showToast({title:mode.value==='reset'?'密码已重置，请重新登录':'账号已注销',icon:'none'});}
  catch(e){error.value=e instanceof Error?e.message:'操作失败，请重试';}finally{busy.value=false;}
}
watch(mode,()=>{clear();error.value='';});
onShow(async()=>{layout.value=getPlatformLayout();try{if(!httpMode)throw new Error('体验账号不支持修改密码或注销');const userId=identity.session?.userId;const contact=await api.registrationContact();if(userId===identity.session?.userId&&contact.userId===userId)phone.value=contact.phone;}catch(e){error.value=e instanceof Error?e.message:'账号信息加载失败';}});
onHide(()=>{clear();phone.value='';});onUnmounted(()=>{clear();phone.value='';if(timer)clearInterval(timer);});
</script>

<template>
  <view class="security-page"><view class="topbar account-topbar" :style="layout?{height:layout.navHeight+'px',paddingTop:layout.topPadding+'px',paddingRight:layout.rightPadding+'px'}:{}"><button class="account-back" aria-label="返回我的" @tap="back"><UniIcons type="arrow-left" :size="23" color="#e9ebf1"/></button><view class="account-page-title">账号安全</view><view class="security-spacer"/></view>
    <scroll-view scroll-y class="security-scroll" :show-scrollbar="false"><view class="security-body">
      <view class="security-phone">绑定手机<text>{{maskedPhone}}</text></view>
      <view class="security-modes"><button :class="{active:mode==='reset'}" :disabled="busy" @tap="mode='reset'">设置 / 修改密码</button><button :class="{active:mode==='close'}" :disabled="busy" @tap="mode='close'">注销账号</button></view>
      <view class="security-card"><view class="security-title">{{mode==='reset'?'验证手机，设置新密码':'注销前请确认'}}</view>
        <view class="security-caption">{{mode==='reset'?'修改后所有设备上的旧登录会失效，需重新登录。':'请先处理未完成订单、售后及未结清收益。注销后释放手机号和证件绑定，清除密码及身份资料，历史交易记录保留。重新注册将是新账号，不继承原入驻资格。'}}</view>
        <view class="form-label">短信验证码</view><view class="security-code"><input v-model="code" class="form-input" type="number" :maxlength="6" aria-label="短信验证码" placeholder="6位验证码" placeholder-class="field-placeholder"/><button class="secondary small" :disabled="busy||sending||remaining>0||!phone" @tap="send">{{remaining?remaining+'s 后重发':sending?'发送中…':'获取验证码'}}</button></view>
        <template v-if="mode==='reset'"><view class="form-label">新密码</view><input v-model="password" class="form-input" password :maxlength="72" aria-label="新密码" placeholder="至少8位，包含字母和数字" placeholder-class="field-placeholder"/><view class="form-label">确认密码</view><input v-model="repeatPassword" class="form-input" password :maxlength="72" aria-label="确认密码" placeholder="再次填写新密码" placeholder-class="field-placeholder"/></template>
        <button v-else class="closure-consent" role="checkbox" :aria-checked="confirmed" :disabled="busy" @tap="confirmed=!confirmed"><text class="closure-box" :class="{checked:confirmed}">{{confirmed?'✓':''}}</text><text>我已阅读并确认注销说明</text></button>
        <view v-if="error" class="security-error" role="alert">{{error}}</view>
        <button class="primary full" :class="{danger:mode==='close'}" :disabled="busy||!phone||code.length!==6||(mode==='reset'&&(!password||!repeatPassword))||(mode==='close'&&!confirmed)" @tap="submit">{{busy?'正在处理…':mode==='reset'?'重置密码':'注销账号'}}</button>
      </view>
    </view></scroll-view>
  </view>
</template>

<style lang="scss">@import "../../styles/mobile.scss";</style>
<style scoped lang="scss">
.security-page{height:100dvh;max-width:520px;margin:auto;display:flex;flex-direction:column;background:#101218;color:#e9ebf1}.security-spacer{width:40px}.security-scroll{height:0;min-height:0;flex:1;overscroll-behavior:contain}.security-body{padding:24px 20px 40px}.security-phone{display:flex;justify-content:space-between;font-size:13px;padding:16px 2px;color:#989eae}.security-phone text{color:#e9ebf1}.security-modes{display:flex;gap:6px;padding:5px;border:1px solid #333b4a;border-radius:12px;margin:10px 0 20px}.security-modes button{flex:1;padding:12px 4px;border-radius:8px;color:#9ca3b3;font-size:13px}.security-modes button.active{background:#d9fd8810;color:#d9fd88}.security-card{border:1px solid #333b4a;background:#1b2029;border-radius:18px;padding:20px}.security-title{font-size:17px;font-weight:600}.security-caption{font-size:12px;line-height:1.9;color:#9ca3b3;margin-top:12px}.security-code{display:flex;gap:10px;align-items:center}.security-code input{flex:1;min-width:0}.security-code button{white-space:nowrap;flex-shrink:0}.security-card>.primary{margin-top:24px}.security-card>.danger{background:#efaaaa;color:#351616}.security-error{color:#f29c9c;font-size:12px;margin-top:16px;line-height:1.8}.closure-consent{display:flex;gap:9px;align-items:flex-start;text-align:left;font-size:12px;line-height:1.8;color:#bac0cf;margin-top:22px}.closure-box{width:18px;height:18px;flex-shrink:0;border:1px solid #757f92;border-radius:5px;text-align:center}.closure-box.checked{background:#d9fd88;border-color:#d9fd88;color:#101218}
</style>
