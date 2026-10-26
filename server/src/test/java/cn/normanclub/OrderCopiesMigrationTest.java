package cn.normanclub;

import java.sql.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OrderCopiesMigrationTest {
    @Test void administratorCanPrepareSchemaBeforeAppMigration()throws Exception{
        String url="jdbc:h2:mem:prepared_copies_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1";
        try(Connection db=DriverManager.getConnection(url,"sa","");Statement statement=db.createStatement()){
            var source=new org.springframework.jdbc.datasource.SingleConnectionDataSource(db,true);
            Flyway.configure().dataSource(source).target("4").load().migrate();
            statement.execute("ALTER TABLE club_order ADD COLUMN copies INT NOT NULL DEFAULT 1");
            statement.execute("ALTER TABLE club_order ADD CONSTRAINT ck_order_copies CHECK(copies BETWEEN 1 AND 99)");
            assertEquals(1,Flyway.configure().dataSource(source).load().migrate().migrationsExecuted);
            assertEquals(0,Flyway.configure().dataSource(source).load().migrate().migrationsExecuted);
        }
    }

    @Test void existingOrdersKeepTheirAmountsAndDefaultToOneCopy()throws Exception{
        String url="jdbc:h2:mem:copies_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1";
        try(Connection db=DriverManager.getConnection(url,"sa","");Statement statement=db.createStatement()){
            var source=new org.springframework.jdbc.datasource.SingleConnectionDataSource(db,true);
            Flyway.configure().dataSource(source).target("4").load().migrate();
            statement.execute("INSERT INTO club_user(id,name,created_at) VALUES('buyer','测试用户',1)");
            statement.execute("INSERT INTO club_order(id,boss_id,boss_name,game_id,game_name,region,service_type,task,unit_price,quantity,amount,commission_bps,earnings,status,refund_status,note,created_at,expires_at) VALUES('old-order','buyer','测试用户','delta','三角洲','国服','hourly','',6800,2,13600,2000,10880,'waiting','none','',1,600001)");
            Flyway.configure().dataSource(source).load().migrate();
            try(ResultSet rows=statement.executeQuery("SELECT copies,quantity,amount,earnings FROM club_order WHERE id='old-order'")){
            assertTrue(rows.next());assertEquals(1,rows.getInt("copies"));assertEquals(2,rows.getInt("quantity"));assertEquals(13600,rows.getInt("amount"));assertEquals(10880,rows.getInt("earnings"));
            assertThrows(SQLException.class,()->statement.executeUpdate("UPDATE club_order SET copies=0 WHERE id='old-order'"));
            }
        }
    }
}
