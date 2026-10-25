import { defineStore } from 'pinia';
import { ref } from 'vue';
import { ApiError, ClubApi, createHttpTransport, createAcceptanceTransport, type Session, type Role } from '@stellar/core';
export const httpMode=import.meta.env.VITE_API_MODE==='http';
export const acceptanceMode=import.meta.env.VITE_API_MODE==='acceptance';
export const api=new ClubApi(acceptanceMode?createAcceptanceTransport(import.meta.env.VITE_API_BASE_URL||'/api','staff'):httpMode?createHttpTransport(import.meta.env.VITE_API_BASE_URL||'/api','staff'):undefined);
export const useStaff=defineStore('staff',()=>{
  const key=acceptanceMode?'norman-acceptance-admin-session':'stellar-admin-session';
  let saved:Session|null=null;if(!httpMode)try{saved=JSON.parse(localStorage.getItem(key)||'null');}catch{}
  const session=ref<Session|null>(saved);api.session=saved;
  function save(value:Session|null){session.value=value;api.session=value;if(!httpMode)localStorage.setItem(key,JSON.stringify(value));}
  async function login(role:Role){if(httpMode)throw new Error('请使用员工账号登录');save(await api.login(role));}
  async function restore(){if(!httpMode)return session.value;try{save(await api.currentSession());return session.value;}catch(error){if(error instanceof ApiError&&error.code===401){save(null);return null;}throw error;}}
  async function verify(challengeToken:string,code:string){save(await api.verifyStaff(challengeToken,code));}
  async function logout(){try{await api.logout();}catch(error){if(!(error instanceof ApiError&&error.code===401))throw error;}save(null);}
  function clear(){save(null);}
  return {session,login,restore,verify,logout,clear};
});
