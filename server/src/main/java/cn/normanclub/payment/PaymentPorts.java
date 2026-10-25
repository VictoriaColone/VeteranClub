package cn.normanclub.payment;

import java.util.Map;
import org.springframework.context.annotation.*;
import cn.normanclub.business.Rows;
import cn.normanclub.common.ApiException;

@Configuration
public class PaymentPorts {
    @Bean PaymentGateway wechatGateway(){return new Unconfigured("wechat");}
    @Bean PaymentGateway alipayGateway(){return new Unconfigured("alipay");}
    static class Unconfigured implements PaymentGateway {
        private final String channel;Unconfigured(String channel){this.channel=channel;}
        public String channel(){return channel;}public boolean configured(){return false;}public String merchantId(){return "";}
        private ApiException missing(){return new ApiException(503,(channel.equals("wechat")?"微信":"支付宝")+"支付渠道尚未接入");}
        public Map<String,Object> create(Rows.Payment p){throw missing();}
        public VerifiedPayment verify(Map<String,String> headers,byte[] body){throw missing();}
        public Status query(Rows.Payment p){throw missing();}public Status close(Rows.Payment p){throw missing();}
        public RefundStatus refund(Rows.Refund r,Rows.Payment p){throw missing();}
    }
}
