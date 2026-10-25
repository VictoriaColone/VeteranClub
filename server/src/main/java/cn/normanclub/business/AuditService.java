package cn.normanclub.business;

import java.time.Clock;
import java.util.*;
import org.apache.ibatis.annotations.*;
import org.springframework.stereotype.Service;
import cn.normanclub.security.Crypto;

@Service
public class AuditService {
    @Mapper public interface Store {
        @Insert("INSERT INTO audit_log(id,at,actor,action,target,detail) VALUES(#{id},#{at},#{actor},#{action},#{target},#{detail})") void insert(Rows.Audit a);
        @Select("SELECT * FROM audit_log ORDER BY at DESC LIMIT 300") List<Rows.Audit> list();
    }
    private final Store db;private final Clock clock;
    public AuditService(Store db,Clock clock) {this.db=db;this.clock=clock;}
    public void record(String actor,String action,String target,String detail) {
        Rows.Audit a=new Rows.Audit();a.id=Crypto.id("log_");a.at=clock.millis();a.actor=actor;a.action=action;a.target=target;a.detail=detail;db.insert(a);
    }
    public List<Rows.Audit> list() {return db.list();}
}
