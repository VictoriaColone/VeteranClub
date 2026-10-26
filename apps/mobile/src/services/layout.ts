export interface WindowMetrics {windowWidth:number;windowHeight:number;statusBarHeight?:number;safeAreaInsets?:{bottom?:number}}
export interface MenuRect {top:number;bottom:number;left:number;height:number}
// Pure calculation so capsule and safe-area behavior can be checked without wx.
export function miniProgramLayout(info:WindowMetrics,menu?:MenuRect){
  const status=Math.max(0,info.statusBarHeight||0);
  const valid=!!menu&&menu.height>0&&menu.top>=status&&menu.left>0&&menu.left<info.windowWidth;
  const gap=valid?Math.max(4,menu!.top-status):8;
  const navHeight=valid?menu!.bottom+gap:status+44;
  const safeBottom=Math.max(0,info.safeAreaInsets?.bottom||0);
  return {windowHeight:info.windowHeight,status,navHeight,safeBottom,topPadding:valid?menu!.top:status+8,
    rightPadding:valid?info.windowWidth-menu!.left+12:104,
    sheetHeight:Math.max(160,Math.floor(Math.min(info.windowHeight*.84,info.windowHeight-navHeight)-46))};
}
