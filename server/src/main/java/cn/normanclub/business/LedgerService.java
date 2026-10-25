package cn.normanclub.business;

import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import cn.normanclub.business.Rows.*;
import cn.normanclub.security.*;
import cn.normanclub.jobs.TaskService;
import static cn.normanclub.business.Rows.map;
import static cn.normanclub.common.ApiException.require;

@Service
public class LedgerService {
    private final LedgerMapper db;private final OrderMapper orders;private final OrderViews views;private final AuditService audit;private final Clock clock;private final TaskService tasks;
    public LedgerService(LedgerMapper db,OrderMapper orders,OrderViews views,AuditService audit,Clock clock,TaskService tasks){this.db=db;this.orders=orders;this.views=views;this.audit=audit;this.clock=clock;this.tasks=tasks;}
    /** Called with the order row locked, in the same transaction as confirmation/refund completion. */
    public void credit(Order o){
        if(!"completed".equals(o.status)||o.paidAt==null||Set.of("pending","processing").contains(o.refundStatus))return;
        List<Member> members=orders.members(o.id);require(!members.isEmpty(),409,"订单协作名单缺失");
        for(int n=0;n<members.size();n++){
            Member m=members.get(n);Income i=db.income(o.id,m.playerId);
            if(i==null){i=new Income();i.id=Crypto.id("INC_");i.orderId=o.id;i.playerId=m.playerId;i.playerName=m.name;i.amount=o.earnings/members.size()+(n<o.earnings%members.size()?1:0);i.status="credited";i.createdAt=clock.millis();db.insertIncome(i);String payout=Crypto.id("PO_");db.insertPayout(payout,i.id,i.amount,clock.millis());tasks.enqueue("PAYOUT",payout,clock.millis());audit.record("系统","自动记账",o.id,m.name+" · 净报酬 "+i.amount+" 分；转账渠道待接入");}
            orders.linkIncome(o.id,m.playerId,i.id);if(o.settlementId==null)o.settlementId=i.id;
        }
    }
    public Map<String,Object> wallet(Actor actor){
        actor.user();List<Income> incomes=db.incomes(actor.playerId());List<Order> list=orders.list(false,false,actor.id(),actor.playerId(),null,null,"completed",null,null).stream().filter(o->orders.members(o.id).stream().anyMatch(m->m.playerId.equals(actor.playerId()))).toList();
        WalletTotals totals=db.totals(actor.playerId());long total=totals.total,paid=totals.paid;
        return map("total",total,"pending",total-paid,"settled",paid,"available",total-paid,"orders",list.stream().map(views::view).toList(),"settlements",incomes.stream().map(OrderViews::income).toList());
    }
}
