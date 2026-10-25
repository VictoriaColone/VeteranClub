package cn.normanclub.payment;

import java.util.Map;
import cn.normanclub.business.Rows;

/** SDK adapters must verify signatures, merchant/app identity, amount and currency before returning verified results. */
public interface PaymentGateway {
    String channel();
    boolean configured();
    String merchantId();
    Map<String,Object> create(Rows.Payment payment);
    VerifiedPayment verify(Map<String,String> headers,byte[] body);
    Status query(Rows.Payment payment);
    Status close(Rows.Payment payment);
    RefundStatus refund(Rows.Refund refund,Rows.Payment payment);
    record VerifiedPayment(String channel,String paymentId,String transactionId,String merchantId,int amount,String currency,String eventId) { }
    record Status(String state,VerifiedPayment payment) { }
    record RefundStatus(String state,String refundId,String channelRefundId,int amount) { }
}
