package cn.normanclub.business;

import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import cn.normanclub.business.Rows.*;

@Component
public class OrderViews {
    private final OrderMapper orders;private final LedgerMapper ledger;private final JsonMapper json;
    public OrderViews(OrderMapper orders,LedgerMapper ledger,JsonMapper json){this.orders=orders;this.ledger=ledger;this.json=json;}
    public Map<String,Object> view(Order o){
        Map<String,Object> result=json.convertValue(o,new TypeReference<LinkedHashMap<String,Object>>(){});result.values().removeIf(Objects::isNull);
        List<Member> members=orders.members(o.id);result.put("team",members);result.put("timeline",orders.events(o.id));
        if(o.settlementId!=null)result.put("settlementStatus",members.stream().allMatch(m->{Income i=ledger.income(o.id,m.playerId);return i!=null&&"paid".equals(i.status);})?"paid":"credited");
        return result;
    }
    public static Map<String,Object> income(Income i){return Rows.map("id",i.id,"playerId",i.playerId,"playerName",i.playerName,"orderIds",List.of(i.orderId),"amount",i.amount,"status",i.status,"createdAt",i.createdAt,"paidAt",i.paidAt);}
}
