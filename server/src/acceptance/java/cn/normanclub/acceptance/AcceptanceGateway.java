package cn.normanclub.acceptance;

import java.util.Map;
import org.springframework.stereotype.Component;
import cn.normanclub.business.Rows;
import cn.normanclub.business.LedgerMapper;
import cn.normanclub.payment.PaymentGateway;
import cn.normanclub.common.ApiException;

@Component
public class AcceptanceGateway implements PaymentGateway {
    private final LedgerMapper db;
    public AcceptanceGateway(AcceptanceGuard guard,LedgerMapper db){this.db=db;}
    public String channel(){return "acceptance";}public boolean configured(){return true;}public String merchantId(){return "INTERNAL-TEST-NO-MONEY";}
    public Map<String,Object> create(Rows.Payment payment){return Map.of("acceptance",true);}
    public VerifiedPayment verified(Rows.Payment payment){return new VerifiedPayment(channel(),payment.id,"acceptance-tx-"+payment.id,merchantId(),payment.amount,"CNY","acceptance-event-"+payment.id);}
    public VerifiedPayment verify(Map<String,String> headers,byte[] body){throw new ApiException(404,"验收渠道不接受外部支付回调");}
    public Status query(Rows.Payment payment){return new Status(payment.status.equals("paid")?"paid":"pending",payment.status.equals("paid")?verified(payment):null);}
    public Status close(Rows.Payment payment){return new Status("closed",null);}
    public RefundStatus refund(Rows.Refund refund,Rows.Payment payment){return new RefundStatus("refunded",refund.id,"acceptance-refund-"+refund.id,refund.amount);}
}
