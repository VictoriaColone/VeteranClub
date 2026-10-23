import {miniProgramLayout} from './layout';
export let isWeixinMiniProgram=false;
// #ifdef MP-WEIXIN
isWeixinMiniProgram=true;
// #endif
export const deviceDataLabel=isWeixinMiniProgram?'当前小程序':'当前浏览器';
export function getPlatformLayout(){
  if(!isWeixinMiniProgram)return null;
  const info=uni.getWindowInfo();
  let menu;
  try{menu=uni.getMenuButtonBoundingClientRect();}catch{/* Devtools can omit capsule metrics. */}
  return miniProgramLayout(info,menu);
}
