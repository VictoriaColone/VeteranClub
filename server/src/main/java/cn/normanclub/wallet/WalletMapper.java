package cn.normanclub.wallet;

import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface WalletMapper {
    class Bank {public String playerId,holderCipher,numberCipher,bankName,lastFour;public long updatedAt;}
    class Balance {public long total,pending,available,frozen,paid,simulation;}
    class Income {public String incomeId,orderId,playerId,fundMode;public long amount,frozen,paid,releaseAt;public boolean blocked;}
    class Withdrawal {public String id,userId,playerId,playerName,requestKey,status,holderCipher,numberCipher,bankName,lastFour,reviewNote,paymentReference,paymentNote;public long amount,createdAt,updatedAt;public Long paidAt;}
    class Allocation {public String incomeId;public long amount;}
    class Entry {public String id,playerId,fundMode,kind,referenceId;public long amount,createdAt;}
    @Select("SELECT * FROM member_bank_account WHERE player_id=#{id}") Bank bank(String id);
    @Delete("DELETE FROM member_bank_account WHERE player_id=#{id}") void deleteBank(String id);
    @Insert("INSERT INTO member_bank_account(player_id,holder_cipher,number_cipher,bank_name,last_four,updated_at) VALUES(#{playerId},#{holderCipher},#{numberCipher},#{bankName},#{lastFour},#{updatedAt})") void insertBank(Bank b);
    @Select("SELECT COALESCE(SUM(CASE WHEN fund_mode='real' THEN amount ELSE 0 END),0) total,COALESCE(SUM(CASE WHEN fund_mode='real' AND (release_at>#{now} OR blocked=TRUE) THEN amount-frozen-paid ELSE 0 END),0) pending,COALESCE(SUM(CASE WHEN fund_mode='real' AND release_at<=#{now} AND blocked=FALSE THEN amount-frozen-paid ELSE 0 END),0) available,COALESCE(SUM(CASE WHEN fund_mode='real' THEN frozen ELSE 0 END),0) frozen,COALESCE(SUM(CASE WHEN fund_mode='real' THEN paid ELSE 0 END),0) paid,COALESCE(SUM(CASE WHEN fund_mode='simulation' THEN amount ELSE 0 END),0) simulation FROM wallet_income WHERE player_id=#{id}") Balance balance(String id,long now);
    @Select("SELECT * FROM wallet_income WHERE player_id=#{id} AND fund_mode='real' AND release_at<=#{now} AND blocked=FALSE AND amount>frozen+paid ORDER BY release_at,income_id FOR UPDATE") List<Income> available(String id,long now);
    @Select("SELECT * FROM wallet_income WHERE order_id=#{id} ORDER BY player_id") List<Income> orderIncome(String id);
    @Select("SELECT * FROM wallet_income WHERE income_id=#{id} FOR UPDATE") Income income(String id);
    @Insert("INSERT INTO wallet_income(income_id,order_id,player_id,fund_mode,amount,release_at) VALUES(#{incomeId},#{orderId},#{playerId},#{fundMode},#{amount},#{releaseAt})") void insertIncome(Income i);
    @Update("UPDATE wallet_income SET amount=#{amount},frozen=#{frozen},paid=#{paid},blocked=#{blocked} WHERE income_id=#{incomeId}") void updateIncome(Income i);
    @Select("SELECT * FROM withdrawal_request WHERE user_id=#{user} AND request_key=#{key}") Withdrawal request(String user,String key);
    @Select("SELECT * FROM withdrawal_request WHERE id=#{id}") Withdrawal withdrawal(String id);
    @Select("<script>SELECT * FROM withdrawal_request <where><if test='player!=null'>player_id=#{player}</if></where> ORDER BY created_at DESC,id DESC LIMIT 200</script>") List<Withdrawal> withdrawals(String player);
    @Select("SELECT COUNT(*) FROM withdrawal_request WHERE player_id=#{id} AND status IN ('pending','approved','processing')") int active(String id);
    @Select("SELECT id FROM withdrawal_request WHERE payment_reference=#{reference}") String referenceOwner(String reference);
    @Insert("INSERT INTO withdrawal_request(id,user_id,player_id,player_name,request_key,amount,status,holder_cipher,number_cipher,bank_name,last_four,created_at,updated_at) VALUES(#{id},#{userId},#{playerId},#{playerName},#{requestKey},#{amount},#{status},#{holderCipher},#{numberCipher},#{bankName},#{lastFour},#{createdAt},#{updatedAt})") void insertWithdrawal(Withdrawal w);
    @Update("UPDATE withdrawal_request SET status=#{status},review_note=#{reviewNote},payment_reference=#{paymentReference},payment_note=#{paymentNote},updated_at=#{updatedAt},paid_at=#{paidAt} WHERE id=#{id}") void updateWithdrawal(Withdrawal w);
    @Insert("INSERT INTO withdrawal_allocation(withdrawal_id,income_id,amount) VALUES(#{withdrawal},#{income},#{amount})") void allocate(String withdrawal,String income,long amount);
    @Select("SELECT income_id,amount FROM withdrawal_allocation WHERE withdrawal_id=#{id} ORDER BY income_id") List<Allocation> allocations(String id);
    @Insert("INSERT INTO wallet_entry(id,player_id,fund_mode,kind,reference_id,amount,created_at) VALUES(#{id},#{playerId},#{fundMode},#{kind},#{referenceId},#{amount},#{createdAt})") void entry(Entry e);
    @Select("SELECT * FROM wallet_entry WHERE player_id=#{id} ORDER BY created_at DESC,id DESC LIMIT 100") List<Entry> entries(String id);
    @Update("UPDATE income_entry SET amount=#{amount},status=#{status},paid_at=#{paidAt} WHERE id=#{id}") void syncIncome(String id,int amount,String status,Long paidAt);
}
