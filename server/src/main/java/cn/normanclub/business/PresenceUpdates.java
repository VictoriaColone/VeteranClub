package cn.normanclub.business;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import cn.normanclub.security.Actor;
import static cn.normanclub.common.ApiException.require;

/** Single-instance invalidations only: profile details still require the normal authenticated API. */
@Component
public class PresenceUpdates {
    private final Map<SseEmitter,String> clients=new HashMap<>();
    private final AtomicBoolean dirty=new AtomicBoolean();
    public SseEmitter subscribe(Actor actor){
        actor.user();SseEmitter emitter=new SseEmitter(55_000L);
        synchronized(clients){
            require(clients.size()<512&&clients.values().stream().filter(actor.id()::equals).count()<4,429,"状态同步连接过多，请关闭重复页面");
            clients.put(emitter,actor.id());
        }
        emitter.onCompletion(()->remove(emitter));emitter.onTimeout(()->{remove(emitter);emitter.complete();});emitter.onError(error->remove(emitter));
        send(emitter,"presence");return emitter;
    }
    private void remove(SseEmitter emitter){synchronized(clients){clients.remove(emitter);}}
    private void send(SseEmitter emitter,String event){
        try{emitter.send(SseEmitter.event().name(event).data("refresh").reconnectTime(1500));}
        catch(IOException|IllegalStateException error){remove(emitter);}
    }
    private List<SseEmitter> snapshot(){synchronized(clients){return List.copyOf(clients.keySet());}}
    public void changed(){
        if(TransactionSynchronizationManager.isSynchronizationActive())TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){dirty.set(true);}});
        else dirty.set(true);
    }
    // Coalesce simultaneous mutations and never hold database locks while writing to clients.
    @Scheduled(fixedDelay=300) public void flush(){if(dirty.getAndSet(false))snapshot().forEach(emitter->send(emitter,"presence"));}
    @Scheduled(fixedDelay=10_000) public void heartbeat(){snapshot().forEach(emitter->send(emitter,"heartbeat"));}
    @EventListener(ContextClosedEvent.class) public void close(){snapshot().forEach(SseEmitter::complete);synchronized(clients){clients.clear();}}
}
