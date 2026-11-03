<script setup lang="ts">
import {onUnmounted,ref} from 'vue';
import {onShow,onHide} from '@dcloudio/uni-app';
import UniIcons from '@club-ui/uni-icons/uni-icons.vue';
import {type UserIdentity} from '@stellar/core';
import {api,useIdentity,httpMode} from '../../store';
import {getPlatformLayout} from '../../services/platform';
const identity=useIdentity(),layout=ref(getPlatformLayout());
const details=ref<UserIdentity|null>(null),name=ref(''),idNumber=ref(''),consent=ref(false),busy=ref(false),loading=ref(true),error=ref('');
function back(){uni.navigateBack({fail:()=>uni.reLaunch({url:'/pages/index/index'})});}
function clear(){name.value='';idNumber.value='';consent.value=false;}
async function load(){
  loading.value=true;error.value='';details.value=null;
  try{if(httpMode&&!identity.session)await identity.restore();const user=identity.session?.userId;if(!user)throw new Error('请先返回首页登录');const result=await api.userIdentity();if(identity.session?.userId===user)details.value=result;}
  catch(e){error.value=e instanceof Error?e.message:'信息加载失败';}finally{loading.value=false;}
}
async function submit(){
  if(busy.value||!details.value)return;
  if(!consent.value){error.value='请先阅读并同意身份信息处理说明';return;}
  busy.value=true;error.value='';const user=identity.session?.userId;
  try{const result=await api.submitIdentity({name:name.value.trim(),idNumber:idNumber.value.trim().toUpperCase(),consent:true,consentVersion:details.value.consentVersion});clear();if(identity.session?.userId===user)details.value=result;}
  catch(e){error.value=e instanceof Error?e.message:'提交失败，请重试';}finally{busy.value=false;}
}
onShow(()=>{layout.value=getPlatformLayout();void load();});
onHide(clear);onUnmounted(clear);
</script>

<template>
  <view class="identity-page" :style="{'--club-safe-bottom':(layout?.safeBottom||0)+'px'}">
    <view class="topbar account-topbar" :style="layout?{height:layout.navHeight+'px',paddingTop:layout.topPadding+'px',paddingRight:layout.rightPadding+'px'}:{}"><button class="account-back" aria-label="返回我的" @tap="back"><UniIcons type="arrow-left" :size="23" color="#e9ebf1"/></button><view class="account-page-title">实名认证</view><view class="header-spacer"/></view>
    <scroll-view scroll-y class="identity-scroll" :show-scrollbar="false">
      <view class="identity-body">
        <view class="identity-intro"><view class="identity-symbol"><UniIcons type="person" :size="28" color="#d9fd88"/></view><view class="identity-title">完善身份信息</view><view class="identity-caption">用于身份登记与年龄判断，信息加密保存。</view></view>
        <view v-if="loading" class="identity-caption">正在加载…</view>
        <template v-else-if="details">
          <view v-if="details.status==='self_declared'" class="identity-card">
            <view class="identity-status">身份信息已提交<text :class="{minor:!details.adult}">{{details.adult?'已满18周岁':'未满18周岁'}}</text></view>
            <view class="identity-row"><text>姓名</text><text>{{details.maskedName}}</text></view><view class="identity-row"><text>身份证号</text><text>{{details.maskedIdNumber}}</text></view>
            <view class="identity-caption">年龄根据填写的证件信息计算。修改身份信息请联系平台客服。</view>
          </view>
          <view v-else class="identity-card">
            <view class="form-label">真实姓名</view><input v-model="name" class="form-input" aria-label="真实姓名" :maxlength="60" placeholder="与身份证一致的姓名" placeholder-class="field-placeholder" :cursor-spacing="24" :disabled="busy"/>
            <view class="form-label">身份证号码</view><input v-model="idNumber" class="form-input" aria-label="身份证号码" type="idcard" :maxlength="18" placeholder="18位居民身份证号码" placeholder-class="field-placeholder" :cursor-spacing="24" :disabled="busy"/>
            <view class="identity-privacy">平台仅为身份登记、年龄判断及账号去重处理姓名和身份证号码。身份证不会展示给其他用户，提交后仅展示脱敏信息。暂不受理未满14周岁用户的身份信息。</view>
            <button class="identity-consent" role="checkbox" :aria-checked="consent" :disabled="busy" @tap="consent=!consent"><view class="consent-box" :class="{checked:consent}">{{consent?'✓':''}}</view><text>我已阅读并同意上述身份信息处理说明</text></button>
            <button class="primary full" :disabled="busy||!consent||name.trim().length<2||idNumber.trim().length!==18" @tap="submit">{{busy?'正在提交…':'提交身份信息'}}</button>
          </view>
          <view class="identity-disclosure"><UniIcons type="info" :size="16" color="#aaaebe"/><text>当前为用户填报，尚未接入权威身份核验，不代表证件已验真或本人认证通过。</text></view>
        </template>
        <view v-if="error" class="identity-error" role="alert">{{error}}<button v-if="!details" class="secondary small" @tap="load">重新加载</button></view>
      </view>
    </scroll-view>
  </view>
</template>

<style lang="scss">@import "../../styles/mobile.scss";</style>
<style scoped lang="scss">
.identity-page{height:100dvh;max-width:520px;margin:auto;display:flex;flex-direction:column;background:#101218;color:#e9ebf1}.header-spacer{width:40px}.identity-scroll{flex:1;min-height:0;height:0;overscroll-behavior:contain}.identity-body{padding:28px 20px calc(32px + var(--club-safe-bottom))}.identity-intro{margin-bottom:28px}.identity-symbol{width:54px;height:54px;border:1px solid #566338;border-radius:16px;background:#d9fd880b;display:flex;align-items:center;justify-content:center;margin-bottom:20px}.identity-title{font-size:23px;font-weight:700}.identity-caption{font-size:12px;color:#989eae;line-height:1.8;margin-top:10px}.identity-card{background:#1b2029;border:1px solid #333b4a;border-radius:18px;padding:20px}.identity-card>.form-label:first-child{margin-top:0}.identity-privacy{font-size:12px;color:#9ca3b3;line-height:1.9;margin:22px 0 18px}.identity-consent{display:flex;align-items:flex-start;gap:9px;padding:0;background:none;border:none;text-align:left;color:#bec3d0;font-size:12px;line-height:1.7}.consent-box{flex-shrink:0;width:17px;height:17px;box-sizing:border-box;border:1px solid #727b8c;border-radius:5px;margin-top:2px;text-align:center;line-height:15px}.consent-box.checked{background:#d9fd88;border-color:#d9fd88;color:#18200e}.identity-card>.primary{margin-top:24px}.identity-disclosure{display:flex;align-items:flex-start;gap:8px;font-size:12px;line-height:1.8;color:#9299aa;margin:20px 4px}.identity-status{font-size:17px;font-weight:600;display:flex;flex-wrap:wrap;align-items:center;gap:12px;margin-bottom:18px}.identity-status>text{font-size:11px;color:#d9fd88;background:#d9fd8810;padding:4px 8px;border-radius:7px}.identity-status>text.minor{color:#edb18d;background:#edb18d10}.identity-row{display:flex;justify-content:space-between;gap:12px;padding:16px 0;border-bottom:1px solid #303744;font-size:13px}.identity-row>text:first-child{color:#959dae}.identity-error{margin-top:16px;font-size:13px;color:#f29c9c;line-height:1.8}.identity-error button{margin-top:12px}
</style>
