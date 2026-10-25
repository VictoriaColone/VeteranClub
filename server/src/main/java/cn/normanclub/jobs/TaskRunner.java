package cn.normanclub.jobs;

import java.time.Clock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import cn.normanclub.auth.AuthMapper;
import cn.normanclub.business.*;
import cn.normanclub.business.Rows.*;
import cn.normanclub.config.ClubConfig;
import cn.normanclub.payment.PaymentService;
import cn.normanclub.security.SecurityMapper;

@Component
public class TaskRunner {
    private final TaskService tasks;private final OrderService orders;private final OrderMapper orderDb;private final PaymentService payments;private final ClubConfig config;private final Clock clock;private final AuthMapper auth;private final SecurityMapper security;
    public TaskRunner(TaskService tasks,OrderService orders,OrderMapper orderDb,PaymentService payments,ClubConfig config,Clock clock,AuthMapper auth,SecurityMapper security){this.tasks=tasks;this.orders=orders;this.orderDb=orderDb;this.payments=payments;this.config=config;this.clock=clock;this.auth=auth;this.security=security;}
    @Scheduled(fixedDelay=5000) public void run(){if(!config.jobsEnabled)return;for(int n=0;n<20;n++){Task t=tasks.claim();if(t==null)return;execute(t);}}
    public void execute(Task t){
        try{
            boolean done=true;
            switch(t.type){
                case "CLOSE_ORDER" -> done=orders.closeExpired(t.aggregateId);
                case "PAYMENT_CREATE" -> {if(!payments.configuredFor(payments.payment(t.aggregateId))){tasks.blocked(t);return;}payments.prepare(t.aggregateId);}
                case "PAYMENT_QUERY" -> {if(!payments.configuredFor(payments.payment(t.aggregateId))){tasks.blocked(t);return;}done=payments.query(t.aggregateId);}
                case "REFUND" -> {Refund r=payments.refund(t.aggregateId);if(r!=null&&!payments.configuredFor(payments.payment(r.paymentId))){tasks.blocked(t);return;}done=payments.processRefund(t.aggregateId);}
                case "PAYOUT","PAYMENT_EXCEPTION" -> {tasks.blocked(t);return;}
                default -> throw new IllegalStateException("unsupported task");
            }
            if(done)tasks.done(t);else tasks.retry(t,"等待渠道确认或订单关单条件");
        }catch(Exception ex){tasks.retry(t,"处理未完成，请检查渠道与任务状态");}
    }
    @Scheduled(fixedDelay=60000) public void recover(){if(!config.jobsEnabled)return;for(Order o:orderDb.expired(clock.millis()))tasks.enqueue("CLOSE_ORDER",o.id,clock.millis());}
    @Scheduled(fixedDelay=3_600_000) public void clean(){if(!config.jobsEnabled)return;long now=clock.millis();auth.purgeSessions(now);auth.purgeSms(now-86_400_000);auth.purgeChallenges(now-86_400_000);security.purge(now-86_400_000);}
}
