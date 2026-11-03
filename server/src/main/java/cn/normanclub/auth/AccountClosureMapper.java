package cn.normanclub.auth;

import org.apache.ibatis.annotations.*;

@Mapper
public interface AccountClosureMapper {
    @Select("SELECT COUNT(*) FROM club_order o WHERE (o.boss_id=#{userId} OR o.player_id=#{playerId} OR EXISTS(SELECT 1 FROM order_member m WHERE m.order_id=o.id AND m.player_id=#{playerId})) AND (o.status NOT IN ('completed','cancelled') OR o.refund_status IN ('pending','processing'))") int openOrders(String userId,String playerId);
    @Select("SELECT COUNT(*) FROM income_entry i WHERE player_id=#{playerId} AND NOT EXISTS(SELECT 1 FROM wallet_income w WHERE w.income_id=i.id AND w.fund_mode='simulation') AND status<>'paid' AND amount>0") int unpaidIncome(String playerId);
    @Select("SELECT COUNT(*) FROM payment_attempt WHERE user_id=#{userId} AND status IN ('creating','pending','unknown','late_paid','duplicate_paid')") int openPayments(String userId);
    @Update("UPDATE club_user SET disabled=TRUE,closed_at=#{now},password_hash=NULL,real_name_cipher=NULL,id_number_cipher=NULL,id_number_hash=NULL,identity_submitted_at=NULL,identity_consent_version=NULL WHERE id=#{userId} AND closed_at IS NULL") int close(String userId,long now);
    @Delete("DELETE FROM wechat_payment_identity WHERE user_id=#{userId}") void wechatIdentity(String userId);
    @Delete("DELETE FROM wechat_oauth_state WHERE user_id=#{userId}") void wechatState(String userId);
    @Delete("DELETE FROM member_bank_account WHERE player_id=#{playerId}") void bank(String playerId);
    @Select("SELECT COUNT(*) FROM withdrawal_request WHERE player_id=#{playerId} AND status IN ('pending','approved','processing')") int withdrawals(String playerId);
    @Delete("DELETE FROM login_identity WHERE user_id=#{userId}") void identities(String userId);
    @Update("UPDATE service_profile SET approved=FALSE,available=FALSE WHERE user_id=#{userId}") void profile(String userId);
    @Update("UPDATE product SET active=FALSE WHERE user_id=#{userId}") void products(String userId);
    @Delete("DELETE FROM service_qualification WHERE player_id=#{playerId}") void qualifications(String playerId);
    @Update("UPDATE qualification_application SET status='rejected',active_key=NULL,reviewed_at=#{now},review_note='账号已注销' WHERE user_id=#{userId} AND status IN ('pending','assessment')") void applications(String userId,long now);
}
