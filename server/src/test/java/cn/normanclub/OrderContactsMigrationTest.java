package cn.normanclub;

import java.sql.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OrderContactsMigrationTest {
    @Test void nullableContactColumnsCanBePreparedAndDoNotChangeHistoricalOrders()throws Exception{
        String url="jdbc:h2:mem:contacts_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1";
        try(Connection db=DriverManager.getConnection(url,"sa","");Statement sql=db.createStatement()){
            var source=new org.springframework.jdbc.datasource.SingleConnectionDataSource(db,true);
            Flyway.configure().dataSource(source).target("5").load().migrate();
            sql.execute("INSERT INTO club_user(id,name,created_at) VALUES('buyer','测试用户',1)");
            sql.execute("INSERT INTO club_order(id,boss_id,boss_name,game_id,game_name,region,service_type,task,unit_price,quantity,copies,amount,commission_bps,earnings,status,refund_status,note,created_at,expires_at) VALUES('old','buyer','测试用户','delta','三角洲','国服','hourly','',6800,2,3,40800,2000,32640,'waiting','none','',1,600001)");
            sql.execute("ALTER TABLE club_order ADD COLUMN contact_phone_enc VARCHAR(256) NULL");
            sql.execute("ALTER TABLE club_order ADD COLUMN contact_wechat_enc VARCHAR(512) NULL");
            assertEquals(1,Flyway.configure().dataSource(source).target("6").load().migrate().migrationsExecuted);
            assertEquals(0,Flyway.configure().dataSource(source).target("6").load().migrate().migrationsExecuted);
            try(ResultSet row=sql.executeQuery("SELECT * FROM club_order WHERE id='old'")){
                assertTrue(row.next());assertEquals(2,row.getInt("quantity"));assertEquals(3,row.getInt("copies"));assertEquals(40800,row.getInt("amount"));assertEquals(32640,row.getInt("earnings"));assertNull(row.getString("contact_phone_enc"));assertNull(row.getString("contact_wechat_enc"));
            }
        }
    }
}
