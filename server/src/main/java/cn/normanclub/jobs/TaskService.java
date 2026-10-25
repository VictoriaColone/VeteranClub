package cn.normanclub.jobs;

import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import cn.normanclub.business.Rows.Task;
import cn.normanclub.security.Crypto;

@Service
public class TaskService {
    private final TaskMapper db;private final Clock clock;
    public TaskService(TaskMapper db,Clock clock){this.db=db;this.clock=clock;}
    public void enqueue(String type,String aggregate,long at){Task t=new Task();t.id=Crypto.id("task_");t.type=type;t.aggregateId=aggregate;t.runAt=at;db.enqueue(t);}
    @Transactional public Task claim(){Task t=db.due(clock.millis());if(t!=null){db.claim(t.id,clock.millis()+60_000);t.attempts++;}return t;}
    public void done(Task t){db.finish(t.id,"done",clock.millis(),null);}
    public void retry(Task t,String reason){db.finish(t.id,t.attempts>=8?"failed":"pending",clock.millis()+Math.min(3600L,5L<<Math.min(t.attempts,9))*1000,reason);}
    public void blocked(Task t){db.finish(t.id,"blocked",clock.millis(),"渠道尚未接入；完成 SDK 接入后需恢复任务");}
}
