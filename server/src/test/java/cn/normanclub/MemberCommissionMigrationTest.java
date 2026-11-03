package cn.normanclub;

import java.sql.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MemberCommissionMigrationTest {
    @Test void preparedColumnsAndConstraintsPreserveExistingOrderRules()throws Exception{
        String url="jdbc:h2:mem:commission_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1";
        try(Connection db=DriverManager.getConnection(url,"sa","");Statement sql=db.createStatement()){
            var source=new org.springframework.jdbc.datasource.SingleConnectionDataSource(db,true);
            Flyway.configure().dataSource(source).target("6").load().migrate();
            sql.execute("INSERT INTO club_user(id,name,created_at) VALUES('buyer','测试用户',1)");
            sql.execute("INSERT INTO service_profile(id,user_id,name,subtitle,game_ids,tags,color,avatar,bio,created_at) VALUES('member','buyer','测试用户','','[]','[]','#fff','用','',1)");
            sql.execute("INSERT INTO club_order(id,boss_id,boss_name,game_id,game_name,region,service_type,task,unit_price,quantity,amount,commission_bps,earnings,status,note,created_at,expires_at) VALUES('old','buyer','测试用户','delta','三角洲','国服','escort','任务',101,1,101,3000,70,'completed','',1,2)");
            sql.execute("ALTER TABLE service_profile ADD COLUMN commission_bps INT NOT NULL DEFAULT 2000");
            sql.execute("ALTER TABLE club_order ADD COLUMN member_commission BOOLEAN NOT NULL DEFAULT FALSE");
            sql.execute("ALTER TABLE order_member ADD COLUMN commission_bps INT NULL");
            sql.execute("ALTER TABLE service_profile ADD CONSTRAINT ck_profile_commission CHECK(commission_bps BETWEEN 0 AND 10000)");
            sql.execute("ALTER TABLE order_member ADD CONSTRAINT ck_member_commission CHECK(commission_bps IS NULL OR commission_bps BETWEEN 0 AND 10000)");
            assertEquals(1,Flyway.configure().dataSource(source).target("7").load().migrate().migrationsExecuted);assertEquals(0,Flyway.configure().dataSource(source).target("7").load().migrate().migrationsExecuted);
            try(ResultSet row=sql.executeQuery("SELECT * FROM club_order WHERE id='old'")){assertTrue(row.next());assertFalse(row.getBoolean("member_commission"));assertEquals(3000,row.getInt("commission_bps"));assertEquals(70,row.getInt("earnings"));}
            try(ResultSet row=sql.executeQuery("SELECT commission_bps FROM service_profile WHERE id='member'")){assertTrue(row.next());assertEquals(2000,row.getInt(1));}
            assertThrows(SQLException.class,()->sql.execute("UPDATE service_profile SET commission_bps=10001 WHERE id='member'"));
        }
    }
}
