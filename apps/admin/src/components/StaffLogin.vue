<script setup lang="ts">
import {ref} from 'vue';
import {CLUB_NAME,CLUB_NAME_EN,type StaffChallenge} from '@stellar/core';
import {api,useStaff} from '../store';
const emit=defineEmits<{authenticated:[]}>();
const staff=useStaff();
const username=ref(''),password=ref(''),code=ref(''),error=ref(''),busy=ref(false),challenge=ref<StaffChallenge|null>(null);
async function submit(){
  if(busy.value)return;busy.value=true;error.value='';
  try{if(!challenge.value){challenge.value=await api.staffLogin(username.value,password.value);password.value='';}else{await staff.verify(challenge.value.challengeToken,code.value);challenge.value=null;code.value='';emit('authenticated');}}
  catch(e){error.value=e instanceof Error?e.message:'登录失败';}finally{busy.value=false;}
}
function back(){challenge.value=null;code.value='';password.value='';error.value='';}
</script>

<template>
  <main class="staff-login">
    <div class="staff-login-brand"><span>✳</span><div>{{CLUB_NAME}}<small>{{CLUB_NAME_EN}}</small></div></div>
    <section class="staff-login-card">
      <p class="staff-login-kicker">CLUB WORKSPACE</p><h1>{{!challenge?'员工登录':challenge.step==='enroll'?'绑定身份验证器':'安全验证'}}</h1>
      <p class="staff-login-intro">{{!challenge?'使用你的独立员工账号，开始今天的工作。':challenge.step==='enroll'?'在身份验证器中添加此密钥，再输入当前的 6 位动态验证码。':'输入身份验证器中的 6 位动态验证码。'}}</p>
      <form @submit.prevent="submit">
        <template v-if="!challenge"><label for="staff-username">员工账号</label><el-input id="staff-username" v-model="username" autocomplete="username" placeholder="请输入员工账号" :maxlength="64" size="large"/><label for="staff-password">密码</label><el-input id="staff-password" v-model="password" type="password" autocomplete="current-password" show-password placeholder="请输入密码" :maxlength="72" size="large"/></template>
        <template v-else><div v-if="challenge.step==='enroll'" class="staff-enrollment"><span>手动添加密钥 · 请妥善保管</span><code>{{challenge.secret}}</code><p>名称填写 Norman Club；类型选择“基于时间”。请勿将密钥分享给他人。</p></div><label for="staff-code">动态验证码</label><el-input id="staff-code" v-model="code" inputmode="numeric" autocomplete="one-time-code" placeholder="请输入 6 位验证码" :maxlength="6" size="large"/></template>
        <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon role="alert"/>
        <el-button native-type="submit" type="primary" size="large" :loading="busy" class="staff-login-submit">{{challenge?'验证并登录':'下一步'}}</el-button>
        <el-button v-if="challenge" text :disabled="busy" @click="back">返回账号登录</el-button>
      </form>
      <p class="staff-login-footnote">员工账号由管理员创建。忘记密码或遗失验证器，请联系管理员。</p>
    </section>
  </main>
</template>

<style scoped>
.staff-login{min-height:100vh;display:flex;flex-direction:column;align-items:center;justify-content:center;padding:40px 24px;background:radial-gradient(ellipse at 50% 0,#25283a 0,transparent 60%),var(--club-bg);gap:30px}.staff-login-brand{display:flex;align-items:center;gap:12px;font-weight:700;font-size:25px;color:var(--club-text)}.staff-login-brand>span{color:var(--club-primary);font-size:44px}.staff-login-brand small{display:block;font-size:10px;color:var(--club-muted);letter-spacing:3px;margin-top:4px}.staff-login-card{width:100%;max-width:420px;padding:34px;border:1px solid var(--club-border);border-radius:18px;background:var(--club-surface);box-shadow:0 20px 80px #0003}.staff-login-kicker{font-size:10px;letter-spacing:2px;color:var(--club-purple);margin-bottom:10px}.staff-login-intro{color:var(--club-secondary);font-size:13px;line-height:1.8;margin-top:10px}.staff-login-card form{margin-top:24px}.staff-login-card label{display:block;font-size:13px;color:var(--club-text);margin:20px 0 9px}.staff-login-card .el-alert{margin-top:20px}.staff-login-submit{width:100%;margin:26px 0 12px}.staff-login-footnote{color:var(--club-muted);font-size:11px;line-height:1.8;margin-top:20px}.staff-enrollment{background:var(--club-bg);padding:16px;border:1px solid var(--club-border);border-radius:10px;font-size:11px;color:var(--club-secondary)}.staff-enrollment code{display:block;color:var(--club-primary);font-size:15px;overflow-wrap:anywhere;letter-spacing:2px;margin:12px 0}.staff-enrollment p{line-height:1.8}
</style>
