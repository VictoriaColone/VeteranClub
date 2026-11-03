package cn.normanclub.business;

import java.util.List;
import cn.normanclub.business.Rows.*;

/** Integer cents: split service revenue first, then apply each member's locked rate. */
public final class CommissionAmounts {
    private CommissionAmounts() { }
    public static int net(int gross,int bps){return (int)((long)gross*(10000-bps)/10000);}
    public static int revenue(Order order){return Math.max(0,order.amount-("refunded".equals(order.refundStatus)&&order.refundAmount!=null?order.refundAmount:0));}
    public static int share(Order order,List<Member> members,int index){
        if(!order.memberCommission)return order.earnings/members.size()+(index<order.earnings%members.size()?1:0);
        int gross=revenue(order)/members.size()+(index<revenue(order)%members.size()?1:0);
        return net(gross,members.get(index).commissionBps==null?order.commissionBps:members.get(index).commissionBps);
    }
    public static int total(Order order,List<Member> members){
        if(!order.memberCommission)return order.earnings;
        if(members.isEmpty())return net(revenue(order),order.commissionBps);
        int total=0;for(int n=0;n<members.size();n++)total+=share(order,members,n);return total;
    }
}
