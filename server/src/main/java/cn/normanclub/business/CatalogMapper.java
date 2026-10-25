package cn.normanclub.business;

import java.util.List;
import org.apache.ibatis.annotations.*;
import cn.normanclub.business.Rows.*;

@Mapper
public interface CatalogMapper {
    @Select("SELECT * FROM service_profile WHERE user_id=#{userId}") Profile byUser(String userId);
    @Select("SELECT * FROM service_profile WHERE id=#{id}") Profile profile(String id);
    @Select("SELECT * FROM service_profile WHERE id=#{id} FOR UPDATE") Profile lockProfile(String id);
    @Select("SELECT * FROM service_profile ORDER BY created_at DESC LIMIT 1000") List<Profile> profiles();
    @Insert("INSERT INTO service_profile(id,user_id,name,subtitle,game_ids,tags,rating,completed,price,color,avatar,available,approved,bio,created_at) VALUES(#{id},#{userId},#{name},#{subtitle},#{gameIds},#{tags},#{rating},#{completed},#{price},#{color},#{avatar},#{available},#{approved},#{bio},#{createdAt})") void insertProfile(Profile p);
    @Update("UPDATE service_profile SET name=#{name},game_ids=#{gameIds},price=#{price},available=#{available},approved=#{approved} WHERE id=#{id}") void updateProfile(Profile p);
    @Select("SELECT * FROM service_qualification WHERE player_id=#{id}") List<Qualification> qualifications(String id);
    @Select("SELECT * FROM service_qualification") List<Qualification> allQualifications();
    @Select("SELECT MIN(p.price) FROM service_profile p JOIN service_qualification q ON q.player_id=p.id WHERE p.approved=TRUE AND q.service_type=#{type} AND q.game_id=#{game}") Integer minimumPrice(String type,String game);
    @Select("SELECT COUNT(*) FROM service_qualification WHERE player_id=#{id} AND service_type=#{type} AND game_id=#{game}") int qualified(String id,String type,String game);
    @Insert("INSERT INTO service_qualification(player_id,service_type,game_id,granted_at) VALUES(#{playerId},#{serviceType},#{gameId},#{grantedAt})") void grant(Qualification q);
    @Delete("DELETE FROM service_qualification WHERE player_id=#{id}") void revoke(String id);
    @Select("SELECT * FROM product WHERE id=#{id}") Product product(String id);
    @Select("SELECT * FROM product WHERE id=#{id} FOR UPDATE") Product lockProduct(String id);
    @Select("SELECT * FROM product WHERE active=TRUE ORDER BY CASE WHEN seller_type='club' THEN 0 ELSE 1 END,created_at DESC,id LIMIT 500") List<Product> publicProducts();
    @Select("SELECT * FROM product WHERE user_id=#{id} ORDER BY created_at DESC LIMIT 500") List<Product> ownProducts(String id);
    @Insert("INSERT INTO product(id,user_id,player_id,seller_type,seller_name,game_id,service_type,title,description,price,task,active,created_at) VALUES(#{id},#{userId},#{playerId},#{sellerType},#{sellerName},#{gameId},#{serviceType},#{title},#{description},#{price},#{task},#{active},#{createdAt})") void insertProduct(Product p);
    @Update("UPDATE product SET active=#{active} WHERE id=#{id}") void productActive(String id,boolean active);
    @Select("<script>SELECT * FROM qualification_application <if test='!staff'>WHERE user_id=#{userId}</if> ORDER BY created_at DESC LIMIT 500</script>") List<Application> applications(boolean staff,String userId);
    @Select("SELECT * FROM qualification_application WHERE id=#{id} FOR UPDATE") Application lockApplication(String id);
    @Insert("INSERT INTO qualification_application(id,user_id,player_id,name,service_type,game_ids,introduction,status,active_key,created_at) VALUES(#{id},#{userId},#{playerId},#{name},#{serviceType},#{gameIds},#{introduction},#{status},#{activeKey},#{createdAt})") void insertApplication(Application a);
    @Update("UPDATE qualification_application SET status=#{status},active_key=#{activeKey},score=#{score},review_note=#{reviewNote},reviewed_at=#{reviewedAt} WHERE id=#{id}") void updateApplication(Application a);
}
