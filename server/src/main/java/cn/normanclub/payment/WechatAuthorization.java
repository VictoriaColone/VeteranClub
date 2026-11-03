package cn.normanclub.payment;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import cn.normanclub.auth.AuthMapper;
import cn.normanclub.business.OrderMapper;
import cn.normanclub.common.*;
import cn.normanclub.security.*;
import static cn.normanclub.common.ApiException.require;
import static cn.normanclub.business.Rows.map;

@Service
public class WechatAuthorization {
    private final WechatConfig config;private final WechatIdentityMapper db;private final OrderMapper orders;private final AuthMapper users;private final Crypto crypto;private final Clock clock;private final AbuseService abuse;private final JsonMapper json;private final TransactionTemplate tx;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    public WechatAuthorization(WechatConfig config,WechatIdentityMapper db,OrderMapper orders,AuthMapper users,Crypto crypto,Clock clock,AbuseService abuse,JsonMapper json,PlatformTransactionManager manager){this.config=config;this.db=db;this.orders=orders;this.users=users;this.crypto=crypto;this.clock=clock;this.abuse=abuse;this.json=json;tx=new TransactionTemplate(manager);}
    public Map<String,Object> start(Actor a,Map<String,Object> body){
        a.user();require(!a.bearer(),400,"请在微信内打开网页付款");require(config.enabled,503,"微信支付尚未配置");abuse.limit("wechat-authorize",a.id(),20,300_000);String id=new Inputs(body,"orderId").text("orderId",1,48);var order=orders.order(Inputs.identifier(id));require(order!=null&&order.bossId.equals(a.id())&&order.status.equals("unpaid")&&order.expiresAt>clock.millis(),409,"订单不可支付");
        if(db.openid(a.id(),config.appId)!=null)return map("authorized",true);
        String token=Crypto.token();var state=new WechatIdentityMapper.State();state.stateHash=Crypto.sha256(token);state.userId=a.id();state.sessionHash=a.tokenHash();state.orderId=id;state.expiresAt=clock.millis()+300_000;db.purge(clock.millis());db.state(state);
        return map("authorized",false,"url","https://open.weixin.qq.com/connect/oauth2/authorize?appid="+config.appId+"&redirect_uri="+URLEncoder.encode(config.callback(),StandardCharsets.UTF_8)+"&response_type=code&scope=snsapi_base&state="+token+"#wechat_redirect");
    }
    public String complete(Actor a,String token,String code){
        a.user();require(config.enabled&&!a.bearer(),403,"支付授权不可用");require(token!=null&&token.matches("[A-Za-z0-9_-]{40,64}")&&code!=null&&code.matches("[A-Za-z0-9_-]{1,256}"),400,"支付授权参数无效");
        String hash=Crypto.sha256(token);var state=db.get(hash);require(state!=null&&state.userId.equals(a.id())&&Crypto.equal(state.sessionHash,a.tokenHash())&&state.expiresAt>clock.millis()&&!state.consumed,403,"支付授权已过期，请重新发起");require(db.consume(hash,clock.millis())==1,409,"支付授权已使用");
        String openid;
        try{var uri=URI.create("https://api.weixin.qq.com/sns/oauth2/access_token?appid="+config.appId+"&secret="+config.appSecret+"&code="+code+"&grant_type=authorization_code");var response=http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8)).GET().build(),HttpResponse.BodyHandlers.ofString());require(response.statusCode()==200&&response.body().length()<16384,502,"微信支付授权暂不可用");var data=json.readTree(response.body());openid=data.path("openid").asText("");require(!data.has("errcode")&&openid.matches("[A-Za-z0-9_-]{16,128}")&&data.path("scope").asText("").contains("snsapi_base"),400,"微信支付授权失败，请重新发起");}
        catch(ApiException e){throw e;}catch(InterruptedException e){Thread.currentThread().interrupt();throw new ApiException(503,"微信支付授权暂不可用");}catch(Exception e){throw new ApiException(503,"微信支付授权暂不可用");}
        tx.executeWithoutResult(status->{var u=users.lockUser(a.id());var session=users.session(a.tokenHash());require(u!=null&&!u.disabled&&session!=null&&session.expiresAt>clock.millis(),403,"登录状态已失效");String cipher=crypto.encrypt(openid);if(db.update(a.id(),config.appId,cipher,clock.millis())==0)db.bind(a.id(),config.appId,cipher,clock.millis());});
        return config.origin+"/mobile/#/pages/account/wechat-pay?orderId="+state.orderId;
    }
}
