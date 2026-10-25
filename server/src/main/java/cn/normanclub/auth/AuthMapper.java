package cn.normanclub.auth;

import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface AuthMapper {
    class User { public String id,name;public boolean disabled; public long createdAt; }
    class Identity { public String id,userId,provider,appId,subjectHash,subjectCipher,maskedValue;public long createdAt; }
    class Staff { public String id,username,name,passwordHash,role,totpCipher;public boolean disabled;public long lastTotpStep,createdAt; }
    class Session { public String tokenHash,accountId,audience,csrfHash;public boolean bearer;public long expiresAt,lastSeenAt,createdAt; }
    class Sms { public String phoneHash,codeHash;public int attempts;public long sentAt,expiresAt;public boolean consumed; }
    class Challenge { public String tokenHash,staffId,setupCipher;public int attempts;public long expiresAt;public boolean consumed; }
    @Select("SELECT * FROM club_user WHERE id=#{id}") User user(String id);
    @Select("SELECT * FROM club_user WHERE id=#{id} FOR UPDATE") User lockUser(String id);
    @Insert("INSERT INTO club_user(id,name,disabled,created_at) VALUES(#{id},#{name},FALSE,#{createdAt})") void insertUser(User user);
    @Select("SELECT * FROM login_identity WHERE provider='phone' AND app_id='' AND subject_hash=#{hash}") Identity phone(String hash);
    @Insert("INSERT INTO login_identity(id,user_id,provider,app_id,subject_hash,subject_cipher,masked_value,created_at) VALUES(#{id},#{userId},#{provider},#{appId},#{subjectHash},#{subjectCipher},#{maskedValue},#{createdAt})") void insertIdentity(Identity id);
    @Select("SELECT * FROM staff_account WHERE username=#{username}") Staff staffByUsername(String username);
    @Select("SELECT * FROM staff_account WHERE id=#{id}") Staff staff(String id);
    @Select("SELECT * FROM staff_account WHERE id=#{id} FOR UPDATE") Staff lockStaff(String id);
    @Select("SELECT COUNT(*) FROM staff_account WHERE role='admin' AND disabled=FALSE") int adminCount();
    @Insert("INSERT INTO staff_account(id,username,name,password_hash,role,disabled,totp_cipher,last_totp_step,created_at) VALUES(#{id},#{username},#{name},#{passwordHash},#{role},#{disabled},#{totpCipher},#{lastTotpStep},#{createdAt})") void insertStaff(Staff staff);
    @Update("UPDATE staff_account SET totp_cipher=#{cipher},last_totp_step=#{step} WHERE id=#{id}") void totp(String id,String cipher,long step);
    @Update("UPDATE staff_account SET disabled=#{disabled} WHERE id=#{id}") void disableStaff(String id,boolean disabled);
    @Update("UPDATE staff_account SET password_hash=#{hash},totp_cipher=NULL,last_totp_step=-1 WHERE id=#{id}") void resetStaff(String id,String hash);
    @Select("SELECT * FROM staff_account ORDER BY created_at LIMIT 200") List<Staff> staffList();
    @Select("SELECT * FROM auth_session WHERE token_hash=#{hash}") Session session(String hash);
    @Insert("INSERT INTO auth_session(token_hash,account_id,audience,csrf_hash,bearer,expires_at,last_seen_at,created_at) VALUES(#{tokenHash},#{accountId},#{audience},#{csrfHash},#{bearer},#{expiresAt},#{lastSeenAt},#{createdAt})") void insertSession(Session session);
    @Update("UPDATE auth_session SET last_seen_at=#{now} WHERE token_hash=#{hash}") void touch(String hash,long now);
    @Delete("DELETE FROM auth_session WHERE token_hash=#{hash}") void logout(String hash);
    @Delete("DELETE FROM auth_session WHERE account_id=#{id} AND audience=#{audience}") void revoke(String id,String audience);
    @Select("SELECT * FROM sms_code WHERE phone_hash=#{hash} FOR UPDATE") Sms sms(String hash);
    @Insert("INSERT INTO sms_code(phone_hash,code_hash,attempts,sent_at,expires_at,consumed) VALUES(#{phoneHash},#{codeHash},#{attempts},#{sentAt},#{expiresAt},#{consumed}) ON DUPLICATE KEY UPDATE code_hash=#{codeHash},attempts=#{attempts},sent_at=#{sentAt},expires_at=#{expiresAt},consumed=#{consumed}") void putSms(Sms sms);
    @Select("SELECT * FROM auth_challenge WHERE token_hash=#{hash} FOR UPDATE") Challenge challenge(String hash);
    @Insert("INSERT INTO auth_challenge(token_hash,staff_id,setup_cipher,attempts,expires_at,consumed) VALUES(#{tokenHash},#{staffId},#{setupCipher},0,#{expiresAt},FALSE)") void insertChallenge(Challenge c);
    @Update("UPDATE auth_challenge SET attempts=#{attempts},consumed=#{consumed} WHERE token_hash=#{tokenHash}") void updateChallenge(Challenge c);
    @Delete("DELETE FROM auth_challenge WHERE staff_id=#{id}") void clearChallenges(String id);
    @Delete("DELETE FROM auth_session WHERE expires_at<#{now}") void purgeSessions(long now);
    @Delete("DELETE FROM sms_code WHERE expires_at<#{before}") void purgeSms(long before);
    @Delete("DELETE FROM auth_challenge WHERE expires_at<#{before}") void purgeChallenges(long before);
}
