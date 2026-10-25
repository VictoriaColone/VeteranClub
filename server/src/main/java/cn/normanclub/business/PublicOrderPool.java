package cn.normanclub.business;

import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import cn.normanclub.business.Rows.Order;

/** Single application instance: RDS remains authoritative; this index is rebuilt lazily. */
@Component
public class PublicOrderPool {
    private final OrderMapper db;
    private List<Order> snapshot;
    private long refreshedAt;
    public PublicOrderPool(OrderMapper db){this.db=db;}
    public synchronized List<Order> orders(){
        long now=System.nanoTime();
        if(snapshot==null||now-refreshedAt>=TimeUnit.SECONDS.toNanos(5)){
            snapshot=List.copyOf(db.publicPool());refreshedAt=now;
        }
        return snapshot;
    }
    public synchronized void invalidate(){snapshot=null;}
    // Never publish uncommitted state, nor invalidate a newer cache from a rollback.
    public void changed(){
        if(TransactionSynchronizationManager.isSynchronizationActive()){
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                @Override public void afterCommit(){invalidate();}
            });
        }else invalidate();
    }
}
