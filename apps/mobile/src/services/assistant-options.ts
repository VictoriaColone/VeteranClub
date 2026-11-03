import {canServe,orderMembers,type Order,type Player} from '@stellar/core';

export function assistantOptions(players:Player[],order:Order|null,query=''){
  if(!order)return [];
  const term=query.trim().toLowerCase();
  return players.filter(player=>player.approved&&player.available&&!player.busy
    &&canServe(player,'escort',order.gameId)&&player.userId!==order.bossId
    &&!orderMembers(order).some(member=>member.playerId===player.id)
    &&(!term||[player.name,player.subtitle,...player.tags].join(' ').toLowerCase().includes(term)));
}
