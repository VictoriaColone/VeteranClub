package cn.normanclub.payment;

import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import cn.normanclub.security.Actor;
import cn.normanclub.common.ApiResponse;

@RestController
@RequestMapping("/v1")
public class PaymentController {
    private final PaymentService payments;private final WechatAuthorization wechat;public PaymentController(PaymentService payments,WechatAuthorization wechat){this.payments=payments;this.wechat=wechat;}
    @PostMapping("/payments/wechat/authorize") ApiResponse<?> authorize(@AuthenticationPrincipal Actor a,@RequestBody Map<String,Object> body,HttpServletRequest r){return ApiResponse.ok(wechat.start(a,body),r);}
    @GetMapping("/payments/wechat/oauth/callback") ResponseEntity<?> callback(@AuthenticationPrincipal Actor a,@RequestParam String state,@RequestParam String code){return ResponseEntity.status(303).header("Location",wechat.complete(a,state,code)).header("Referrer-Policy","no-referrer").build();}
    @GetMapping("/payments/channels") ApiResponse<?> channels(HttpServletRequest r){return ApiResponse.ok(payments.channels(),r);}
    @GetMapping("/orders/{id}/payment-attempts") ApiResponse<?> attempts(@AuthenticationPrincipal Actor a,@PathVariable String id,HttpServletRequest r){return ApiResponse.ok(payments.attempts(a,id),r);}
    @PostMapping("/orders/{id}/payment-attempts") ApiResponse<?> create(@AuthenticationPrincipal Actor a,@PathVariable String id,@RequestBody Map<String,Object> body,@RequestHeader(value="Idempotency-Key",required=false)String key,HttpServletRequest r){return ApiResponse.ok(payments.create(a,id,body,key),r);}
    @GetMapping("/payments/{id}") ApiResponse<?> get(@AuthenticationPrincipal Actor a,@PathVariable String id,HttpServletRequest r){return ApiResponse.ok(payments.get(a,id),r);}
    @PostMapping("/payments/notify/{channel}") ResponseEntity<?> notify(@PathVariable String channel,@RequestHeader Map<String,String> headers,@RequestBody byte[] body){
        payments.notify(channel,headers,body);
        return channel.equals("alipay")?ResponseEntity.ok("success"):ResponseEntity.ok(Map.of("code","SUCCESS","message","成功"));
    }
}
