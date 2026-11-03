package cn.normanclub.business;

import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import cn.normanclub.business.Rows.*;
import cn.normanclub.security.Actor;
import cn.normanclub.security.Crypto;

@Component
public class OrderViews {
    private final CatalogMapper catalog;private final OrderMapper orders;private final LedgerMapper ledger;private final JsonMapper json;private final Crypto crypto;
    public OrderViews(OrderMapper orders,LedgerMapper ledger,JsonMapper json,Crypto crypto,CatalogMapper catalog){this.catalog=catalog;this.orders=orders;this.ledger=ledger;this.json=json;this.crypto=crypto;}
    public Map<String,Object> view(Order o){
        Map<String,Object> result=json.convertValue(o,new TypeReference<LinkedHashMap<String,Object>>(){});result.values().removeIf(Objects::isNull);
        result.remove("contactPhoneEnc");result.remove("contactWechatEnc");
        List<Member> members=orders.members(o.id);result.put("team",members);result.put("timeline",orders.events(o.id));
        if(o.settlementId!=null)result.put("settlementStatus",members.stream().allMatch(m->{Income i=ledger.income(o.id,m.playerId);return i!=null&&"paid".equals(i.status);})?"paid":"credited");
        return result;
    }
    public Map<String,Object> view(Order o,Actor actor){
        Map<String,Object> result=offer(o,actor);
        if(actor.staff()||o.bossId.equals(actor.id())||orders.members(o.id).stream().anyMatch(m->m.playerId.equals(actor.playerId()))){
            if(o.contactPhoneEnc!=null)result.put("contactPhone",crypto.decrypt(o.contactPhoneEnc));
            if(o.contactWechatEnc!=null)result.put("contactWechat",crypto.decrypt(o.contactWechatEnc));
        }
        return result;
    }
    public Map<String,Object> offer(Order o,Actor actor){
        Map<String,Object> result=view(o);
        if(o.memberCommission&&o.playerId==null&&!actor.staff()&&!o.bossId.equals(actor.id())&&actor.playerId()!=null){
            Profile receiver=catalog.profile(actor.playerId());
            if(receiver!=null&&(o.preferredPlayerId==null||o.preferredPlayerId.equals(receiver.id))){result.put("estimatedCommissionBps",receiver.commissionBps);result.put("earnings",CommissionAmounts.net(CommissionAmounts.revenue(o),receiver.commissionBps));}
        }
        return result;
    }
    public static Map<String,Object> income(Income i){return Rows.map("id",i.id,"playerId",i.playerId,"playerName",i.playerName,"orderIds",List.of(i.orderId),"amount",i.amount,"status",i.status,"createdAt",i.createdAt,"paidAt",i.paidAt);}
}
