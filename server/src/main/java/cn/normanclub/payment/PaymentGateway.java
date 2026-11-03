package cn.normanclub.payment;

import java.util.Map;
import cn.normanclub.business.Rows;

/**
 * Only trusted server SDK adapters may produce VerifiedPayment. Verify the raw
 * signature/certificate, merchant/app identity, success state and provider replay
 * rules; query/close must authenticate their responses too. PaymentService then
 * binds the result to the requested channel/payment and checks stored amounts.
 */
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
