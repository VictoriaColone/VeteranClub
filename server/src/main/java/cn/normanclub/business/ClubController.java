package cn.normanclub.business;

import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import cn.normanclub.common.ApiResponse;
import cn.normanclub.security.Actor;
import static cn.normanclub.business.Rows.map;

@RestController
@RequestMapping("/v1")
public class ClubController {
    private final CatalogService catalog;private final OrderService orders;private final LedgerService ledger;
    public ClubController(CatalogService catalog,OrderService orders,LedgerService ledger){this.catalog=catalog;this.orders=orders;this.ledger=ledger;}
    @GetMapping("/club") ApiResponse<?> club(HttpServletRequest r){return ApiResponse.ok(map("name","诺曼部落","commissionBps",2000,"unpaidTimeoutMinutes",10,"announcement","一起出发。下单前请确认游戏区服、任务目标或服务时长。"),r);}
    @GetMapping("/catalog/games") ApiResponse<?> games(HttpServletRequest r){return ApiResponse.ok(CatalogService.GAMES,r);}
    @GetMapping("/players") ApiResponse<?> players(@AuthenticationPrincipal Actor a,@RequestParam(required=false)String gameId,HttpServletRequest r){return ApiResponse.ok(catalog.profiles(a,gameId),r);}
    @GetMapping(value="/players/events",produces="text/event-stream") org.springframework.web.servlet.mvc.method.annotation.SseEmitter presence(@AuthenticationPrincipal Actor a,jakarta.servlet.http.HttpServletResponse response){response.setHeader("X-Accel-Buffering","no");return catalog.presence(a);}
    @GetMapping("/products") ApiResponse<?> products(@AuthenticationPrincipal Actor a,@RequestParam(defaultValue="public")String scope,HttpServletRequest r){return ApiResponse.ok(catalog.products(a,scope.equals("mine")),r);}
    @PostMapping("/products") ApiResponse<?> publish(@AuthenticationPrincipal Actor a,@RequestBody Map<String,Object> body,HttpServletRequest r){return ApiResponse.ok(catalog.publish(a,body),r);}
    @PatchMapping("/products/{id}") ApiResponse<?> active(@AuthenticationPrincipal Actor a,@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest r){return ApiResponse.ok(catalog.active(a,id,body),r);}
    @GetMapping("/applications") ApiResponse<?> applications(@AuthenticationPrincipal Actor a,HttpServletRequest r){return ApiResponse.ok(catalog.applications(a),r);}
    @PostMapping("/applications") ApiResponse<?> apply(@AuthenticationPrincipal Actor a,@RequestBody Map<String,Object> body,HttpServletRequest r){return ApiResponse.ok(catalog.apply(a,body),r);}
    @PostMapping("/admin/applications/{id}/review") ApiResponse<?> review(@AuthenticationPrincipal Actor a,@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest r){return ApiResponse.ok(catalog.review(a,id,body),r);}
    @PostMapping("/player/availability") ApiResponse<?> availability(@AuthenticationPrincipal Actor a,@RequestBody Map<String,Object> body,HttpServletRequest r){return ApiResponse.ok(catalog.availability(a,body),r);}
    @GetMapping("/player/wallet") ApiResponse<?> wallet(@AuthenticationPrincipal Actor a,HttpServletRequest r){return ApiResponse.ok(ledger.wallet(a),r);}
    @PatchMapping("/admin/players/{id}") ApiResponse<?> player(@AuthenticationPrincipal Actor a,@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest r){return ApiResponse.ok(catalog.updateProfile(a,id,body),r);}
    @GetMapping("/orders") ApiResponse<?> orders(@AuthenticationPrincipal Actor a,@RequestParam Map<String,String> filter,HttpServletRequest r){return ApiResponse.ok(orders.list(a,filter),r);}
    @GetMapping("/orders/{id}") ApiResponse<?> order(@AuthenticationPrincipal Actor a,@PathVariable String id,HttpServletRequest r){return ApiResponse.ok(orders.get(a,id),r);}
    @PostMapping("/orders") ApiResponse<?> create(@AuthenticationPrincipal Actor a,@RequestBody Map<String,Object> body,@RequestHeader(value="Idempotency-Key",required=false)String key,HttpServletRequest r){return ApiResponse.ok(orders.create(a,body,key),r);}
    @PostMapping("/orders/grab") ApiResponse<?> grab(@AuthenticationPrincipal Actor a,@RequestBody Map<String,Object> body,HttpServletRequest r){return ApiResponse.ok(orders.grab(a,body),r);}
    @PostMapping("/orders/{id}/{action}") ApiResponse<?> act(@AuthenticationPrincipal Actor a,@PathVariable String id,@PathVariable String action,@RequestBody(required=false)Map<String,Object> body,HttpServletRequest r){return ApiResponse.ok(orders.act(a,id,action,body),r);}
}
