package cn.normanclub.jobs;

import java.util.List;
import org.apache.ibatis.annotations.*;
import cn.normanclub.business.Rows.Task;

@Mapper
public interface TaskMapper {
    @Insert("INSERT INTO background_task(id,type,aggregate_id,state,run_at,attempts) VALUES(#{id},#{type},#{aggregateId},'pending',#{runAt},0) ON DUPLICATE KEY UPDATE aggregate_id=#{aggregateId}") void enqueue(Task t);
    @Select("SELECT * FROM background_task WHERE (state='pending' AND run_at<=#{now}) OR (state='running' AND lease_until<=#{now}) ORDER BY run_at LIMIT 1 FOR UPDATE SKIP LOCKED") Task due(long now);
    @Update("UPDATE background_task SET state='running',lease_until=#{lease},attempts=attempts+1 WHERE id=#{id}") void claim(String id,long lease);
    @Update("UPDATE background_task SET state=#{state},run_at=#{runAt},lease_until=NULL,last_error=#{error} WHERE id=#{id}") void finish(String id,String state,long runAt,String error);
    @Select("SELECT * FROM background_task WHERE state IN ('blocked','failed') ORDER BY run_at LIMIT 100") List<Task> problems();
}
