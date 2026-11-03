package cn.normanclub.payment;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import com.wechat.pay.java.core.*;
import com.wechat.pay.java.core.exception.ServiceException;
import com.wechat.pay.java.core.notification.*;
import com.wechat.pay.java.service.payments.jsapi.JsapiServiceExtension;
import com.wechat.pay.java.service.payments.jsapi.model.*;
import com.wechat.pay.java.service.payments.model.Transaction;
import com.wechat.pay.java.service.refund.RefundService;
import com.wechat.pay.java.service.refund.model.AmountReq;
import com.wechat.pay.java.service.refund.model.CreateRequest;
import com.wechat.pay.java.service.refund.model.QueryByOutRefundNoRequest;
import tools.jackson.databind.json.JsonMapper;
import cn.normanclub.business.*;
import cn.normanclub.common.*;
import cn.normanclub.security.Crypto;
import static cn.normanclub.common.ApiException.require;
import static cn.normanclub.business.Rows.map;

/** Official SDK signs requests and verifies every channel response and notification. */
public class WechatGateway implements PaymentGateway {
    private final WechatConfig config;private final WechatIdentityMapper identities;private final OrderMapper orders;private final Crypto crypto;private final Clock clock;private final JsonMapper json;
    private final JsapiServiceExtension jsapi;private final RefundService refunds;private final NotificationParser parser;
    public WechatGateway(WechatConfig config,WechatIdentityMapper identities,OrderMapper orders,Crypto crypto,Clock clock,JsonMapper json){
        this.config=config;this.identities=identities;this.orders=orders;this.crypto=crypto;this.clock=clock;this.json=json;
        if(!config.enabled){jsapi=null;refunds=null;parser=null;return;}
        var sdk=new RSAPublicKeyConfig.Builder().merchantId(config.merchantId).privateKeyFromPath(config.privateKeyPath).merchantSerialNumber(config.serial).publicKeyFromPath(config.publicKeyPath).publicKeyId(config.publicKeyId).apiV3Key(config.apiKey).build();
        jsapi=new JsapiServiceExtension.Builder().config(sdk).build();refunds=new RefundService.Builder().config(sdk).build();
        parser=new NotificationParser(new RSAPublicKeyNotificationConfig.Builder().publicKeyFromPath(config.publicKeyPath).publicKeyId(config.publicKeyId).apiV3Key(config.apiKey).build());
    }
    public String channel(){return "wechat";}public boolean configured(){return config.enabled;}public String merchantId(){return config.merchantId;}
    private void enabled(){require(configured(),503,"微信支付尚未配置，当前不会收款");}
    public Map<String,Object> create(Rows.Payment payment){
        enabled();String cipher=identities.openid(payment.userId,config.appId);require(cipher!=null,409,"请先完成微信支付授权");var order=orders.order(payment.orderId);require(order!=null&&order.expiresAt>clock.millis(),409,"订单已过期");
        PrepayRequest r=new PrepayRequest();r.setAppid(config.appId);r.setMchid(config.merchantId);r.setOutTradeNo(payment.id);r.setDescription("诺曼部落 · "+order.gameName+" · "+(order.serviceType.equals("escort")?"任务护航":"时长组队"));r.setNotifyUrl(config.notifyUrl());r.setTimeExpire(Instant.ofEpochMilli(order.expiresAt).atOffset(ZoneOffset.ofHours(8)).toString());
        Amount amount=new Amount();amount.setTotal(payment.amount);amount.setCurrency("CNY");r.setAmount(amount);Payer payer=new Payer();payer.setOpenid(crypto.decrypt(cipher));r.setPayer(payer);
        try{var p=jsapi.prepayWithRequestPayment(r);return map("appId",p.getAppId(),"timeStamp",p.getTimeStamp(),"nonceStr",p.getNonceStr(),"package",p.getPackageVal(),"signType",p.getSignType(),"paySign",p.getPaySign());}catch(Exception e){throw new ApiException(503,"微信下单暂未完成，请查询订单结果后重试");}
    }
    private String header(Map<String,String> headers,String name){return headers.entrySet().stream().filter(e->e.getKey().equalsIgnoreCase(name)).map(Map.Entry::getValue).findFirst().orElse("");}
    public VerifiedPayment verify(Map<String,String> headers,byte[] body){
        enabled();require(body.length>0&&body.length<=65536,400,"支付通知格式无效");String timestamp=header(headers,"Wechatpay-Timestamp");
        try{require(timestamp.matches("[0-9]{10,12}")&&Math.abs(clock.instant().getEpochSecond()-Long.parseLong(timestamp))<=300,400,"支付通知已过期");var request=new RequestParam.Builder().serialNumber(header(headers,"Wechatpay-Serial")).nonce(header(headers,"Wechatpay-Nonce")).signature(header(headers,"Wechatpay-Signature")).timestamp(timestamp).body(new String(body,StandardCharsets.UTF_8)).build();
            var transaction=parser.parse(request,Transaction.class);var envelope=json.readTree(body);require("TRANSACTION.SUCCESS".equals(envelope.path("event_type").asText()),400,"不是支付成功通知");return verified(transaction,envelope.path("id").asText());
        }catch(ApiException e){throw e;}catch(Exception e){throw new ApiException(400,"微信支付通知核验未通过");}
    }
    VerifiedPayment verified(Transaction t,String event){
        require(t!=null&&Transaction.TradeStateEnum.SUCCESS.equals(t.getTradeState())&&Transaction.TradeTypeEnum.JSAPI.equals(t.getTradeType())&&config.appId.equals(t.getAppid())&&config.merchantId.equals(t.getMchid())&&t.getAmount()!=null&&"CNY".equals(t.getAmount().getCurrency()),400,"微信支付结果身份或状态不匹配");
        return new VerifiedPayment(channel(),t.getOutTradeNo(),t.getTransactionId(),t.getMchid(),t.getAmount().getTotal(),t.getAmount().getCurrency(),event);
    }
    public Status query(Rows.Payment p){
        enabled();var r=new QueryOrderByOutTradeNoRequest();r.setMchid(config.merchantId);r.setOutTradeNo(p.id);
        try{var t=jsapi.queryOrderByOutTradeNo(r);require(p.id.equals(t.getOutTradeNo())&&config.merchantId.equals(t.getMchid())&&config.appId.equals(t.getAppid()),400,"微信查单归属不匹配");if(Transaction.TradeStateEnum.SUCCESS.equals(t.getTradeState()))return new Status("paid",verified(t,"query:"+t.getTransactionId()));return new Status(Set.of("CLOSED","REVOKED","PAYERROR").contains(String.valueOf(t.getTradeState()))?"closed":"pending",null);}
        catch(ServiceException e){if("ORDER_NOT_EXIST".equals(e.getErrorCode()))return new Status("pending",null);throw new ApiException(503,"微信查单暂不可用");}
    }
    public Status close(Rows.Payment p){enabled();var r=new CloseOrderRequest();r.setMchid(config.merchantId);r.setOutTradeNo(p.id);try{jsapi.closeOrder(r);return new Status("closed",null);}catch(ServiceException e){if("ORDERPAID".equals(e.getErrorCode()))return query(p);if("ORDER_NOT_EXIST".equals(e.getErrorCode())||"ORDERCLOSED".equals(e.getErrorCode()))return new Status("closed",null);throw new ApiException(503,"微信关单结果待确认");}}
    public RefundStatus refund(Rows.Refund r,Rows.Payment p){
        enabled();String key="R"+Crypto.sha256(r.id).substring(0,31);com.wechat.pay.java.service.refund.model.Refund result;
        var query=new QueryByOutRefundNoRequest();query.setOutRefundNo(key);
        try{result=refunds.queryByOutRefundNo(query);}catch(ServiceException ex){if(!"RESOURCE_NOT_EXISTS".equals(ex.getErrorCode()))throw new ApiException(503,"退款结果待确认");var request=new CreateRequest();request.setOutTradeNo(p.id);request.setTransactionId(p.transactionId);request.setOutRefundNo(key);request.setReason("订单售后退款");var amount=new AmountReq();amount.setTotal((long)p.amount);amount.setRefund((long)r.amount);amount.setCurrency("CNY");request.setAmount(amount);result=refunds.create(request);}
        require(key.equals(result.getOutRefundNo())&&p.id.equals(result.getOutTradeNo())&&Objects.equals(p.transactionId,result.getTransactionId())&&result.getAmount()!=null&&result.getAmount().getRefund()==r.amount&&result.getAmount().getTotal()==p.amount&&"CNY".equals(result.getAmount().getCurrency()),400,"微信退款结果归属或金额不匹配");
        require(Set.of("SUCCESS","PROCESSING").contains(String.valueOf(result.getStatus())),409,"微信退款异常，须人工核对渠道结果；钱包保持冻结");
        return new RefundStatus("SUCCESS".equals(String.valueOf(result.getStatus()))?"refunded":"processing",r.id,result.getRefundId(),r.amount);
    }
}
