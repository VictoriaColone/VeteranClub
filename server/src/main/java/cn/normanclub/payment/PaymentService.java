package cn.normanclub.payment;

import java.time.Clock;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import cn.normanclub.business.*;
import cn.normanclub.business.Rows.*;
import cn.normanclub.common.*;
import cn.normanclub.jobs.TaskService;
import cn.normanclub.security.*;
import static cn.normanclub.common.ApiException.require;
import static cn.normanclub.business.Rows.map;

@Service
public class PaymentService {
    private final PublicOrderPool pool;private final Map<String,PaymentGateway> gateways;private final OrderMapper orders;private final LedgerMapper db;private final CatalogMapper catalog;private final CatalogService services;
    private final LedgerService ledger;private final TaskService tasks;private final AuditService audit;private final Clock clock;private final JsonMapper json;private final TransactionTemplate tx;
    public PaymentService(List<PaymentGateway> gateways,OrderMapper orders,LedgerMapper db,CatalogMapper catalog,CatalogService services,LedgerService ledger,TaskService tasks,AuditService audit,Clock clock,JsonMapper json,PlatformTransactionManager manager,PublicOrderPool pool){this.pool=pool;this.gateways=gateways.stream().collect(Collectors.toMap(PaymentGateway::channel,g->g));this.orders=orders;this.db=db;this.catalog=catalog;this.services=services;this.ledger=ledger;this.tasks=tasks;this.audit=audit;this.clock=clock;this.json=json;tx=new TransactionTemplate(manager);}
    public PaymentGateway gateway(String name){PaymentGateway g=gateways.get(name);require(g!=null,400,"不支持的支付渠道");return g;}
    public List<Map<String,Object>> channels(){return gateways.values().stream().sorted(Comparator.comparing(PaymentGateway::channel)).map(g->map("channel",g.channel(),"enabled",g.configured(),"message",g.configured()?"可用":"渠道尚未接入")).toList();}
    @Transactional public Map<String,Object> create(Actor actor,String orderId,Map<String,Object> body,String requestKey){
        actor.user();String channel=new Inputs(body,"channel").text("channel",1,24);PaymentGateway gateway=gateway(channel);require(gateway.configured(),503,"支付渠道尚未接入，当前不会收款");
        require(requestKey!=null&&requestKey.matches("[A-Za-z0-9_-]{8,80}"),400,"需要有效的 Idempotency-Key");
        Order o=orders.lock(Inputs.identifier(orderId));require(o!=null,404,"订单不存在");require(o.bossId.equals(actor.id()),403,"只能支付自己的订单");
        Payment prior=db.paymentRequest(actor.id(),requestKey);if(prior!=null){require(prior.orderId.equals(o.id)&&prior.channel.equals(channel),409,"请求号已经用于其他支付");return view(prior);}
        require(o.status.equals("unpaid")&&o.expiresAt>clock.millis(),409,"订单已关闭或支付超时");
        if(o.productId!=null){Product product=catalog.lockProduct(o.productId);require(product!=null&&product.active&&("club".equals(product.sellerType)||services.qualified(catalog.lockProfile(product.playerId),o.serviceType,o.gameId)),409,"商品已下架或服务资格失效");}
        Payment active=db.payments(o.id).stream().filter(p->Set.of("creating","pending","unknown").contains(p.status)).findFirst().orElse(null);
        require(active==null,409,"订单已有支付尝试，请先查询结果，勿重复支付");
        Payment p=new Payment();p.id=Crypto.id("PAY_");p.orderId=o.id;p.userId=actor.id();p.channel=channel;p.requestKey=requestKey;p.amount=o.amount;p.currency="CNY";p.status="creating";p.createdAt=clock.millis();p.updatedAt=p.createdAt;db.insertPayment(p);tasks.enqueue("PAYMENT_CREATE",p.id,clock.millis());return view(p);
    }
    public Map<String,Object> get(Actor a,String id){Payment p=db.payment(Inputs.identifier(id));require(p!=null,404,"支付尝试不存在");require(a.staff()||p.userId.equals(a.id()),403,"无权查看支付记录");return view(p);}
    private Map<String,Object> view(Payment p){return map("id",p.id,"orderId",p.orderId,"channel",p.channel,"amount",p.amount,"currency",p.currency,"status",p.status,"clientParams",p.clientParams==null?null:json.readValue(p.clientParams,new TypeReference<Map<String,Object>>(){}),"createdAt",p.createdAt);}
    public boolean configuredFor(Payment p){return p!=null&&gateway(p.channel).configured();}
    public Payment payment(String id){return db.payment(id);}
    public Refund refund(String id){return db.lockRefund(id);}
    public void prepare(String id){
        Payment p=db.payment(id);if(p==null||!p.status.equals("creating"))return;PaymentGateway gateway=gateway(p.channel);require(gateway.configured(),503,"渠道未配置");
        Map<String,Object> parameters=gateway.create(p);
        tx.executeWithoutResult(status->{Order o=orders.lock(p.orderId);Payment current=db.lockPayment(id);if(current.status.equals("creating")){current.status="pending";current.clientParams=json.writeValueAsString(parameters);current.updatedAt=clock.millis();db.updatePayment(current);tasks.enqueue("PAYMENT_QUERY",id,clock.millis()+5000);}});
    }
    @Transactional public void acceptVerified(PaymentGateway.VerifiedPayment verified){
        require(verified!=null&&verified.paymentId()!=null&&verified.eventId()!=null&&verified.eventId().length()<=120&&verified.transactionId()!=null&&verified.transactionId().length()<=120,400,"支付通知格式无效");
        Payment snapshot=db.payment(Inputs.identifier(verified.paymentId()));require(snapshot!=null,404,"支付尝试不存在");Order o=orders.lock(snapshot.orderId);Payment p=db.lockPayment(snapshot.id);PaymentGateway gateway=gateway(p.channel);
        require(gateway.configured()&&verified.channel().equals(p.channel)&&Crypto.equal(verified.merchantId(),gateway.merchantId())&&verified.amount()==p.amount&&verified.amount()==o.amount&&verified.currency().equals("CNY"),400,"支付通知核验未通过");
        String key=p.channel+":"+verified.eventId();if(db.notified(key)>0)return;
        if(p.transactionId!=null)require(p.transactionId.equals(verified.transactionId()),409,"渠道交易号不一致");
        db.notification(key,p.id,clock.millis());p.transactionId=verified.transactionId();p.merchantId=verified.merchantId();p.updatedAt=clock.millis();
        if(o.paidAt!=null){p.status=Objects.equals(o.paymentId,p.id)?"paid":"duplicate_paid";if(p.status.equals("duplicate_paid"))tasks.enqueue("PAYMENT_EXCEPTION",p.id,clock.millis());db.updatePayment(p);return;}
        if(o.status.equals("cancelled")){p.status="late_paid";db.updatePayment(p);tasks.enqueue("PAYMENT_EXCEPTION",p.id,clock.millis());audit.record("系统","支付异常",o.id,"已关闭订单收到渠道收款，待退款补偿");return;}
        p.status="paid";db.updatePayment(p);o.paidAt=clock.millis();o.paymentId=p.id;o.status="waiting";require(orders.update(o)==1,409,"订单状态冲突");pool.changed();event(o,"渠道支付已确认",null);audit.record("系统","收款确认",o.id,p.channel+" · "+p.amount+" 分");
    }
    public boolean query(String id){
        Payment p=db.payment(id);if(p==null||Set.of("paid","closed","late_paid","duplicate_paid").contains(p.status))return true;
        PaymentGateway gateway=gateway(p.channel);require(gateway.configured(),503,"渠道未配置");PaymentGateway.Status state=gateway.query(p);
        if("paid".equals(state.state())){acceptInTransaction(state.payment());return true;}
        if("closed".equals(state.state())){markClosed(p);return true;}
        Order o=orders.order(p.orderId);if(o!=null&&o.expiresAt<=clock.millis()){PaymentGateway.Status closed=gateway.close(p);if("paid".equals(closed.state())){acceptInTransaction(closed.payment());return true;}if("closed".equals(closed.state())){markClosed(p);return true;}}
        return false;
    }
    private void acceptInTransaction(PaymentGateway.VerifiedPayment payment){tx.executeWithoutResult(s->acceptVerified(payment));}
    private void markClosed(Payment p){tx.executeWithoutResult(s->{orders.lock(p.orderId);Payment current=db.lockPayment(p.id);if(!Set.of("paid","late_paid","duplicate_paid").contains(current.status)){current.status="closed";current.updatedAt=clock.millis();db.updatePayment(current);}});}
    public boolean processRefund(String id){
        Refund refund=db.lockRefund(id);if(refund==null||!refund.status.equals("processing"))return true;Payment p=db.payment(refund.paymentId);PaymentGateway gateway=gateway(p.channel);require(gateway.configured(),503,"退款渠道未配置");
        PaymentGateway.RefundStatus state=gateway.refund(refund,p);if(!"refunded".equals(state.state()))return false;
        require(id.equals(state.refundId())&&refund.amount==state.amount()&&state.channelRefundId()!=null,400,"退款结果核验未通过");
        tx.executeWithoutResult(s->{Order o=orders.lock(refund.orderId);Refund r=db.lockRefund(id);if(r.status.equals("refunded"))return;require(o.refundStatus.equals("processing"),409,"退款状态冲突");r.status="refunded";r.channelRefundId=state.channelRefundId();db.updateRefund(r);o.refundStatus="refunded";o.earnings=(int)((long)(o.amount-r.amount)*(10000-o.commissionBps)/10000);if(r.amount==o.amount){o.status="cancelled";orders.release(o.id);}ledger.credit(o);require(orders.update(o)==1,409,"订单状态冲突");pool.changed();event(o,"渠道退款已确认",r.reviewNote);audit.record("系统","退款完成",o.id,r.amount+" 分");});return true;
    }
    private void event(Order o,String label,String note){Event e=new Event();e.id=Crypto.id("ev_");e.orderId=o.id;e.at=clock.millis();e.actor="系统";e.label=label;e.note=note;orders.event(e);}
}
