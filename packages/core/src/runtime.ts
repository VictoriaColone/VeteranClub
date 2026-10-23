// Mini Program JS runtimes need not provide browser URL or Intl globals.
export function parseMockPath(path:string){
  const [pathname,query='']=path.split('#')[0].split('?');
  const params=new Map<string,string>();
  const decode=(s:string)=>decodeURIComponent(s.replace(/\+/g,' '));
  for(const pair of query.split('&')){
    if(!pair)continue;
    const index=pair.indexOf('=');
    const key=decode(index<0?pair:pair.slice(0,index));
    if(!params.has(key))params.set(key,decode(index<0?'':pair.slice(index+1)));
  }
  return {route:pathname.replace(/\/$/,''),query:params};
}
const pad=(n:number)=>String(n).padStart(2,'0');
function shanghaiParts(value:number){
  const date=new Date(value+8*3600000);
  return {year:date.getUTCFullYear(),month:pad(date.getUTCMonth()+1),day:pad(date.getUTCDate()),hour:pad(date.getUTCHours()),minute:pad(date.getUTCMinutes())};
}
export function shanghaiDay(value:number){const p=shanghaiParts(value);return `${p.year}-${p.month}-${p.day}`;}
export function clubTime(value:number){const p=shanghaiParts(value);return `${p.month}/${p.day} ${p.hour}:${p.minute}`;}
export function formatCompactMoney(cents:number){
  const [whole,fraction]=String(Number((cents/100).toFixed(2))).split('.');
  return whole.replace(/\B(?=(\d{3})+(?!\d))/g,',')+(fraction?'.'+fraction:'');
}
