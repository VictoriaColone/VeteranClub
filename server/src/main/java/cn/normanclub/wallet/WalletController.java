package cn.normanclub.wallet;

import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import cn.normanclub.common.ApiResponse;
import cn.normanclub.security.Actor;

@RestController
@RequestMapping("/v1")
public class WalletController {
    private final WalletService wallets;
    public WalletController(WalletService wallets){this.wallets=wallets;}
    @GetMapping("/wallet") ApiResponse<?> wallet(@AuthenticationPrincipal Actor a,HttpServletRequest r){return ApiResponse.ok(wallets.summary(a),r);}
    @PostMapping("/wallet/bank") ApiResponse<?> bank(@AuthenticationPrincipal Actor a,@RequestBody Map<String,Object> body,HttpServletRequest r){return ApiResponse.ok(wallets.saveBank(a,body),r);}
    @PostMapping("/wallet/withdrawals") ApiResponse<?> withdraw(@AuthenticationPrincipal Actor a,@RequestBody Map<String,Object> body,@RequestHeader(value="Idempotency-Key",required=false)String key,HttpServletRequest r){return ApiResponse.ok(wallets.withdraw(a,body,key),r);}
    @PostMapping("/wallet/withdrawals/{id}/cancel") ApiResponse<?> cancel(@AuthenticationPrincipal Actor a,@PathVariable String id,HttpServletRequest r){return ApiResponse.ok(wallets.cancel(a,id),r);}
    @GetMapping("/admin/withdrawals") ApiResponse<?> list(@AuthenticationPrincipal Actor a,HttpServletRequest r){return ApiResponse.ok(wallets.list(a),r);}
    @PostMapping("/admin/withdrawals/{id}/bank") ApiResponse<?> details(@AuthenticationPrincipal Actor a,@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest r){return ApiResponse.ok(wallets.details(a,id,body),r);}
    @PostMapping("/admin/withdrawals/{id}/{action}") ApiResponse<?> action(@AuthenticationPrincipal Actor a,@PathVariable String id,@PathVariable String action,@RequestBody Map<String,Object> body,HttpServletRequest r){return ApiResponse.ok(wallets.action(a,id,action,body),r);}
}
