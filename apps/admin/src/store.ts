import { defineStore } from 'pinia';
import { ref } from 'vue';
import { ClubApi, createHttpTransport, type Session, type Role } from '@stellar/core';
export const api=new ClubApi(import.meta.env.VITE_API_MODE==='http'?createHttpTransport(import.meta.env.VITE_API_BASE_URL||''):undefined);
export const useStaff=defineStore('staff',()=>{
  let saved:Session|null=null;try{saved=JSON.parse(localStorage.getItem('stellar-admin-session')||'null');}catch{}
  const session=ref<Session|null>(saved);api.session=saved;
  async function login(role:Role){session.value=await api.login(role);localStorage.setItem('stellar-admin-session',JSON.stringify(session.value));}
  return {session,login};
});
