package cn.normanclub.acceptance;

import java.time.Clock;
import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import cn.normanclub.business.*;
import cn.normanclub.common.*;
import cn.normanclub.payment.*;
import cn.normanclub.security.Actor;
import static cn.normanclub.common.ApiException.require;

@RestController
public class AcceptanceOrderController {
    private final AcceptanceGuard guard;private final AcceptanceGateway gateway;private final PaymentService payments;private final OrderMapper db;private final OrderService orders;private final Clock clock;
    public AcceptanceOrderController(AcceptanceGuard guard,AcceptanceGateway gateway,PaymentService payments,OrderMapper db,OrderService orders,Clock clock){this.guard=guard;this.gateway=gateway;this.payments=payments;this.db=db;this.orders=orders;this.clock=clock;}
    @PostMapping("/v1/acceptance/orders/{id}/pay")
    public ApiResponse<?> pay(@AuthenticationPrincipal Actor actor,@PathVariable String id,HttpServletRequest request){
        guard.check(request);var current=orders.get(actor,id);require(actor.id().equals(current.get("bossId")),403,"只能模拟支付自己下的订单");
        if(!"unpaid".equals(current.get("status")))return ApiResponse.ok(current,request);
        var attempt=payments.create(actor,id,Map.of("channel","acceptance"),"acceptance-pay-"+id);payments.prepare((String)attempt.get("id"));payments.acceptVerified(gateway.verified(payments.payment((String)attempt.get("id"))));
        return ApiResponse.ok(orders.get(actor,id),request);
    }
    @PostMapping("/v1/acceptance/orders/{id}/demo-elapse") @Transactional
    public ApiResponse<?> elapse(@AuthenticationPrincipal Actor actor,@PathVariable String id,HttpServletRequest request){
        guard.check(request);actor.user();var order=db.lock(Inputs.identifier(id));require(order!=null,404,"订单不存在");
        require(order.status.equals("serving")&&order.serviceType.equals("hourly")&&Objects.equals(actor.playerId(),order.playerId)&&!Set.of("pending","processing").contains(order.refundStatus),409,"只有本人的服务中组队订单可以快进验收时间");
        order.startedAt=clock.millis()-order.quantity*3_600_000L;require(db.update(order)==1,409,"订单状态冲突");orders.event(order,actor.name(),"内部验收：模拟走满时长","此操作仅存在于独立验收包");return ApiResponse.ok(orders.get(actor,id),request);
    }
}
