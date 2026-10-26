package cn.normanclub.business;

import java.util.*;

public final class Rows {
    private Rows() { }
    public static class Profile {
        public String id,userId,name,subtitle,gameIds,tags,color,avatar,bio;
        public double rating; public int completed,price;public boolean available,approved;public long createdAt;
    }
    public static class Qualification { public String playerId,serviceType,gameId;public long grantedAt; }
    public static class Application {
        public String id,userId,playerId,name,serviceType,gameIds,introduction,status,activeKey,reviewNote;
        public Integer score;public long createdAt;public Long reviewedAt;
    }
    public static class Product {
        public String id,userId,playerId,sellerType="member",sellerName,gameId,serviceType,title,description,task;
        public int price;public boolean active;public long createdAt;
    }
    public static class Order {
        public String id,productId,productTitle,bossId,bossName,gameId,gameName,region,serviceType,task;
        public String preferredPlayerId,preferredPlayerName,playerId,playerName,status,refundStatus,refundId,refundReason,refundReviewNote,note,paymentId,settlementId;
        public int unitPrice,quantity,amount,commissionBps,earnings;public int copies=1;public Integer refundAmount;public boolean taskCompleted;
        public long createdAt,expiresAt,version;public Long paidAt,startedAt,finishedAt;
    }
    public static class Member { public String orderId,playerId,name,settlementId;public int position; }
    public static class Event { public String id,orderId,actor,label,note;public long at; }
    public static class Audit { public String id,actor,action,target,detail;public long at; }
    public static class Income { public String id,orderId,playerId,playerName,status;public int amount;public long createdAt;public Long paidAt; }
    public static class Payment { public String id,orderId,userId,channel,requestKey,currency,status,transactionId,merchantId,clientParams;public int amount;public long createdAt,updatedAt; }
    public static class Refund { public String id,orderId,paymentId,status,reason,reviewNote,channelRefundId;public int amount;public long createdAt;public Long reviewedAt; }
    public static class Task { public String id,type,aggregateId,state,lastError;public long runAt;public Long leaseUntil;public int attempts; }
    public static class Request { public String ownerId,requestKey,requestHash,orderId;public long createdAt; }
    public static class WalletTotals { public long total,paid; }
    public static class DayTotals { public long revenue,orders; }
    public static class DashboardTotals { public long todayRevenue,todayOrders,liveOrders,refundPending,unassigned; }
    public static Map<String,Object> map(Object... values) {
        Map<String,Object> out=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)if(values[i+1]!=null)out.put((String)values[i],values[i+1]);return out;
    }
}
