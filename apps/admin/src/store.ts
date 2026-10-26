import { defineStore } from 'pinia';
import { ref } from 'vue';
import { ApiError, ClubApi, createHttpTransport, createAcceptanceTransport, type Session } from '@stellar/core';
export const httpMode=import.meta.env.VITE_API_MODE==='http';
export const acceptanceMode=import.meta.env.VITE_API_MODE==='acceptance';
// The admin always authenticates with the server, including acceptance builds.
export const api=new ClubApi(acceptanceMode?createAcceptanceTransport(import.meta.env.VITE_API_BASE_URL||'/api','staff'):createHttpTransport(import.meta.env.VITE_API_BASE_URL||'/api','staff'));
export const useStaff=defineStore('staff',()=>{
  localStorage.removeItem('norman-acceptance-admin-session');localStorage.removeItem('stellar-admin-session');
  const session=ref<Session|null>(null);
  function save(value:Session|null){session.value=value;api.session=value;}
  async function login(username:string,password:string){save(await api.staffLogin(username,password));}
  async function restore(){try{save(await api.currentSession());return session.value;}catch(error){if(error instanceof ApiError&&error.code===401){save(null);return null;}throw error;}}
  async function logout(){try{await api.logout();}catch(error){if(!(error instanceof ApiError&&error.code===401))throw error;}save(null);}
  function clear(){save(null);}
  return {session,login,restore,logout,clear};
});
