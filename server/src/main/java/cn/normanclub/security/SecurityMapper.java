package cn.normanclub.security;

import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface SecurityMapper {
    class Bucket { public String bucketKey; public int hits; public long windowEnd; }
    class Ban { public String ip, reason, actor; public long expiresAt, createdAt; public boolean revoked; }
    @Insert("INSERT INTO security_bucket(bucket_key,hits,window_end) VALUES(#{key},0,#{end}) ON DUPLICATE KEY UPDATE bucket_key=#{key}")
    void ensureBucket(String key, long end);
    @Select("SELECT * FROM security_bucket WHERE bucket_key=#{key} FOR UPDATE") Bucket bucket(String key);
    @Update("UPDATE security_bucket SET hits=#{hits},window_end=#{end} WHERE bucket_key=#{key}") void bucketUpdate(String key,int hits,long end);
    @Select("SELECT * FROM ip_ban WHERE ip=#{ip} AND revoked=FALSE AND expires_at>#{now}") Ban activeBan(String ip,long now);
    @Insert("INSERT INTO ip_ban(ip,reason,expires_at,created_at,actor,revoked) VALUES(#{ip},#{reason},#{expiresAt},#{createdAt},#{actor},FALSE) ON DUPLICATE KEY UPDATE reason=#{reason},expires_at=#{expiresAt},created_at=#{createdAt},actor=#{actor},revoked=FALSE")
    void ban(Ban ban);
    @Update("UPDATE ip_ban SET revoked=TRUE WHERE ip=#{ip}") int revoke(String ip);
    @Select("SELECT * FROM ip_ban ORDER BY created_at DESC LIMIT 200") List<Ban> bans();
    @Delete("DELETE FROM security_bucket WHERE window_end<#{before}") void purge(long before);
}
