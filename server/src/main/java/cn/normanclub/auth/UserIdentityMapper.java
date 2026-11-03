package cn.normanclub.auth;

import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface UserIdentityMapper {
    class Entry {public String id,realNameCipher,idNumberCipher,idNumberHash,identityConsentVersion;public Long identitySubmittedAt,closedAt;}
    @Select("SELECT id,real_name_cipher,id_number_cipher,id_number_hash,identity_submitted_at,identity_consent_version,closed_at FROM club_user WHERE id=#{id}") Entry get(String id);
    @Select("SELECT id FROM club_user WHERE id_number_hash=#{hash}") String owner(String hash);
    @Select("SELECT id_number_hash FROM club_user WHERE id_number_hash IS NOT NULL AND id_number_hash>#{after} ORDER BY id_number_hash LIMIT 1000") List<String> hashes(String after);
    @Update("UPDATE club_user SET real_name_cipher=#{realNameCipher},id_number_cipher=#{idNumberCipher},id_number_hash=#{idNumberHash},identity_submitted_at=#{identitySubmittedAt},identity_consent_version=#{identityConsentVersion} WHERE id=#{id} AND identity_submitted_at IS NULL AND disabled=FALSE AND closed_at IS NULL") int submit(Entry entry);
}
