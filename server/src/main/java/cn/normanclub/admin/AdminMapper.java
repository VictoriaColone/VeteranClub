package cn.normanclub.admin;

import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface AdminMapper {
    class UserView {public String id,name,playerId,phoneMasked;public boolean disabled;public long createdAt;}
    @Select("SELECT u.id,u.name,u.disabled,u.created_at,p.id AS player_id,i.masked_value AS phone_masked FROM club_user u LEFT JOIN service_profile p ON p.user_id=u.id LEFT JOIN login_identity i ON i.user_id=u.id AND i.provider='phone' ORDER BY u.created_at DESC LIMIT 1000") List<UserView> users();
    @Update("UPDATE club_user SET disabled=#{disabled} WHERE id=#{id}") int disable(String id,boolean disabled);
    @Select("SELECT id FROM staff_account WHERE role='admin' AND disabled=FALSE ORDER BY id FOR UPDATE") List<String> lockAdmins();
}
