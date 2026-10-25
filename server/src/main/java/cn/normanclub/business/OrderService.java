package cn.normanclub.business;

import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import cn.normanclub.auth.AuthMapper;
import cn.normanclub.business.Rows.*;
import cn.normanclub.common.*;
import cn.normanclub.jobs.TaskService;
import cn.normanclub.security.*;
import static cn.normanclub.common.ApiException.require;

@Service
public class OrderService {
    private final OrderMapper db;private final CatalogMapper catalog;private final CatalogService services;private final AuthMapper users;
    private final LedgerMapper payments;private final LedgerService ledger;private final AuditService audit;private final OrderViews views;private final Clock clock;private final TaskService tasks;
    public OrderService(OrderMapper db,CatalogMapper catalog,CatalogService services,AuthMapper users,LedgerMapper payments,LedgerService ledger,AuditService audit,OrderViews views,Clock clock,TaskService tasks){this.db=db;this.catalog=catalog;this.services=services;this.users=users;this.payments=payments;this.ledger=ledger;this.audit=audit;this.views=views;this.clock=clock;this.tasks=tasks;}
    public void mayRead(Order o,Actor a){require(a.staff()||o.bossId.equals(a.id())||db.members(o.id).stream().anyMatch(m->m.playerId.equals(a.playerId()))||("waiting".equals(o.status)&&Objects.equals(o.preferredPlayerId,a.playerId())&&a.playerId()!=null),403,"无权查看该订单");}
    public Map<String,Object> get(Actor a,String id){Order o=db.order(Inputs.identifier(id));require(o!=null,404,"订单不存在");mayRead(o,a);return views.view(o);}
    public List<Map<String,Object>> list(Actor a,Map<String,String> filter){
        require(Set.of("scope","status","serviceType","gameId","query","refund").containsAll(filter.keySet()),400,"筛选条件不受支持");
        String scope=filter.getOrDefault("scope","mine");require(Set.of("mine","all","hall").contains(scope),400,"订单范围无效");
        String type=nonAll(filter.get("serviceType")),game=nonAll(filter.get("gameId")),status=nonAll(filter.get("status")),refund=nonAll(filter.get("refund"));
        if(type!=null)CatalogService.type(type);if(game!=null)CatalogService.game(game);
        if(status!=null)require(Set.of("unpaid","waiting","accepted","serving","confirming","completed","cancelled").contains(status),400,"订单状态无效");
        if(refund!=null)require(Set.of("none","pending","processing","refunded","rejected").contains(refund),400,"售后状态无效");
        boolean hall=scope.equals("hall");if(hall&&!a.staff())require(!catalog.qualifications(a.playerId()).isEmpty(),403,"尚未取得接单资格");
        String query=filter.get("query")==null?null:Inputs.like(filter.get("query"));
        return db.list(a.staff(),hall,a.id(),a.playerId(),type,game,status,refund,query).stream().filter(o->!hall||a.staff()||services.qualified(catalog.profile(a.playerId()),o.serviceType,o.gameId)).map(views::view).toList();
    }
    private String nonAll(String value){return value==null||value.isBlank()||value.equals("all")?null:value;}
    @Transactional public Map<String,Object> create(Actor a,Map<String,Object> body,String requestKey){
        a.user();Inputs in=new Inputs(body,"productId","serviceType","task","taskPrice","gameId","region","playerId","quantity","note");
        require(requestKey!=null&&requestKey.matches("[A-Za-z0-9_-]{8,80}"),400,"创建订单需要有效的 Idempotency-Key");
        users.lockUser(a.id());String hash=Crypto.sha256(services.encode(new TreeMap<>(body)));Request previous=db.request(a.id(),requestKey);
        if(previous!=null){require(previous.requestHash.equals(hash),409,"同一请求号不能用于不同订单内容");return views.view(db.order(previous.orderId));}
        String type=in.has("serviceType")?CatalogService.type(in.text("serviceType",1,12)):"hourly",gameId=in.text("gameId",1,24),region=in.text("region",1,40),preferred=in.optional("playerId",48),productId=in.optional("productId",48);
        Map<String,Object> game=CatalogService.game(gameId);require(((List<?>)game.get("regions")).contains(region),400,"请选择有效游戏区服");int quantity=in.integer("quantity",1,8);String task="";Integer price=null;Product product=null;
        if(!productId.isEmpty()){
            product=catalog.lockProduct(Inputs.identifier(productId));require(product!=null&&product.active,409,"商品已下架或不存在");
            require(product.gameId.equals(gameId)&&product.serviceType.equals(type)&&(preferred.isEmpty()||preferred.equals(product.playerId)),400,"商品游戏、类型或服务成员不一致");preferred=product.playerId;price=product.price;task=product.task;
        }
        Profile p=null;if(!preferred.isEmpty()){p=catalog.lockProfile(Inputs.identifier(preferred));require(p!=null&&!p.userId.equals(a.id())&&services.qualified(p,type,gameId),403,"当前成员不可预约，不能邀请自己或无资格成员");}
        if(type.equals("escort")){
            require(quantity==1,400,"护航按整单计价");if(product==null){task=in.text("task",1,200);price=in.integer("taskPrice",100,100000);}
        }else {task="";if(price==null)price=p==null?catalog.minimumPrice(type,gameId):p.price;}
        require(price!=null,409,"该游戏暂未开放此服务");
        Order o=new Order();o.id=Crypto.id("NC_");o.productId=product==null?null:product.id;o.productTitle=product==null?null:product.title;o.bossId=a.id();o.bossName=a.name();o.gameId=gameId;o.gameName=(String)game.get("name");o.region=region;o.serviceType=type;o.task=task;o.preferredPlayerId=p==null?null:p.id;o.preferredPlayerName=p==null?null:p.name;o.unitPrice=price;o.quantity=quantity;o.amount=Math.multiplyExact(price,quantity);o.commissionBps=2000;o.earnings=(int)((long)o.amount*8000/10000);o.status="unpaid";o.refundStatus="none";o.note=in.optional("note",200);o.createdAt=clock.millis();o.expiresAt=o.createdAt+600_000;db.insert(o);
        Request record=new Request();record.ownerId=a.id();record.requestKey=requestKey;record.requestHash=hash;record.orderId=o.id;record.createdAt=clock.millis();db.requestInsert(record);
        tasks.enqueue("CLOSE_ORDER",o.id,o.expiresAt);event(o,a.name(),"订单已创建",null);audit.record(a.name(),"创建订单",o.id,o.gameName+" · "+o.amount+" 分");return views.view(o);
    }
    @Transactional public Map<String,Object> act(Actor a,String id,String action,Map<String,Object> body){
        Order o=db.lock(Inputs.identifier(id));require(o!=null,404,"订单不存在");
        switch(action){
            case "pay" -> throw new ApiException(503,"支付渠道尚未接入，请通过支付尝试接口查询渠道状态");
            case "accept","assign" -> {
                Inputs in=new Inputs(body,"playerId","reason");boolean assign=action.equals("assign");if(assign)a.roles("admin","customer_service");else a.user();
                require(o.paidAt!=null&&Set.of("waiting","accepted").contains(o.status)&&!frozen(o),409,"当前订单不可接取或改派");if(!assign)require(o.status.equals("waiting"),409,"订单已被接取");
                String pid=assign?in.text("playerId",1,48):a.playerId();Profile p=catalog.lockProfile(pid);validMember(o,p);if(!assign)require(o.preferredPlayerId==null||o.preferredPlayerId.equals(pid),403,"这是其他成员的指定邀请");
                if(Objects.equals(o.playerId,pid)&&o.status.equals("accepted"))return views.view(o);
                String reason=in.optional("reason",200);if(o.playerId!=null)require(reason.length()>=2,400,"改派必须填写原因");require(db.busy(pid)==null,409,"该成员还有未完成订单");
                String previous=o.playerName;db.release(o.id);db.clearMembers(o.id);db.occupy(pid,o.id);add(o,p,0);o.playerId=p.id;o.playerName=p.name;o.status="accepted";event(o,a.name(),previous==null?"成员已接单":"订单已改派",(previous==null?"":previous+" → ")+p.name+(reason.isEmpty()?"":" · "+reason));
            }
            case "add-assistant" -> {Inputs in=new Inputs(body,"playerId");lead(o,a);require(o.serviceType.equals("escort")&&o.status.equals("accepted")&&!frozen(o),409,"仅待开始的护航订单可以添加副手");List<Member> members=db.members(o.id);require(members.size()<8,409,"协作人数已达上限");Profile p=catalog.lockProfile(in.text("playerId",1,48));validMember(o,p);require(members.stream().noneMatch(m->m.playerId.equals(p.id))&&db.busy(p.id)==null,409,"该成员已加入协作或另有未完成订单");db.occupy(p.id,o.id);add(o,p,members.stream().mapToInt(m->m.position).max().orElse(0)+1);event(o,a.name(),"已添加副手",p.name+" · 完成后均分净报酬");}
            case "remove-assistant" -> {Inputs in=new Inputs(body,"playerId");lead(o,a);require(o.serviceType.equals("escort")&&o.status.equals("accepted")&&!frozen(o),409,"当前不可变更协作成员");String pid=in.text("playerId",1,48);require(!pid.equals(o.playerId)&&db.members(o.id).stream().anyMatch(m->m.playerId.equals(pid)),400,"只能移除已加入的副手");db.removeMember(o.id,pid);db.releaseMember(o.id,pid);event(o,a.name(),"已移除副手",null);}
            case "start" -> {new Inputs(body);lead(o,a);require(o.status.equals("accepted")&&!frozen(o),409,"当前无法开始服务");o.status="serving";o.startedAt=clock.millis();event(o,a.name(),"服务已开始",null);}
            case "finish" -> {Inputs in=new Inputs(body,"note","taskCompleted");lead(o,a);require(o.status.equals("serving")&&!frozen(o),409,"当前无法提交完成");if(o.serviceType.equals("escort")){require(in.bool("taskCompleted"),400,"请确认任务已经达成");o.taskCompleted=true;}else require(o.startedAt!=null&&clock.millis()-o.startedAt>=o.quantity*3_600_000L,409,"约定时长尚未完成");o.status="confirming";event(o,a.name(),"服务已结束，等待用户确认",in.text("note",2,200));}
            case "confirm" -> {new Inputs(body);owner(o,a);if(o.status.equals("completed"))return views.view(o);require(o.status.equals("confirming")&&!frozen(o),409,"当前无法确认完成");o.status="completed";o.finishedAt=clock.millis();db.release(o.id);ledger.credit(o);event(o,a.name(),"用户确认完成","系统已自动计算并记录各成员净报酬；转账渠道待接入");}
            case "cancel" -> {new Inputs(body);if(!Set.of("admin","customer_service").contains(a.role()))owner(o,a);require(o.status.equals("unpaid"),409,"已付款订单请申请售后退款");require(payments.payments(o.id).stream().noneMatch(p->Set.of("creating","pending","unknown").contains(p.status)),409,"支付结果待确认，请稍后再试");o.status="cancelled";event(o,a.name(),"订单已取消",null);}
            case "refund" -> {Inputs in=new Inputs(body,"amount","reason");if(!Set.of("admin","customer_service").contains(a.role()))owner(o,a);require(o.paidAt!=null&&!o.status.equals("cancelled")&&Set.of("none","rejected").contains(o.refundStatus)&&o.settlementId==null,409,"该订单当前不可申请退款");Refund r=new Refund();r.id=Crypto.id("RF_");r.orderId=o.id;r.paymentId=o.paymentId;r.amount=in.integer("amount",1,o.amount);r.reason=in.text("reason",5,200);r.status="pending";r.createdAt=clock.millis();payments.insertRefund(r);o.refundId=r.id;o.refundAmount=r.amount;o.refundReason=r.reason;o.refundStatus="pending";event(o,a.name(),"已申请退款",r.reason);}
            case "review-refund" -> {Inputs in=new Inputs(body,"approve","note");a.roles("admin","finance");require(o.refundStatus.equals("pending"),409,"该退款已经处理");Refund r=payments.lockRefund(o.refundId);require(r!=null,409,"退款记录缺失");r.reviewNote=in.text("note",2,500);r.reviewedAt=clock.millis();o.refundReviewNote=r.reviewNote;
                if(in.bool("approve")){r.status="processing";o.refundStatus="processing";tasks.enqueue("REFUND",r.id,clock.millis());event(o,a.name(),"退款审核通过，等待渠道处理",r.reviewNote);}else{r.status="rejected";o.refundStatus="rejected";ledger.credit(o);event(o,a.name(),"退款申请未通过",r.reviewNote);}payments.updateRefund(r);}
            default -> throw new ApiException(404,"接口不存在");
        }
        require(db.update(o)==1,409,"订单已更新，请刷新后重试");audit.record(a.name(),"订单操作："+action,o.id,"状态："+o.status+"；售后："+o.refundStatus);return views.view(db.order(o.id));
    }
    private boolean frozen(Order o){return Set.of("pending","processing").contains(o.refundStatus);}
    private void owner(Order o,Actor a){a.user();require(o.bossId.equals(a.id()),403,"只能操作自己下单的订单");}
    private void lead(Order o,Actor a){a.user();require(Objects.equals(o.playerId,a.playerId())&&a.playerId()!=null,403,"仅接单负责人可以操作");}
    private void validMember(Order o,Profile p){require(p!=null&&p.available&&!p.userId.equals(o.bossId)&&services.qualified(p,o.serviceType,o.gameId),403,"成员未上线、资格不匹配或为下单用户本人");}
    private void add(Order o,Profile p,int position){Member m=new Member();m.orderId=o.id;m.playerId=p.id;m.name=p.name;m.position=position;db.addMember(m);}
    public void event(Order o,String actor,String label,String note){Event e=new Event();e.id=Crypto.id("ev_");e.orderId=o.id;e.at=clock.millis();e.actor=actor;e.label=label;e.note=note;db.event(e);}
    @Transactional public Map<String,Object> grab(Actor a,Map<String,Object> body){
        a.user();Inputs in=new Inputs(body,"serviceType","gameId");String type=CatalogService.type(in.text("serviceType",1,12)),game=in.optional("gameId",24);if(!game.isEmpty())CatalogService.game(game);
        Profile p=catalog.profile(a.playerId());require(p!=null&&p.available&&p.approved&&db.busy(p.id)==null,409,"请取得资格、开启接单并完成当前服务");
        for(Order o:db.candidates(a.id(),type))if((game.isEmpty()||game.equals(o.gameId))&&services.qualified(p,type,o.gameId))return act(a,o.id,"accept",Map.of());
        throw new ApiException(404,"当前筛选下暂无可接取的公共单");
    }
    @Transactional public boolean closeExpired(String id){
        Order o=db.lock(id);if(o==null||!o.status.equals("unpaid"))return true;if(o.expiresAt>clock.millis())return false;
        if(payments.payments(id).stream().anyMatch(p->Set.of("creating","pending","unknown").contains(p.status)))return false;
        o.status="cancelled";require(db.update(o)==1,409,"订单状态冲突");event(o,"系统","超时未支付，订单已关闭",null);audit.record("系统","自动关闭订单",id,"待支付已满 10 分钟");return true;
    }
}
