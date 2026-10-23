import { defineStore } from 'pinia';
import { ref } from 'vue';
import { ClubApi, type Role, type Session } from '@stellar/core';
import { getMobileTransport } from './services/transport';
export const api=new ClubApi(getMobileTransport());
export const useIdentity=defineStore('identity',()=>{
  const session=ref<Session|null>(uni.getStorageSync('stellar-mobile-session')||null);api.session=session.value;
  async function login(role:Role){session.value=await api.login(role);uni.setStorageSync('stellar-mobile-session',session.value);return session.value;}
  async function logout(){await api.logout();session.value=null;uni.removeStorageSync('stellar-mobile-session');}
  return {session,login,logout};
});
