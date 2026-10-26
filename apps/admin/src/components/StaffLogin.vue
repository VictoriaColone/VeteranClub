<script setup lang="ts">
import {ref} from 'vue';
import {CLUB_NAME,CLUB_NAME_EN} from '@stellar/core';
import {useStaff} from '../store';
const staff=useStaff();
const username=ref(''),password=ref(''),error=ref(''),busy=ref(false);
async function submit(){
  if(busy.value)return;error.value='';
  if(!username.value.trim()||!password.value){error.value='请输入账号和密码';return;}
  busy.value=true;
  try{await staff.login(username.value.trim(),password.value);password.value='';}
  catch(e){password.value='';error.value=e instanceof Error?e.message:'登录失败，请稍后重试';}finally{busy.value=false;}
}
</script>

<template>
  <main class="staff-login">
    <div class="staff-login-brand"><span>✳</span><div>{{CLUB_NAME}}<small>{{CLUB_NAME_EN}}</small></div></div>
    <section class="staff-login-card">
      <p class="staff-login-kicker">CLUB WORKSPACE</p><h1>管理后台登录</h1>
      <p class="staff-login-intro">使用管理账号登录，处理俱乐部日常事务。</p>
      <form @submit.prevent="submit">
        <label for="staff-username">管理账号</label><el-input id="staff-username" v-model="username" autocomplete="username" placeholder="请输入管理账号" :maxlength="64" size="large" :disabled="busy"/>
        <label for="staff-password">密码</label><el-input id="staff-password" v-model="password" type="password" autocomplete="current-password" show-password placeholder="请输入密码" :maxlength="72" size="large" :disabled="busy"/>
        <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon role="alert"/>
        <el-button native-type="submit" type="primary" size="large" :loading="busy" class="staff-login-submit">登录</el-button>
      </form>
      <p class="staff-login-footnote">请妥善保管管理账号，使用完毕后退出登录。</p>
    </section>
  </main>
</template>

<style scoped>
.staff-login{min-height:100vh;display:flex;flex-direction:column;align-items:center;justify-content:center;padding:40px 24px;background:radial-gradient(ellipse at 50% 0,#25283a 0,transparent 60%),var(--club-bg);gap:30px}.staff-login-brand{display:flex;align-items:center;gap:12px;font-weight:700;font-size:25px;color:var(--club-text)}.staff-login-brand>span{color:var(--club-primary);font-size:44px}.staff-login-brand small{display:block;font-size:10px;color:var(--club-muted);letter-spacing:3px;margin-top:4px}.staff-login-card{width:100%;max-width:420px;padding:34px;border:1px solid var(--club-border);border-radius:18px;background:var(--club-surface);box-shadow:0 20px 80px #0003}.staff-login-kicker{font-size:10px;letter-spacing:2px;color:var(--club-purple);margin-bottom:10px}.staff-login-intro{color:var(--club-secondary);font-size:13px;line-height:1.8;margin-top:10px}.staff-login-card form{margin-top:24px}.staff-login-card label{display:block;font-size:13px;color:var(--club-text);margin:20px 0 9px}.staff-login-card .el-alert{margin-top:20px}.staff-login-submit{width:100%;margin:26px 0 12px}.staff-login-footnote{color:var(--club-muted);font-size:11px;line-height:1.8;margin-top:20px}</style>
