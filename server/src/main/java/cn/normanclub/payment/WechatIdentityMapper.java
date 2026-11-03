package cn.normanclub.payment;

import org.apache.ibatis.annotations.*;

@Mapper
public interface WechatIdentityMapper {
    class State {public String stateHash,userId,sessionHash,orderId;public long expiresAt;public boolean consumed;}
    @Select("SELECT openid_cipher FROM wechat_payment_identity WHERE user_id=#{user} AND app_id=#{app}") String openid(String user,String app);
    @Insert("INSERT INTO wechat_payment_identity(user_id,app_id,openid_cipher,created_at) VALUES(#{user},#{app},#{cipher},#{now})") void bind(String user,String app,String cipher,long now);
    @Update("UPDATE wechat_payment_identity SET openid_cipher=#{cipher},created_at=#{now} WHERE user_id=#{user} AND app_id=#{app}") int update(String user,String app,String cipher,long now);
    @Insert("INSERT INTO wechat_oauth_state(state_hash,user_id,session_hash,order_id,expires_at) VALUES(#{stateHash},#{userId},#{sessionHash},#{orderId},#{expiresAt})") void state(State s);
    @Select("SELECT * FROM wechat_oauth_state WHERE state_hash=#{hash}") State get(String hash);
    @Update("UPDATE wechat_oauth_state SET consumed=TRUE WHERE state_hash=#{hash} AND consumed=FALSE AND expires_at>#{now}") int consume(String hash,long now);
    @Delete("DELETE FROM wechat_oauth_state WHERE expires_at<#{now}") void purge(long now);
}
