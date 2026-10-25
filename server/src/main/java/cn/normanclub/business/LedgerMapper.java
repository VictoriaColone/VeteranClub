package cn.normanclub.business;

import java.util.List;
import org.apache.ibatis.annotations.*;
import cn.normanclub.business.Rows.*;

@Mapper
public interface LedgerMapper {
    @Select("SELECT * FROM income_entry WHERE order_id=#{order} AND player_id=#{player}") Income income(String order,String player);
    @Select("<script>SELECT * FROM income_entry <if test='player!=null'>WHERE player_id=#{player}</if> ORDER BY created_at DESC LIMIT 1000</script>") List<Income> incomes(String player);
    @Select("<script>SELECT COALESCE(SUM(amount),0) AS total,COALESCE(SUM(CASE WHEN status='paid' THEN amount ELSE 0 END),0) AS paid FROM income_entry <if test='player!=null'>WHERE player_id=#{player}</if></script>") WalletTotals totals(String player);
    @Insert("INSERT INTO income_entry(id,order_id,player_id,player_name,amount,status,created_at) VALUES(#{id},#{orderId},#{playerId},#{playerName},#{amount},#{status},#{createdAt})") void insertIncome(Income i);
    @Insert("INSERT INTO payout(id,income_id,amount,status,created_at,updated_at) VALUES(#{id},#{income},#{amount},'awaiting_channel',#{now},#{now})") void insertPayout(String id,String income,int amount,long now);
    @Select("SELECT * FROM payment_attempt WHERE id=#{id}") Payment payment(String id);
    @Select("SELECT * FROM payment_attempt WHERE id=#{id} FOR UPDATE") Payment lockPayment(String id);
    @Select("SELECT * FROM payment_attempt WHERE user_id=#{user} AND request_key=#{key}") Payment paymentRequest(String user,String key);
    @Select("SELECT * FROM payment_attempt WHERE order_id=#{id}") List<Payment> payments(String id);
    @Insert("INSERT INTO payment_attempt(id,order_id,user_id,channel,request_key,amount,currency,status,created_at,updated_at) VALUES(#{id},#{orderId},#{userId},#{channel},#{requestKey},#{amount},#{currency},#{status},#{createdAt},#{updatedAt})") void insertPayment(Payment p);
    @Update("UPDATE payment_attempt SET status=#{status},transaction_id=#{transactionId},merchant_id=#{merchantId},client_params=#{clientParams},updated_at=#{updatedAt} WHERE id=#{id}") void updatePayment(Payment p);
    @Select("SELECT COUNT(*) FROM payment_notification WHERE event_key=#{key}") int notified(String key);
    @Insert("INSERT INTO payment_notification(event_key,payment_id,received_at) VALUES(#{key},#{payment},#{now})") void notification(String key,String payment,long now);
    @Select("SELECT * FROM refund_request WHERE id=#{id} FOR UPDATE") Refund lockRefund(String id);
    @Insert("INSERT INTO refund_request(id,order_id,payment_id,amount,status,reason,created_at) VALUES(#{id},#{orderId},#{paymentId},#{amount},#{status},#{reason},#{createdAt})") void insertRefund(Refund r);
    @Update("UPDATE refund_request SET status=#{status},review_note=#{reviewNote},reviewed_at=#{reviewedAt},channel_refund_id=#{channelRefundId} WHERE id=#{id}") void updateRefund(Refund r);
}
