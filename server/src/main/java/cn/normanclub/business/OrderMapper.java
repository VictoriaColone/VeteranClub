package cn.normanclub.business;

import java.util.List;
import org.apache.ibatis.annotations.*;
import cn.normanclub.business.Rows.*;

@Mapper
public interface OrderMapper {
    @Select("SELECT * FROM club_order WHERE id=#{id}") Order order(String id);
    @Select("SELECT * FROM club_order WHERE id=#{id} FOR UPDATE") Order lock(String id);
    @Insert("INSERT INTO club_order(id,product_id,product_title,boss_id,boss_name,game_id,game_name,region,service_type,task,preferred_player_id,preferred_player_name,unit_price,quantity,amount,commission_bps,earnings,status,refund_status,note,created_at,expires_at) VALUES(#{id},#{productId},#{productTitle},#{bossId},#{bossName},#{gameId},#{gameName},#{region},#{serviceType},#{task},#{preferredPlayerId},#{preferredPlayerName},#{unitPrice},#{quantity},#{amount},#{commissionBps},#{earnings},#{status},#{refundStatus},#{note},#{createdAt},#{expiresAt})") void insert(Order o);
    @Update("UPDATE club_order SET player_id=#{playerId},player_name=#{playerName},status=#{status},refund_status=#{refundStatus},refund_id=#{refundId},refund_reason=#{refundReason},refund_amount=#{refundAmount},refund_review_note=#{refundReviewNote},earnings=#{earnings},paid_at=#{paidAt},started_at=#{startedAt},finished_at=#{finishedAt},payment_id=#{paymentId},settlement_id=#{settlementId},task_completed=#{taskCompleted},version=version+1 WHERE id=#{id} AND version=#{version}") int update(Order o);
    @Select("""
      <script>SELECT o.* FROM club_order o WHERE 1=1
      <choose><when test='hall'>AND o.status='waiting' AND o.paid_at IS NOT NULL AND o.preferred_player_id IS NULL AND o.refund_status NOT IN ('pending','processing') AND o.boss_id!=#{userId}</when>
      <otherwise><if test='!staff'>AND (o.boss_id=#{userId} OR EXISTS(SELECT 1 FROM order_member m WHERE m.order_id=o.id AND m.player_id=#{playerId}) OR (o.status='waiting' AND o.preferred_player_id=#{playerId}))</if></otherwise></choose>
      <if test='type!=null'>AND o.service_type=#{type}</if><if test='game!=null'>AND o.game_id=#{game}</if>
      <if test='status!=null'>AND o.status=#{status}</if><if test='refund!=null'>AND o.refund_status=#{refund}</if>
      <if test='query!=null'>AND (o.id LIKE #{query} ESCAPE '!' OR o.boss_name LIKE #{query} ESCAPE '!' OR o.player_name LIKE #{query} ESCAPE '!' OR o.game_name LIKE #{query} ESCAPE '!')</if>
      ORDER BY o.created_at DESC LIMIT 500</script>
      """) List<Order> list(boolean staff,boolean hall,String userId,String playerId,String type,String game,String status,String refund,String query);
    @Select("SELECT * FROM club_order ORDER BY created_at DESC LIMIT 10000") List<Order> reporting();
    @Select("SELECT * FROM club_order ORDER BY created_at DESC LIMIT 6") List<Order> recent();
    @Select("SELECT COUNT(*) FROM club_order WHERE game_id=#{game} AND paid_at IS NOT NULL") long paidGameCount(String game);
    @Select("SELECT COALESCE(SUM(CASE WHEN paid_at IS NOT NULL THEN amount-CASE WHEN refund_status='refunded' THEN COALESCE(refund_amount,0) ELSE 0 END ELSE 0 END),0) AS revenue,COUNT(*) AS orders FROM club_order WHERE created_at>=#{start} AND created_at<#{end}") DayTotals dayTotals(long start,long end);
    @Select("""
        SELECT COALESCE(SUM(CASE WHEN paid_at>=#{start} AND paid_at<#{end} THEN amount-CASE WHEN refund_status='refunded' THEN COALESCE(refund_amount,0) ELSE 0 END ELSE 0 END),0) AS today_revenue,
        COALESCE(SUM(CASE WHEN created_at>=#{start} AND created_at<#{end} THEN 1 ELSE 0 END),0) AS today_orders,
        COALESCE(SUM(CASE WHEN status IN ('accepted','serving','confirming') THEN 1 ELSE 0 END),0) AS live_orders,
        COALESCE(SUM(CASE WHEN refund_status='pending' THEN 1 ELSE 0 END),0) AS refund_pending,
        COALESCE(SUM(CASE WHEN status='waiting' AND refund_status NOT IN ('pending','processing') THEN 1 ELSE 0 END),0) AS unassigned FROM club_order
        """) DashboardTotals dashboardTotals(long start,long end);
    @Select("SELECT * FROM club_order WHERE status='unpaid' AND expires_at<=#{now} ORDER BY expires_at LIMIT 100") List<Order> expired(long now);
    @Select("SELECT * FROM club_order WHERE status='waiting' AND paid_at IS NOT NULL AND preferred_player_id IS NULL AND refund_status NOT IN ('pending','processing') AND boss_id!=#{user} AND service_type=#{type} ORDER BY created_at LIMIT 100") List<Order> candidates(String user,String type);
    @Select("SELECT * FROM order_member WHERE order_id=#{id} ORDER BY position") List<Member> members(String id);
    @Insert("INSERT INTO order_member(order_id,player_id,name,position,settlement_id) VALUES(#{orderId},#{playerId},#{name},#{position},#{settlementId})") void addMember(Member m);
    @Delete("DELETE FROM order_member WHERE order_id=#{order} AND player_id=#{player}") void removeMember(String order,String player);
    @Delete("DELETE FROM order_member WHERE order_id=#{id}") void clearMembers(String id);
    @Update("UPDATE order_member SET settlement_id=#{income} WHERE order_id=#{order} AND player_id=#{player}") void linkIncome(String order,String player,String income);
    @Insert("INSERT INTO busy_slot(player_id,order_id) VALUES(#{player},#{order})") void occupy(String player,String order);
    @Select("SELECT order_id FROM busy_slot WHERE player_id=#{id}") String busy(String id);
    @Select("SELECT player_id FROM busy_slot") List<String> busyPlayers();
    @Delete("DELETE FROM busy_slot WHERE order_id=#{order} AND player_id=#{player}") void releaseMember(String order,String player);
    @Delete("DELETE FROM busy_slot WHERE order_id=#{id}") void release(String id);
    @Select("SELECT * FROM order_event WHERE order_id=#{id} ORDER BY at,id") List<Event> events(String id);
    @Insert("INSERT INTO order_event(id,order_id,at,actor,label,note) VALUES(#{id},#{orderId},#{at},#{actor},#{label},#{note})") void event(Event e);
    @Select("SELECT * FROM idempotency_request WHERE owner_id=#{owner} AND request_key=#{key}") Request request(String owner,String key);
    @Insert("INSERT INTO idempotency_request(owner_id,request_key,request_hash,order_id,created_at) VALUES(#{ownerId},#{requestKey},#{requestHash},#{orderId},#{createdAt})") void requestInsert(Request r);
}
