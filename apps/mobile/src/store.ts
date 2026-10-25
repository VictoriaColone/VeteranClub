import { defineStore } from 'pinia';
import { ref } from 'vue';
import { ApiError, ClubApi, type Role, type Session } from '@stellar/core';
import { getMobileTransport } from './services/transport';
import { isWeixinMiniProgram } from './services/platform';
export const httpMode=import.meta.env.VITE_API_MODE==='http';
export const api=new ClubApi(getMobileTransport());
export const useIdentity=defineStore('identity',()=>{
  const key=httpMode?'norman-mobile-session':'stellar-mobile-session';
  const saved=(!httpMode||isWeixinMiniProgram)?uni.getStorageSync(key):null;
  const session=ref<Session|null>(saved||null);api.session=session.value;
  function save(value:Session|null){session.value=value;api.session=value;if(!httpMode||isWeixinMiniProgram){if(value)uni.setStorageSync(key,value);else uni.removeStorageSync(key);}}
  async function restore(){if(!httpMode)return session.value;try{const current=await api.currentSession();save({...current,token:session.value?.token||current.token});return session.value;}catch(error){if(error instanceof ApiError&&error.code===401){save(null);return null;}throw error;}}
  async function login(role:Role,accountId?:string){if(httpMode)throw new Error('请通过手机号验证码登录');save(await api.login(role,accountId));return session.value;}
  async function smsLogin(phone:string,code:string){save(await api.smsLogin(phone,code));return session.value;}
  async function logout(){try{await api.logout();}catch(error){if(!(error instanceof ApiError&&error.code===401))throw error;}save(null);}
  function clear(){save(null);}
  return {session,login,smsLogin,restore,logout,clear};
});
